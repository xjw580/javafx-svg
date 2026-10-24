package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.*
import javafx.scene.text.Text
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.FontPosture
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Collections
import kotlin.math.hypot
import kotlin.math.min

internal data class SvgViewport(var width: Double, var height: Double, var x: Double = 0.0, var y: Double = 0.0) {
    val diagonal: Double get() = hypot(width, height) / kotlin.math.sqrt(2.0)
}

internal class FxSvgRenderer(private val document: SvgDocument) {
    private val issues = linkedSetOf<SvgDiagnostic>().apply { addAll(document.diagnostics) }
    val diagnostics: List<SvgDiagnostic> get() = Collections.unmodifiableList(issues.toList())
    private fun report(message: String, element: SvgElement? = null) {
        if (document.options.strict) throw SvgException(message + (element?.id?.let { " (#$it)" } ?: ""))
        issues += SvgDiagnostic(message, element?.id)
    }
    private val styles = SvgStyles(document, ::report)
    private val paints = SvgPaints(document, styles)
    private val animations = SvgAnimations(document, ::report) { if (themeReady) root.parent?.requestLayout() }
    val hasAnimations: Boolean get() = animations.hasAnimations
    val animationDuration: Double get() = animations.duration
    /** Prepare the clock without firing node-property listeners before the public time is published. */
    fun prepareAnimationFrame(seconds: Double, updateTransforms: Boolean): Boolean {
        animations.seek(seconds)
        return updateTransforms && transformFramesOnly && layoutComplete && issues.isEmpty()
    }

    fun refreshAnimationTransforms() = animations.refreshTransforms()

    /** Return true when the current viewport can render this frame without a SvgView layout. */
    fun seekAnimations(seconds: Double, updateTransforms: Boolean = false): Boolean {
        if (prepareAnimationFrame(seconds, updateTransforms)) {
            refreshAnimationTransforms()
            return true
        }
        if (themeReady) root.parent?.requestLayout()
        return false
    }

    private fun computedStyle(element: SvgElement, ancestors: List<SvgElement>, parent: Map<String, String>, viewport: SvgViewport): Map<String, String> {
        val inherited = mutableSetOf<String>()
        val base = styles.compute(element, ancestors, parent, inherited)
        return animations.style(element, base, parent, inherited, viewport)
    }
    private val updates = mutableListOf<() -> Unit>()
    private val geometryUpdates = mutableListOf<() -> Unit>()
    private val paintUpdates = mutableListOf<() -> Unit>()
    private val shapeStyles = SvgShapeStyles { if (themeReady) root.parent?.requestLayout() }
    private var themeReady = false
    private var hasTextLayout = false
    private val transformFramesOnly: Boolean
    private val activeReferences = linkedSetOf<String>()
    private var clipping = false
    private var masking = false
    private var paintContext: SvgMarkerContext? = null
    private val maskBudget = SvgMaskBudget(document.options.maxMaskPixels)
    private val masks = mutableListOf<SvgMask>()
    private val patternBudget = SvgPatternBudget(document.options.maxPatternPixels)
    private val patterns = mutableListOf<SvgPattern>()
    private val definitions = Group().apply { isManaged=false; opacity=0.0; clip=Rectangle() }
    private var renderedElements = 0
    private val box = document.root["viewBox"]?.let(SvgBox::parse)
    val intrinsicWidth = document.root["width"]?.let { document.lengths.resolve(it, box?.width ?: 300.0) } ?: box?.width ?: 300.0
    val intrinsicHeight = document.root["height"]?.let { document.lengths.resolve(it, box?.height ?: 150.0) } ?: box?.height ?: 150.0
    private val viewport = SvgViewport(box?.width ?: intrinsicWidth, box?.height ?: intrinsicHeight, box?.x ?: 0.0, box?.y ?: 0.0)
    private val rootCssViewport = SvgViewport(intrinsicWidth, intrinsicHeight)
    private val content: Node
    val root: Group
    private val viewportClip = Rectangle()
    private val renderCache: SvgRenderCache

    init {
        require(intrinsicWidth >= 0 && intrinsicHeight >= 0) { "Negative SVG dimensions" }
        content = render(document.root, emptyList(), emptyMap(), viewport, true) ?: Group()
        root = Group(content, definitions).apply { clip = viewportClip; isManaged = false }
        // Effect callbacks and text flow must observe the normal geometry/paint/transform order.
        transformFramesOnly = animations.transformsOnly && updates.isEmpty() && !hasTextLayout
        themeReady = true
        val candidates = if (!animations.hasAnimations) listOf(root)
            else if (transformFramesOnly) animations.cacheTargets.let { targets ->
                targets.filter { candidate -> targets.none { other ->
                    other !== candidate && generateSequence(other.parent as Node?) { it.parent }.any { it === candidate }
                } }
            } else emptyList()
        renderCache = SvgRenderCache(root,candidates,document.options.maxCachePixels, deferUntilMotion = !animations.hasAnimations)
    }

    fun setRenderCache(enabled: Boolean) { renderCache.enabled = enabled }

    fun setTheme(fill: Paint?, stroke: Paint?, strokeWidth: Double) = shapeStyles.setTheme(fill, stroke, strokeWidth)

    fun layout(width: Double, height: Double, preserveRatio: Boolean) {
        layoutComplete = false
        shapeStyles.refresh()
        rootCssViewport.width = width; rootCssViewport.height = height
        if (width != layoutWidth || height != layoutHeight || preserveRatio != layoutPreserveRatio) {
            viewport.width = box?.width ?: width
            viewport.height = box?.height ?: height
            root.isVisible = width > 0 && height > 0 && viewport.width > 0 && viewport.height > 0
            viewportClip.width = width
            viewportClip.height = height
            root.transforms.clear()
            box?.transform(width, height, if (preserveRatio) document.root["preserveAspectRatio"] else "none")?.let { next ->
                val current = viewportTransform
                if (current == null) {
                    viewportTransform = next
                    content.transforms.add(next)
                } else current.setToTransform(next)
            }
            layoutWidth = width
            layoutHeight = height
            layoutPreserveRatio = preserveRatio
        }
        // Resolve geometry and source paints before transforms; effects then see the completed frame.
        geometryUpdates.forEach { it() }
        paintUpdates.forEach { it() }
        animations.refreshTransforms()
        updates.forEach { it() }
        renderCache.refresh()
        layoutComplete = true
    }
    private var viewportTransform: javafx.scene.transform.Affine? = null
    private var layoutComplete = false
    private var layoutWidth = Double.NaN
    private var layoutHeight = Double.NaN
    private var layoutPreserveRatio = true

    private fun render(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean = false, referenced: Boolean = false
    ): Node? {
        val updateCheckpoint = updates.size
        val geometryCheckpoint = geometryUpdates.size
        val paintCheckpoint = paintUpdates.size
        val styleCheckpoint = shapeStyles.size
        val maskCheckpoint = masks.size
        val patternCheckpoint = patterns.size
        val animationCheckpoint = animations.size
        return try {
            require(ancestors.size < document.options.maxDepth) { "SVG reference depth limit exceeded" }
            require(++renderedElements <= document.options.maxElements) { "SVG expanded element limit exceeded" }
            renderElement(element, ancestors, parentStyle, viewport, isRoot, referenced)
        } catch (e: IllegalArgumentException) {
            while (updates.size > updateCheckpoint) updates.removeAt(updates.lastIndex)
            while (geometryUpdates.size > geometryCheckpoint) geometryUpdates.removeAt(geometryUpdates.lastIndex)
            while (paintUpdates.size > paintCheckpoint) paintUpdates.removeAt(paintUpdates.lastIndex)
            animations.discardFrom(animationCheckpoint)
            shapeStyles.discardFrom(styleCheckpoint)
            while (masks.size > maskCheckpoint) masks.removeAt(masks.lastIndex).dispose()
            while (patterns.size > patternCheckpoint) patterns.removeAt(patterns.lastIndex).also { definitions.children.remove(it.hidden); it.dispose() }
            report(e.message ?: "Invalid SVG element", element)
            null
        }
    }

    private fun renderElement(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean, referenced: Boolean
    ): Node? {
        if (element.name.startsWith('{') || element.name in SvgAnimation.NAMES) return null
        if (element.name in setOf("defs", "style", "title", "desc", "metadata", "linearGradient", "radialGradient", "stop", "filter")) return null
        if (element.name == "symbol" && !referenced) return null
        if (element.name in setOf("clipPath", "mask", "marker", "pattern") && !referenced) return null
        if (element.name in setOf("text", "textPath")) hasTextLayout = true
        val computed = computedStyle(element, ancestors, parentStyle, viewport)
        val style = when (element.name) {
            "mask" -> computed + mapOf("display" to "inline", "opacity" to "1", "filter" to "none")
            "marker" -> computed + ("display" to "inline")
            else -> computed
        }
        if (style.getValue("display") == "none") return null
        if (element.name in setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "image", "use")) {
            element.children.filter { !it.name.startsWith('{') && it.name !in setOf("title", "desc", "metadata", "marker") + SvgAnimation.NAMES }.forEach {
                report("Unsupported child ${it.name} inside ${element.name}", it)
            }
        }
        for (attribute in listOf("paint-order")) {
            if (element[attribute] != null) report("Unsupported SVG attribute: $attribute", element)
        }
        val geometry = animations.geometry(element, viewport)
        fun length(name: String, axis: Double, default: String = "0") = geometry.length(name, axis, default)
        fun updateGeometry(action: () -> Unit) = update(element, if (geometry.isAnimated) null else viewport, phase = geometryUpdates, action = action)
        fun setGeometry(property: javafx.beans.property.Property<Number>, value: Double) {
            if (geometry.isAnimated) setSvgBaseStyle(property, value) else property.value = value
        }
        fun positive(value: Double): Double = value.also { require(it >= 0) { "Negative SVG size" } }
        val node: Node = when (element.name) {
            "text", "textPath" -> if (element.children.any { it.name == "textPath" }) {
                val paths = element.children.filter { it.name == "textPath" }
                require(paths.size == 1 && element.children.all { it.name.startsWith('{') || it.name in setOf("textPath", "title", "desc", "metadata") + SvgAnimation.NAMES }
                    && element.content.filterIsInstance<SvgContent.Characters>().all { it.value.isBlank() }) {
                    "textPath currently requires one path run without mixed text/tspan"
                }
                require(element["textLength"] == null) { "textLength with textPath is unsupported" }
                Group().apply {
                    render(paths.single(), ancestors + element, style, viewport)?.let { children += it }
                }
            } else if (element.children.any { it.name == "tspan" }) {
                renderTextSpans(element, ancestors, style, viewport, geometry)
            } else {
                val text = Text()
                element.children.filter { !it.name.startsWith('{') && it.name !in SvgAnimation.NAMES }.forEach { report("Unsupported text child: ${it.name}", it) }
                listOf("rotate", "dominant-baseline", "writing-mode").forEach {
                    if (element[it] != null) report("Unsupported text attribute: $it", element)
                }
                val preserve = (element["xml:space"] ?: ancestors.asReversed().firstNotNullOfOrNull { it["xml:space"] }) == "preserve"
                text.text = if (preserve) element.text else element.text.replace(Regex("\\s+"), " ").trim()
                configureTextFont(text, style)
                val anchor = textAnchor(style)
                val lengthAdjust = element["lengthAdjust"] ?: "spacing"
                require(lengthAdjust in setOf("spacing", "spacingAndGlyphs")) { "Invalid lengthAdjust" }
                val path = if (element.name == "textPath") {
                    require(element["method"] in setOf(null, "align")) { "textPath method=stretch is unsupported" }
                    require(element["spacing"] in setOf(null, "auto", "exact")) { "Invalid textPath spacing" }
                    require(element.children.all { it.name.startsWith('{') || it.name in setOf("title", "desc", "metadata") }) { "Nested textPath runs are unsupported" }
                    val href = element["href"] ?: element["xlink:href"] ?: throw SvgException("Missing textPath href")
                    require(href.startsWith('#') && href.length > 1) { "Only local textPath references are supported" }
                    val target = document.elementsById[href.drop(1)] ?: throw SvgException("Missing textPath path")
                    require(target.name == "path") { "textPath must reference a path" }
                    require(element["textLength"] == null) { "textLength with textPath is unsupported" }
                    document.geometry.measure(target["d"] ?: "", target["transform"])
                } else null
                val adjusted = if (element["textLength"] != null || path != null) {
                    try {
                        SvgAdjustedText(text, lengthAdjust == "spacingAndGlyphs", anchor,
                            document.options.maxElements - renderedElements, path).also { renderedElements += it.glyphCount }
                    } catch (e: IllegalArgumentException) {
                        report(e.message ?: "Unsupported textLength layout", element)
                        null
                    }
                } else null
                update(element, phase = geometryUpdates) {
                    val x = length("x", viewport.width) + length("dx", viewport.width)
                    val y = length("y", viewport.height) + length("dy", viewport.height)
                    if (path != null && adjusted != null) {
                        val reference = document.elementsById[(element["href"] ?: element["xlink:href"])!!.drop(1)]!!
                        val declared = reference["pathLength"]?.let { SvgNumbers.scalar(it).also { n -> require(n > 0) { "Invalid pathLength" } } }
                        val calibration = if (declared == null) 1.0 else path.length / declared
                        val offset = element["startOffset"] ?: "0"
                        val start = document.lengths.resolve(offset, path.length) * if (offset.trim().endsWith('%')) 1.0 else calibration
                        val parent = ancestors.lastOrNull { it.name == "text" }
                        val along = listOf("x","dx").sumOf { document.lengths.resolve(parent?.get(it) ?: "0", viewport.width) }
                        val normal = document.lengths.resolve(parent?.get("dy") ?: "0", viewport.height)
                        adjusted.positionPath(start + along + x, normal + length("dy",viewport.height))
                    } else if (adjusted != null) adjusted.position(x, y, length("textLength", viewport.width))
                    else {
                        setGeometry(text.xProperty(), x - anchor * text.layoutBounds.width)
                        setGeometry(text.yProperty(), y)
                    }
                }
                adjusted ?: text
            }
            "image" -> {
                require(!clipping) { "Images are not supported inside clipPath" }
                val href = element["href"] ?: element["xlink:href"] ?: throw SvgException("Missing image href")
                val data = Regex("data:image/(png|jpeg);base64,([\\s\\S]*)").matchEntire(href)
                    ?: throw SvgException("Only embedded base64 PNG/JPEG images are supported")
                val bytes = Base64.getDecoder().decode(data.groupValues[2].filterNot { it.isWhitespace() })
                val image = Image(ByteArrayInputStream(bytes))
                require(!image.isError && image.width > 0 && image.height > 0) { "Cannot decode SVG image" }
                val imageView = ImageView(image)
                val group = Group(imageView)
                val clip = Rectangle()
                group.clip = clip
                updateGeometry {
                    val x = length("x", viewport.width); val y = length("y", viewport.height)
                    val width = positive(length("width", viewport.width)); val height = positive(length("height", viewport.height))
                    imageView.transforms.setAll(javafx.scene.transform.Translate(x, y),
                        SvgBox(0.0, 0.0, image.width, image.height).transform(width, height, element["preserveAspectRatio"]))
                    clip.x = x; clip.y = y; clip.width = width; clip.height = height
                    imageView.isVisible = width > 0 && height > 0
                }
                group
            }
            "use" -> {
                val href = element["href"] ?: element["xlink:href"] ?: throw SvgException("Missing use href")
                require(href.startsWith('#') && href.length > 1) { "Only local use references are supported: $href" }
                val target = document.elementsById[href.drop(1)] ?: throw SvgException("Missing use target: $href")
                require(target.name != "mask") { "use cannot reference a mask element" }
                require(target.name != "marker") { "use cannot reference a marker element" }
                require(activeReferences.add(href)) { "Circular use reference: $href" }
                try {
                    val instance = if (target.name in setOf("svg", "symbol")) {
                        val attributes = target.attributes.toMutableMap()
                        listOf("width", "height").forEach { key -> element[key]?.let { attributes[key] = it } }
                        SvgElement(target.name, attributes, target.children, target.text, target.content)
                    } else target
                    val group = Group()
                    updateGeometry {
                        setGeometry(group.layoutXProperty(), length("x", viewport.width))
                        setGeometry(group.layoutYProperty(), length("y", viewport.height))
                    }
                    render(instance, ancestors + element, style, viewport, referenced = true)?.let { group.children += it }
                    group
                } finally { activeReferences.remove(href) }
            }
            "svg", "g", "a", "symbol", "clipPath", "mask", "marker", "pattern" -> {
                val group = Group()
                if (element.name in setOf("svg", "symbol") && !isRoot) {
                    val inner = Group()
                    val nestedBox = element["viewBox"]?.let(SvgBox::parse)
                    val nested = SvgViewport(nestedBox?.width ?: viewport.width, nestedBox?.height ?: viewport.height, nestedBox?.x ?: 0.0, nestedBox?.y ?: 0.0)
                    val clip = Rectangle()
                    group.children += inner
                    group.clip = clip
                    updateGeometry {
                        val x = length("x", viewport.width)
                        val y = length("y", viewport.height)
                        val width = positive(length("width", viewport.width, "100%"))
                        val height = positive(length("height", viewport.height, "100%"))
                        nested.width = nestedBox?.width ?: width
                        nested.height = nestedBox?.height ?: height
                        inner.transforms.setAll(javafx.scene.transform.Translate(x, y))
                        nestedBox?.let { inner.transforms += it.transform(width, height, element["preserveAspectRatio"]) }
                        clip.x = x; clip.y = y; clip.width = width; clip.height = height
                    }
                    element.children.mapNotNullTo(inner.children) { render(it, ancestors + element, style, nested) }
                } else {
                    element.children.mapNotNullTo(group.children) { render(it, ancestors + element, style, viewport) }
                }
                group
            }
            "path" -> SVGPath().apply {
                content = document.geometry.path(element["d"] ?: "")
                fillRule = fillRule(style.getValue(if (clipping) "clip-rule" else "fill-rule"))
            }
            "rect" -> Rectangle().also { shape ->
                updateGeometry {
                    setGeometry(shape.xProperty(), length("x", viewport.width)); setGeometry(shape.yProperty(), length("y", viewport.height))
                    setGeometry(shape.widthProperty(), positive(length("width", viewport.width)))
                    setGeometry(shape.heightProperty(), positive(length("height", viewport.height)))
                    val rx = positive(if (geometry.specified("rx")) length("rx", viewport.width)
                        else if (geometry.specified("ry")) length("ry", viewport.height) else 0.0)
                    val ry = positive(if (geometry.specified("ry")) length("ry", viewport.height) else rx)
                    setGeometry(shape.arcWidthProperty(), 2 * min(rx, shape.width / 2))
                    setGeometry(shape.arcHeightProperty(), 2 * min(ry, shape.height / 2))
                }
            }
            "circle" -> Circle().also { shape -> updateGeometry {
                setGeometry(shape.centerXProperty(), length("cx", viewport.width)); setGeometry(shape.centerYProperty(), length("cy", viewport.height))
                setGeometry(shape.radiusProperty(), positive(length("r", viewport.diagonal)))
            } }
            "ellipse" -> Ellipse().also { shape -> updateGeometry {
                setGeometry(shape.centerXProperty(), length("cx", viewport.width)); setGeometry(shape.centerYProperty(), length("cy", viewport.height))
                setGeometry(shape.radiusXProperty(), positive(length("rx", viewport.width))); setGeometry(shape.radiusYProperty(), positive(length("ry", viewport.height)))
            } }
            "line" -> Line().also { shape -> updateGeometry {
                setGeometry(shape.startXProperty(), length("x1", viewport.width)); setGeometry(shape.startYProperty(), length("y1", viewport.height))
                setGeometry(shape.endXProperty(), length("x2", viewport.width)); setGeometry(shape.endYProperty(), length("y2", viewport.height))
            } }
            "polyline", "polygon" -> SVGPath().apply {
                content = document.geometry.points(element["points"] ?: "", element.name == "polygon")
                fillRule = fillRule(style.getValue(if (clipping) "clip-rule" else "fill-rule"))
            }
            else -> {
                report("Unsupported SVG element: ${element.name}", element)
                return null
            }
        }
        val styleNode = (node as? SvgAdjustedText)?.styleText ?: node
        if (!clipping) {
            styleNode.id = element.id
            styleNode.styleClass += "svg-${element.name}"
            element["class"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { styleNode.styleClass.addAll(it) }
        }
        val sourceTransform = SvgTransforms.parse(element["transform"])
        node.transforms += sourceTransform
        animations.transform(element, sourceTransform, computed, if (isRoot) rootCssViewport else viewport, node, isRoot)
        if (clipping) styleNode.opacity = 1.0
        else if (style is SvgAnimatedStyle && "opacity" in style.affected) update(element) {
            setSvgBaseStyle(styleNode.opacityProperty(), svgStyleNumber(style, "opacity", 1.0, document.lengths).coerceIn(0.0, 1.0))
        } else styleNode.opacity = SvgNumbers.fraction(style.getValue("opacity"))
        if (node !is Group || element.name in setOf("image", "text", "textPath") && node !is SvgTextLayout) node.isVisible = style.getValue("visibility") == "visible"
        if (styleNode is Shape) {
            if (clipping) {
                styleNode.fill = Color.BLACK
                styleNode.stroke = null
            } else {
                styleNode.styleClass += "svg-shape"
                applyShapeStyle(styleNode, style, viewport, element, node, dynamicGeometry = geometry.isAnimated)
            }
        }
        val painted = if (!clipping && style.getValue("vector-effect") == "non-scaling-stroke") when (node) {
            is Shape -> nonScalingStroke(node, element)
            is SvgAdjustedText -> node.apply { wrapRuns { nonScalingStroke(it, element) } }
            else -> node.also {
                if (element.name == "use") report("Place non-scaling-stroke on the referenced shapes, not on use", element)
            }
        } else node
        val marked = if (!clipping && styleNode is Shape && element.name in setOf("path", "line", "polyline", "polygon", "rect", "circle", "ellipse"))
            applyMarkers(painted, styleNode, style, viewport, element, geometry.isAnimated) else painted
        val sized = if (geometry.isAnimated && node is Shape && element.name in setOf("rect", "circle", "ellipse")) {
            require(++renderedElements <= document.options.maxElements) { "SVG expanded geometry node limit exceeded" }
            Group(marked).also { layer ->
                layer.styleClass += "svg-geometry-layer"
                layer.transforms.setAll(marked.transforms)
                marked.transforms.clear()
                update(element) {
                    layer.isVisible = when (node) {
                        is Rectangle -> node.width > 0 && node.height > 0
                        is Circle -> node.radius > 0
                        is Ellipse -> node.radiusX > 0 && node.radiusY > 0
                        else -> true
                    }
                }
            }
        } else marked
        val result = if (!clipping && style.getValue("filter") != "none") applyFilter(sized, style.getValue("filter"), viewport, element) else sized
        if (style.getValue("clip-path") != "none") applyClip(result, style.getValue("clip-path"), viewport, element)
        return if (!clipping && style.getValue("mask") != "none") applyMask(result, style.getValue("mask"), viewport, element) else result
    }

    private fun applyMarkers(source: Node, shape: Shape, style: Map<String, String>, viewport: SvgViewport, owner: SvgElement, animatedGeometry: Boolean = false): Node {
        val references = linkedMapOf<String, SvgMarkerSpec>()
        for (kind in listOf("start", "mid", "end")) {
            val value = style.getValue("marker-$kind")
            if (value == "none") continue
            try {
                val id = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)").matchEntire(value)?.groupValues?.get(1)
                    ?: throw SvgException("Only local marker references are supported")
                require("marker:$id" !in activeReferences) { "Circular marker reference: #$id" }
                val target = document.elementsById[id] ?: throw SvgException("Missing marker: #$id")
                require(target.name == "marker") { "Marker reference is not a marker: #$id" }
                require(target["href"] == null && target["xlink:href"] == null) { "Marker templates are unsupported" }
                val spec = SvgMarkerSpec(target, document.lengths, paints.styleOf(target))
                spec.frame(viewport, SvgViewport(0.0, 0.0))
                references[kind] = spec
            } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid marker reference", owner) }
        }
        if (references.isEmpty()) return source
        val updateCheckpoint = updates.size
        val geometryCheckpoint = geometryUpdates.size
        val paintCheckpoint = paintUpdates.size
        val styleCheckpoint = shapeStyles.size
        val maskCheckpoint = masks.size
        val patternCheckpoint = patterns.size
        val animationCheckpoint = animations.size
        val layer: SvgMarkers
        try {
            val nodes = if (source === shape) 2 else 1
            require(document.options.maxElements - renderedElements >= nodes) { "SVG expanded marker node limit exceeded" }
            renderedElements += nodes
            layer = SvgMarkers(shape, viewport, style.getValue("vector-effect") == "non-scaling-stroke", owner.name != "path",
                document.options.maxElements, if (animatedGeometry) when (shape) { is Rectangle -> 9; is Circle, is Ellipse -> 5; is Line -> 2; else -> null } else null,
                { if (themeReady) root else null },
                { if (themeReady) root.parent?.requestLayout() }, { report(it, owner) })
            if (layer.vertexCount == 0) return source
            // Placement and marker viewports update before their content and context paints.
            update(owner) { layer.refresh() }
            for ((kind, spec) in references) {
                val indices = when (kind) {
                    "start" -> listOf(0)
                    "end" -> listOf(layer.vertexCount - 1)
                    else -> (1 until layer.vertexCount - 1).toList()
                }
                for (index in indices) {
                    require(document.options.maxElements - renderedElements >= 3) { "SVG expanded marker node limit exceeded" }
                    renderedElements += 3
                    val instance = SvgMarkerInstance(spec, viewport)
                    update(owner, phase = geometryUpdates) { instance.frame(viewport) }
                    val context = SvgMarkerContext(shape, layer, ::report)
                    instance.context = context
                    layer.add(index, kind == "start", instance, end = kind == "end")
                    val id = "marker:" + checkNotNull(spec.element.id)
                    require(activeReferences.add(id)) { "Circular marker reference: $id" }
                    val previous = paintContext
                    try {
                        require(activeReferences.size < document.options.maxDepth) { "Marker reference depth limit exceeded" }
                        paintContext = context
                        render(spec.element, paints.ancestorsOf(spec.element), paints.parentStyleOf(spec.element),
                            instance.contentViewport, referenced = true)?.let { instance.mapped.children += it }
                    } finally {
                        paintContext = previous
                        activeReferences.remove(id)
                    }
                }
            }
        } catch (failure: IllegalArgumentException) {
            while (updates.size > updateCheckpoint) updates.removeAt(updates.lastIndex)
            while (geometryUpdates.size > geometryCheckpoint) geometryUpdates.removeAt(geometryUpdates.lastIndex)
            while (paintUpdates.size > paintCheckpoint) paintUpdates.removeAt(paintUpdates.lastIndex)
            animations.discardFrom(animationCheckpoint)
            shapeStyles.discardFrom(styleCheckpoint)
            while (masks.size > maskCheckpoint) masks.removeAt(masks.lastIndex).dispose()
            while (patterns.size > patternCheckpoint) patterns.removeAt(patterns.lastIndex).also { definitions.children.remove(it.hidden); it.dispose() }
            report(failure.message ?: "Invalid SVG marker", owner)
            return source
        }
        layer.attachSource(source)
        layer.refresh()
        return layer
    }

    private fun nonScalingStroke(shape: Shape, owner: SvgElement): SvgNonScalingStroke {
        require(document.options.maxElements - renderedElements >= 4) { "SVG expanded stroke node limit exceeded" }
        renderedElements += 4
        val visible = shape.isVisible
        val transforms = shape.transforms.toList()
        shape.transforms.clear()
        return SvgNonScalingStroke(shape, { if (themeReady) root else null },
            { if (themeReady) root.parent?.requestLayout() }, { report(it, owner) }).also {
            it.transforms.setAll(transforms)
            it.isVisible = visible
            update(owner) { it.refresh() }
        }
    }

    private fun renderTextSpans(
        element: SvgElement, ancestors: List<SvgElement>, style: Map<String, String>, viewport: SvgViewport, rootGeometry: SvgAnimatedGeometry
    ): SvgTextLayout {
        val layout = SvgTextLayout(document.lengths, viewport)
        // Register flow before paint/filter updates, so CSS and resize use the new text geometry.
        update(element, phase = geometryUpdates) { layout.position() }
        data class Piece(
            val value: String, val preserve: Boolean, val owner: SvgElement,
            val style: Map<String, String>, val scopes: List<SvgTextLayout.Scope>, var text: Text? = null
        )
        val pieces = mutableListOf<Piece>()
        val childPlans = linkedMapOf<Group, MutableList<() -> Node?>>()
        fun build(
            owner: SvgElement, lineage: List<SvgElement>, computed: Map<String, String>, group: Group,
            parents: List<SvgTextLayout.Scope>, preserveParent: Boolean
        ) {
            require(lineage.size < document.options.maxDepth) { "SVG text depth limit exceeded" }
            val children = mutableListOf<() -> Node?>()
            childPlans[group] = children
            for (attribute in listOf("rotate", "dominant-baseline", "writing-mode", "textLength", "lengthAdjust")) {
                if (owner[attribute] != null) report("Unsupported attribute with tspan: $attribute", owner)
            }
            if (owner.name == "tspan") {
                if (owner["transform"] != null || (computed as? SvgStyleInfo)?.cssTransform == true) report("Unsupported tspan attribute: transform", owner)
                for (property in listOf("filter", "mask", "clip-path")) {
                    if (computed.getValue(property) != "none") report("Unsupported tspan property: $property", owner)
                }
                if (!clipping) {
                    group.id = owner.id
                    group.styleClass += "svg-tspan"
                    owner["class"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { group.styleClass.addAll(it) }
                }
                if (!clipping && computed is SvgAnimatedStyle && "opacity" in computed.affected) update(owner) {
                    setSvgBaseStyle(group.opacityProperty(), svgStyleNumber(computed, "opacity", 1.0, document.lengths).coerceIn(0.0, 1.0))
                } else group.opacity = if (clipping) 1.0 else SvgNumbers.fraction(computed.getValue("opacity"))
            }
            val coordinates = linkedMapOf<String, String>()
            for (name in listOf("x", "y", "dx", "dy")) {
                owner[name]?.let { value ->
                    try {
                        document.lengths.resolve(value, 1.0)
                        coordinates[name] = value
                    } catch (failure: IllegalArgumentException) {
                        report("Unsupported or invalid text $name (single length required): $value", owner)
                    }
                }
            }
            val geometry = if (owner === element) rootGeometry else animations.geometry(owner, viewport)
            val scopes = parents + SvgTextLayout.Scope(coordinates, geometry)
            val preserve = when (owner["xml:space"]) {
                "preserve" -> true
                "default" -> false
                else -> preserveParent
            }
            for (part in owner.content) {
                when (part) {
                    is SvgContent.Characters -> {
                        val piece = Piece(part.value, preserve, owner, computed, scopes)
                        pieces += piece
                        children += { piece.text }
                    }
                    is SvgContent.Child -> {
                        val child = part.element
                        if (child.name == "tspan") {
                            require(++renderedElements <= document.options.maxElements) { "SVG expanded element limit exceeded" }
                            val childStyle = computedStyle(child, lineage + owner, computed, viewport)
                            if (childStyle.getValue("display") != "none") {
                                val childGroup = Group()
                                children += { childGroup }
                                build(child, lineage + owner, childStyle, childGroup, scopes, preserve)
                            }
                        } else if (!child.name.startsWith('{') && child.name !in setOf("title", "desc", "metadata") + SvgAnimation.NAMES) {
                            report("Unsupported text child: ${child.name}", child)
                        }
                    }
                }
            }
        }
        val preserve = ancestors.asReversed().firstNotNullOfOrNull { it["xml:space"] } == "preserve"
        build(element, ancestors, style, layout, emptyList(), preserve)
        val values = SvgTextLayout.normalize(pieces.map { it.value to it.preserve })
        val wholeText = values.joinToString("")
        if (java.text.Bidi.requiresBidi(wholeText.toCharArray(), 0, wholeText.length)) {
            report("Bidirectional layout across tspan fragments is unsupported", element)
        } else if (wholeText.codePoints().anyMatch { Character.UnicodeScript.of(it) !in SIMPLE_TEXT_SCRIPTS }) {
            report("Contextual shaping across tspan fragments is unsupported", element)
        }
        val runCount = values.count { it.isNotEmpty() }
        require(runCount <= document.options.maxElements - renderedElements) { "SVG expanded text node limit exceeded" }
        renderedElements += runCount
        pieces.forEachIndexed { index, piece ->
            if (values[index].isNotEmpty()) {
                val text = Text(values[index])
                piece.text = text
                configureTextFont(text, piece.style)
                text.isVisible = piece.style.getValue("visibility") == "visible"
                if (!clipping) text.styleClass.addAll("svg-text-run", "svg-shape")
                layout.runs += SvgTextLayout.Run(text, piece.scopes, textAnchor(piece.style))
                text.fontProperty().addListener { _, _, _ ->
                    layout.requestLayout()
                    if (themeReady) root.parent?.requestLayout()
                }
            }
        }
        childPlans.forEach { (group, children) -> group.children.setAll(children.mapNotNull { it() }) }
        layout.position()
        pieces.forEach { piece ->
            piece.text?.let { text ->
                if (clipping) {
                    text.fill = Color.BLACK
                    text.stroke = null
                } else {
                    applyShapeStyle(text, piece.style, viewport, piece.owner, layout, dynamicGeometry = true)
                    if (piece.style.getValue("vector-effect") == "non-scaling-stroke") {
                        val parent = text.parent as Group
                        val index = parent.children.indexOf(text)
                        parent.children.removeAt(index)
                        parent.children.add(index, nonScalingStroke(text, piece.owner))
                    }
                }
            }
        }
        return layout
    }

    private fun configureTextFont(text: Text, style: Map<String, String>) {
        val weight = when (val value = style.getValue("font-weight")) {
            "normal" -> FontWeight.NORMAL
            "bold" -> FontWeight.BOLD
            else -> FontWeight.findByWeight(value.toIntOrNull() ?: throw SvgException("Unsupported font-weight: $value"))
        }
        val posture = when (style.getValue("font-style")) {
            "normal" -> FontPosture.REGULAR
            "italic", "oblique" -> FontPosture.ITALIC
            else -> throw SvgException("Unsupported font-style")
        }
        val family = style.getValue("font-family").substringBefore(',').trim().trim('\'', '"')
        text.font = Font.font(family, weight, posture, SvgNumbers.scalar(style.getValue("font-size")))
    }

    private fun textAnchor(style: Map<String, String>): Double = when (style.getValue("text-anchor")) {
        "start" -> 0.0; "middle" -> .5; "end" -> 1.0
        else -> throw SvgException("Invalid text-anchor")
    }

    private companion object {
        val SIMPLE_TEXT_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK,
            Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED)
    }

    private fun applyMask(node: Node, value: String, viewport: SvgViewport, owner: SvgElement): Node {
        val updateCheckpoint = updates.size
        val geometryCheckpoint = geometryUpdates.size
        val paintCheckpoint = paintUpdates.size
        val maskCheckpoint = masks.size
        val patternCheckpoint = patterns.size
        val animationCheckpoint = animations.size
        try {
            val id = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)").matchEntire(value)?.groupValues?.get(1)
                ?: throw SvgException("Only local mask references are supported")
            val target = document.elementsById[id] ?: throw SvgException("Missing mask: #$id")
            require(target.name == "mask") { "Mask reference is not a mask: #$id" }
            require(target["href"] == null && target["xlink:href"] == null) { "Mask templates are unsupported" }
            val maskStyle = paints.styleOf(target)
            val mode = maskStyle.getValue("mask-type")
            require(mode in setOf("alpha", "luminance")) { "Invalid mask-type: $mode" }
            val interpolation = maskStyle.getValue("color-interpolation")
            require(interpolation in setOf("sRGB", "auto", "linearRGB")) { "Invalid mask color-interpolation: $interpolation" }
            require(activeReferences.add("mask:$id")) { "Circular mask reference: #$id" }
            val previousMasking = masking
            try {
                require(activeReferences.size <= document.options.maxDepth) { "Mask reference depth limit exceeded" }
                masking = true
                val maskViewport = if (target["maskContentUnits"] == "objectBoundingBox") SvgViewport(1.0, 1.0) else viewport
                val content = render(target, paints.ancestorsOf(target), paints.parentStyleOf(target), maskViewport, referenced = true) ?: Group()
                return SvgMask(node, content, target, viewport, document.lengths, maskBudget, mode == "luminance", interpolation == "linearRGB",
                    { if (themeReady) root.parent?.requestLayout() }) { report(it, owner) }.also {
                    masks += it
                    updates += { it.refresh() }
                }
            } finally {
                masking = previousMasking
                activeReferences.remove("mask:$id")
            }
        } catch (failure: IllegalArgumentException) {
            while (updates.size > updateCheckpoint) updates.removeAt(updates.lastIndex)
            while (geometryUpdates.size > geometryCheckpoint) geometryUpdates.removeAt(geometryUpdates.lastIndex)
            while (paintUpdates.size > paintCheckpoint) paintUpdates.removeAt(paintUpdates.lastIndex)
            animations.discardFrom(animationCheckpoint)
            while (masks.size > maskCheckpoint) masks.removeAt(masks.lastIndex).dispose()
            while (patterns.size > patternCheckpoint) patterns.removeAt(patterns.lastIndex).also { definitions.children.remove(it.hidden); it.dispose() }
            report(failure.message ?: "Invalid mask", owner)
            // Invalid or unavailable masks must not expose the unmasked source.
            node.clip = Rectangle()
            return node
        }
    }

    private fun applyFilter(node: Node, value: String, viewport: SvgViewport, owner: SvgElement): Node {
        return try {
            val id = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)").matchEntire(value)?.groupValues?.get(1)
                ?: throw SvgException("Only local filter references are supported")
            val filter = document.elementsById[id] ?: throw SvgException("Missing filter: #$id")
            require(filter.name == "filter") { "Filter reference is not a filter: #$id" }
            require(filter["href"] == null && filter["xlink:href"] == null && filter["filterRes"] == null) {
                "Filter templates and filterRes are unsupported"
            }
            val primitives = filter.children.filter { !it.name.startsWith('{') && it.name !in setOf("title", "desc", "metadata") }
            require(primitives.size == 1 && primitives.single().name in setOf("feGaussianBlur", "feDropShadow", "feOffset")) {
                "Only a single feGaussianBlur, feDropShadow or feOffset primitive is supported"
            }
            val primitive = primitives.single()
            require(primitive.children.all { it.name.startsWith('{') || it.name in setOf("title", "desc", "metadata") }) {
                "Animated filter primitives are unsupported"
            }
            if (primitive.name == "feOffset") {
                return SvgOffsetFilter(node, filter, primitive, viewport, document.lengths) { report(it, owner) }
                    .also { shifted -> update(owner) { shifted.refresh() } }
            }
            val primitiveStyle = paints.styleOf(primitive)
            val flood = Color.web(primitiveStyle.getValue("flood-color").let { if (it == "currentColor") primitiveStyle.getValue("color") else it })
            val floodColor = flood.deriveColor(0.0, 1.0, 1.0, SvgNumbers.fraction(primitiveStyle.getValue("flood-opacity")))
            SvgGaussianFilter(node, filter, primitive, viewport, document.lengths, primitiveStyle.getValue("color-interpolation-filters"), floodColor) {
                report(it, owner)
            }.also { filtered -> update(owner) { filtered.refresh() } }
        } catch (e: IllegalArgumentException) {
            report(e.message ?: "Invalid filter", owner)
            node
        }
    }

    private fun applyClip(node: Node, value: String, viewport: SvgViewport, owner: SvgElement) {
        val id = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)").matchEntire(value)?.groupValues?.get(1)
            ?: throw SvgException("Only local clip-path references are supported")
        val target = document.elementsById[id] ?: throw SvgException("Missing clipPath: #$id")
        require(target.name == "clipPath") { "clip-path target is not a clipPath" }
        require(activeReferences.add("clip:$id")) { "Circular clipPath reference: #$id" }
        val previousClipping = clipping
        try {
            clipping = true
            val proportional = when (target["clipPathUnits"] ?: "userSpaceOnUse") {
                "userSpaceOnUse" -> false
                "objectBoundingBox" -> true
                else -> throw SvgException("Invalid clipPathUnits")
            }
            val clipViewport = if (proportional) SvgViewport(1.0, 1.0) else viewport
            val parents = paints.ancestorsOf(target)
            val clipNode = render(target, parents, paints.parentStyleOf(target), clipViewport, referenced = true) ?: Group()
            val wrapper = Group(clipNode)
            if (proportional) {
                val transform = javafx.scene.transform.Affine()
                wrapper.transforms.add(transform)
                update(owner) {
                    val bounds = svgGeometryBounds(node)
                    transform.setToTransform(bounds.width, 0.0, bounds.minX, 0.0, bounds.height, bounds.minY)
                }
            }
            wrapper.clip = node.clip
            node.clip = wrapper
        } finally {
            clipping = previousClipping
            activeReferences.remove("clip:$id")
        }
    }

    private fun update(element: SvgElement, viewport: SvgViewport? = null, phase: MutableList<() -> Unit> = updates, action: () -> Unit) {
        action()
        var previousWidth = viewport?.width ?: Double.NaN
        var previousHeight = viewport?.height ?: Double.NaN
        phase += {
            if (viewport == null || previousWidth != viewport.width || previousHeight != viewport.height) {
                try {
                    action()
                    previousWidth = viewport?.width ?: Double.NaN
                    previousHeight = viewport?.height ?: Double.NaN
                } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid geometry", element) }
            }
        }
    }

    private fun pattern(value: String, owner: SvgElement, target: Node, viewport: SvgViewport): SvgPattern? {
        if (!value.startsWith("url(")) return null
        val id = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)\\s*.*").matchEntire(value)?.groupValues?.get(1) ?: return null
        if (document.elementsById[id]?.name != "pattern") return null
        require(activeReferences.add("pattern:$id")) { "Circular pattern reference: #$id" }
        val oldMasking = masking
        val oldContext = paintContext
        try {
            val chain = mutableListOf<SvgElement>()
            var next: String? = id
            val seen = mutableSetOf<String>()
            while (next != null) {
                require(seen.add(next) && seen.size <= document.options.maxDepth) { "Circular or too deep pattern template" }
                val item = document.elementsById[next] ?: throw SvgException("Missing pattern: #$next")
                require(item.name == "pattern") { "Pattern template must reference a pattern" }
                chain += item
                val href = item["href"] ?: item["xlink:href"]
                require(href == null || href.startsWith('#') && href.length > 1) { "Only local pattern templates are supported" }
                next = href?.drop(1)
            }
            val attrs = chain.asReversed().flatMap { it.attributes.entries }.associate { it.key to it.value }
            val content = chain.firstOrNull { item -> item.children.any { !it.name.startsWith('{') && it.name !in setOf("title","desc","metadata") } } ?: chain.first()
            val merged = SvgElement("pattern",attrs,content.children,"",content.content)
            require(document.options.maxElements-renderedElements >= 3) { "SVG expanded pattern node limit exceeded" }
            renderedElements += 3
            val instance = SvgPattern(target,merged,viewport,document.lengths,patternBudget) { if (themeReady) root.parent?.requestLayout() }
            patterns += instance
            definitions.children += instance.hidden
            update(owner,phase=geometryUpdates) { instance.prepare() }
            masking = true
            paintContext = null
            render(merged,paints.ancestorsOf(chain.first()),paints.parentStyleOf(chain.first()),instance.contentViewport,referenced=true)
                ?.let { instance.content.children += it }
            instance.observe()
            return instance
        } finally {
            masking=oldMasking; paintContext=oldContext
            activeReferences.remove("pattern:$id")
        }
    }

    private fun applyShapeStyle(
        shape: Shape, style: Map<String, String>, viewport: SvgViewport, element: SvgElement,
        geometry: Node = shape, dynamicGeometry: Boolean = false
    ) {
        val context = paintContext
        val fillPattern = pattern(style.getValue("fill"),element,geometry,viewport)
        val strokePattern = pattern(style.getValue("stroke"),element,geometry,viewport)
        val entry = if (masking || context != null) null else shapeStyles.register(shape)
        val action = {
            val fill = paint(style.getValue("fill"), style, svgStyleNumber(style, "fill-opacity", 1.0, document.lengths).coerceIn(0.0, 1.0), element, geometry, viewport, context, svgStyleColour(style, "fill"), fillPattern)
            val stroke = paint(style.getValue("stroke"), style, svgStyleNumber(style, "stroke-opacity", 1.0, document.lengths).coerceIn(0.0, 1.0), element, geometry, viewport, context, svgStyleColour(style, "stroke"), strokePattern)
            val width = svgStyleNumber(style, "stroke-width", viewport.diagonal, document.lengths).also { require(it >= 0) }
            if (entry != null) entry.setBase(fill, stroke, width) else {
                setSvgBaseStyle(shape.fillProperty(), fill)
                setSvgBaseStyle(shape.strokeProperty(), stroke)
                setSvgBaseStyle(shape.strokeWidthProperty(), width)
            }
            setSvgBaseStyle(shape.strokeDashOffsetProperty(), svgStyleNumber(style, "stroke-dashoffset", viewport.diagonal, document.lengths))
        }
        if (context != null && (style.getValue("fill").startsWith("context-") || style.getValue("stroke").startsWith("context-"))) {
            action()
            context.updates += { try { action() } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid marker paint", element) } }
        } else if (fillPattern != null || strokePattern != null) update(element) { action() }
        else update(element, if (dynamicGeometry || style is SvgAnimatedStyle && style.affected.isNotEmpty()) null else viewport, phase = paintUpdates) { action() }
        shape.strokeType = StrokeType.CENTERED
        shape.strokeLineCap = when (style.getValue("stroke-linecap")) {
            "butt" -> StrokeLineCap.BUTT; "round" -> StrokeLineCap.ROUND; "square" -> StrokeLineCap.SQUARE
            else -> throw SvgException("Invalid stroke-linecap")
        }
        shape.strokeLineJoin = when (style.getValue("stroke-linejoin")) {
            "miter" -> StrokeLineJoin.MITER; "round" -> StrokeLineJoin.ROUND; "bevel" -> StrokeLineJoin.BEVEL
            else -> throw SvgException("Invalid stroke-linejoin")
        }
        shape.strokeMiterLimit = SvgNumbers.scalar(style.getValue("stroke-miterlimit")).also { require(it >= 1) }
        val dash = style.getValue("stroke-dasharray")
        if (dash != "none") {
            val values = dash.trim().split(Regex("[,\\s]+")).map {
                document.lengths.resolve(it, viewport.diagonal).also { n -> require(n >= 0) }
            }
            if (values.any { it > 0 }) shape.strokeDashArray.setAll(if (values.size % 2 == 0) values else values + values)
        }
    }

    private fun paint(
        value: String, style: Map<String, String>, opacity: Double, element: SvgElement, shape: Node,
        viewport: SvgViewport, context: SvgMarkerContext? = null, animatedColour: Color? = null, pattern: SvgPattern? = null
    ): Paint? {
        if (animatedColour != null) return Color(animatedColour.red, animatedColour.green, animatedColour.blue, animatedColour.opacity * opacity)
        if (value == "none") return null
        if (value in setOf("context-fill", "context-stroke")) return context?.paint(value, opacity, shape, element)
        if (value.startsWith("url(")) {
            val match = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)\\s*(.*)").matchEntire(value)
                ?: throw SvgException("Only local paint references are supported: $value")
            return try {
                if (pattern != null) { if (themeReady) pattern.paint(opacity) else Color.TRANSPARENT }
                else paints.gradient(match.groupValues[1], opacity, viewport, svgGeometryBounds(shape))
            } catch (e: IllegalArgumentException) {
                report(e.message ?: "Invalid paint server", element)
                val fallback = match.groupValues[2].ifBlank { "none" }
                require(!fallback.startsWith("url(")) { "Invalid paint fallback" }
                paint(fallback, style, opacity, element, shape, viewport, context)
            }
        }
        val color = if (value == "currentColor") svgStyleColour(style, "color") ?: Color.web(style.getValue("color")) else Color.web(value)
        return Color(color.red, color.green, color.blue, color.opacity * opacity)
    }

    private fun fillRule(value: String): FillRule = when (value) {
        "nonzero" -> FillRule.NON_ZERO
        "evenodd" -> FillRule.EVEN_ODD
        else -> throw SvgException("Invalid fill rule: $value")
    }
}
