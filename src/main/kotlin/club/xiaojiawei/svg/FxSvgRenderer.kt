package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.*
import javafx.beans.property.Property
import javafx.css.StyleableProperty
import javafx.css.StyleOrigin
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

internal data class SvgViewport(var width: Double, var height: Double) {
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
    private val updates = mutableListOf<() -> Unit>()
    private val activeReferences = linkedSetOf<String>()
    private var clipping = false
    private var renderedElements = 0
    private val box = document.root["viewBox"]?.let(SvgBox::parse)
    val intrinsicWidth = document.root["width"]?.let { SvgNumbers.length(it, box?.width ?: 300.0) } ?: box?.width ?: 300.0
    val intrinsicHeight = document.root["height"]?.let { SvgNumbers.length(it, box?.height ?: 150.0) } ?: box?.height ?: 150.0
    private val viewport = SvgViewport(box?.width ?: intrinsicWidth, box?.height ?: intrinsicHeight)
    private val content: Node
    val root: Group
    private val viewportClip = Rectangle()

    init {
        require(intrinsicWidth >= 0 && intrinsicHeight >= 0) { "Negative SVG dimensions" }
        content = render(document.root, emptyList(), emptyMap(), viewport, true) ?: Group()
        root = Group(content).apply { clip = viewportClip; isManaged = false }
    }

    fun layout(width: Double, height: Double, preserveRatio: Boolean) {
        viewport.width = box?.width ?: width
        viewport.height = box?.height ?: height
        root.isVisible = width > 0 && height > 0 && viewport.width > 0 && viewport.height > 0
        viewportClip.width = width
        viewportClip.height = height
        root.transforms.clear()
        content.transforms.removeIf { it === viewportTransform }
        viewportTransform = box?.transform(width, height, if (preserveRatio) document.root["preserveAspectRatio"] else "none")
        viewportTransform?.let { content.transforms.add(it) }
        updates.forEach { it() }
    }
    private var viewportTransform: javafx.scene.transform.Affine? = null

    private fun render(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean = false, referenced: Boolean = false
    ): Node? {
        val updateCheckpoint = updates.size
        return try {
            require(ancestors.size < document.options.maxDepth) { "SVG reference depth limit exceeded" }
            require(++renderedElements <= document.options.maxElements) { "SVG expanded element limit exceeded" }
            renderElement(element, ancestors, parentStyle, viewport, isRoot, referenced)
        } catch (e: IllegalArgumentException) {
            while (updates.size > updateCheckpoint) updates.removeAt(updates.lastIndex)
            report(e.message ?: "Invalid SVG element", element)
            null
        }
    }

    private fun renderElement(
        element: SvgElement, ancestors: List<SvgElement>, parentStyle: Map<String, String>,
        viewport: SvgViewport, isRoot: Boolean, referenced: Boolean
    ): Node? {
        if (element.name in setOf("defs", "style", "title", "desc", "metadata", "linearGradient", "radialGradient", "stop", "filter")) return null
        if (element.name == "symbol" && !referenced) return null
        if (element.name == "clipPath" && !referenced) return null
        val style = styles.compute(element, ancestors, parentStyle)
        if (style.getValue("display") == "none") return null
        if (element.name in setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "image", "use")) {
            element.children.filter { it.name !in setOf("title", "desc", "metadata") }.forEach {
                report("Unsupported child ${it.name} inside ${element.name}", it)
            }
        }
        for (attribute in listOf("mask", "marker-start", "marker-mid", "marker-end", "vector-effect", "paint-order")) {
            if (element[attribute] != null) report("Unsupported SVG attribute: $attribute", element)
        }
        fun length(name: String, axis: Double, default: String = "0") = SvgNumbers.length(element[name] ?: default, axis)
        fun positive(value: Double): Double = value.also { require(it >= 0) { "Negative SVG size" } }
        val node: Node = when (element.name) {
            "text" -> {
                val text = Text()
                element.children.forEach { report("Unsupported text child: ${it.name}", it) }
                listOf("rotate", "dominant-baseline", "writing-mode").forEach {
                    if (element[it] != null) report("Unsupported text attribute: $it", element)
                }
                val preserve = (element["xml:space"] ?: ancestors.asReversed().firstNotNullOfOrNull { it["xml:space"] }) == "preserve"
                text.text = if (preserve) element.text else element.text.replace(Regex("\\s+"), " ").trim()
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
                val anchor = when (style.getValue("text-anchor")) {
                    "start" -> 0.0; "middle" -> .5; "end" -> 1.0
                    else -> throw SvgException("Invalid text-anchor")
                }
                val lengthAdjust = element["lengthAdjust"] ?: "spacing"
                require(lengthAdjust in setOf("spacing", "spacingAndGlyphs")) { "Invalid lengthAdjust" }
                val adjusted = if (element["textLength"] != null) {
                    try {
                        SvgAdjustedText(text, lengthAdjust == "spacingAndGlyphs", anchor,
                            document.options.maxElements - renderedElements).also { renderedElements += it.glyphCount }
                    } catch (e: IllegalArgumentException) {
                        report(e.message ?: "Unsupported textLength layout", element)
                        null
                    }
                } else null
                update(element) {
                    val x = length("x", viewport.width) + length("dx", viewport.width)
                    val y = length("y", viewport.height) + length("dy", viewport.height)
                    if (adjusted != null) adjusted.position(x, y, length("textLength", viewport.width))
                    else {
                        text.x = x - anchor * text.layoutBounds.width
                        text.y = y
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
                update(element) {
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
                require(activeReferences.add(href)) { "Circular use reference: $href" }
                try {
                    val instance = if (target.name in setOf("svg", "symbol")) {
                        val attributes = target.attributes.toMutableMap()
                        listOf("width", "height").forEach { key -> element[key]?.let { attributes[key] = it } }
                        SvgElement(target.name, attributes, target.children, target.text)
                    } else target
                    val group = Group()
                    update(element) {
                        group.layoutX = length("x", viewport.width)
                        group.layoutY = length("y", viewport.height)
                    }
                    render(instance, ancestors + element, style, viewport, referenced = true)?.let { group.children += it }
                    group
                } finally { activeReferences.remove(href) }
            }
            "svg", "g", "a", "symbol", "clipPath" -> {
                val group = Group()
                if (element.name in setOf("svg", "symbol") && !isRoot) {
                    val inner = Group()
                    val nestedBox = element["viewBox"]?.let(SvgBox::parse)
                    val nested = SvgViewport(nestedBox?.width ?: viewport.width, nestedBox?.height ?: viewport.height)
                    val clip = Rectangle()
                    group.children += inner
                    group.clip = clip
                    update(element) {
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
                update(element) {
                    shape.x = length("x", viewport.width); shape.y = length("y", viewport.height)
                    shape.width = positive(length("width", viewport.width)); shape.height = positive(length("height", viewport.height))
                    val rx = positive(SvgNumbers.length(element["rx"] ?: element["ry"] ?: "0", viewport.width))
                    val ry = positive(SvgNumbers.length(element["ry"] ?: element["rx"] ?: "0", viewport.height))
                    shape.arcWidth = 2 * min(rx, shape.width / 2)
                    shape.arcHeight = 2 * min(ry, shape.height / 2)
                }
            }
            "circle" -> Circle().also { shape -> update(element) {
                shape.centerX = length("cx", viewport.width); shape.centerY = length("cy", viewport.height)
                shape.radius = positive(length("r", viewport.diagonal))
            } }
            "ellipse" -> Ellipse().also { shape -> update(element) {
                shape.centerX = length("cx", viewport.width); shape.centerY = length("cy", viewport.height)
                shape.radiusX = positive(length("rx", viewport.width)); shape.radiusY = positive(length("ry", viewport.height))
            } }
            "line" -> Line().also { shape -> update(element) {
                shape.startX = length("x1", viewport.width); shape.startY = length("y1", viewport.height)
                shape.endX = length("x2", viewport.width); shape.endY = length("y2", viewport.height)
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
        node.transforms += SvgTransforms.parse(element["transform"])
        styleNode.opacity = if (clipping) 1.0 else SvgNumbers.fraction(style.getValue("opacity"))
        if (node !is Group || element.name in setOf("image", "text")) node.isVisible = style.getValue("visibility") == "visible"
        if (styleNode is Shape) {
            if (clipping) {
                styleNode.fill = Color.BLACK
                styleNode.stroke = null
            } else {
                styleNode.styleClass += "svg-shape"
                applyShapeStyle(styleNode, style, viewport, element, node)
            }
        }
        val result = if (!clipping && style.getValue("filter") != "none") applyFilter(node, style.getValue("filter"), viewport, element) else node
        if (style.getValue("clip-path") != "none") applyClip(result, style.getValue("clip-path"), viewport, element)
        return result
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
            val primitives = filter.children.filter { it.name !in setOf("title", "desc", "metadata") }
            require(primitives.size == 1 && primitives.single().name == "feGaussianBlur") {
                "Only a single feGaussianBlur primitive is supported"
            }
            val primitive = primitives.single()
            require(primitive.children.all { it.name in setOf("title", "desc", "metadata") }) {
                "Animated Gaussian filters are unsupported"
            }
            SvgGaussianFilter(node, filter, primitive, viewport, paints.styleOf(primitive).getValue("color-interpolation-filters")) {
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
            if (proportional) update(owner) {
                val bounds = svgGeometryBounds(node)
                wrapper.transforms.setAll(javafx.scene.transform.Affine(bounds.width, 0.0, bounds.minX, 0.0, bounds.height, bounds.minY))
            }
            wrapper.clip = node.clip
            node.clip = wrapper
        } finally {
            clipping = previousClipping
            activeReferences.remove("clip:$id")
        }
    }

    private fun update(element: SvgElement, action: () -> Unit) {
        action()
        updates += {
            try { action() } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid geometry", element) }
        }
    }

    private fun applyShapeStyle(shape: Shape, style: Map<String, String>, viewport: SvgViewport, element: SvgElement, geometry: Node = shape) {
        var previousWidth = Double.NaN
        var previousHeight = Double.NaN
        update(element) {
            if (previousWidth != viewport.width || previousHeight != viewport.height) {
                setBase(shape.fillProperty(), paint(style.getValue("fill"), style, SvgNumbers.fraction(style.getValue("fill-opacity")), element, geometry, viewport))
                setBase(shape.strokeProperty(), paint(style.getValue("stroke"), style, SvgNumbers.fraction(style.getValue("stroke-opacity")), element, geometry, viewport))
                setBase(shape.strokeWidthProperty(), SvgNumbers.length(style.getValue("stroke-width"), viewport.diagonal).also { require(it >= 0) })
                setBase(shape.strokeDashOffsetProperty(), SvgNumbers.length(style.getValue("stroke-dashoffset"), viewport.diagonal))
                previousWidth = viewport.width; previousHeight = viewport.height
            }
        }
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
                SvgNumbers.length(it, viewport.diagonal).also { n -> require(n >= 0) }
            }
            if (values.any { it > 0 }) shape.strokeDashArray.setAll(if (values.size % 2 == 0) values else values + values)
        }
    }

    private fun <T> setBase(property: Property<T>, value: T) {
        val origin = (property as? StyleableProperty<*>)?.styleOrigin
        if (!property.isBound && origin != StyleOrigin.AUTHOR && origin != StyleOrigin.INLINE) property.value = value
    }

    private fun paint(value: String, style: Map<String, String>, opacity: Double, element: SvgElement, shape: Node, viewport: SvgViewport): Paint? {
        if (value == "none") return null
        if (value.startsWith("url(")) {
            val match = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)\\s*(.*)").matchEntire(value)
                ?: throw SvgException("Only local paint references are supported: $value")
            return try {
                paints.gradient(match.groupValues[1], opacity, viewport, svgGeometryBounds(shape))
            } catch (e: IllegalArgumentException) {
                report(e.message ?: "Invalid paint server", element)
                val fallback = match.groupValues[2].ifBlank { "none" }
                require(!fallback.startsWith("url(")) { "Invalid paint fallback" }
                paint(fallback, style, opacity, element, shape, viewport)
            }
        }
        val color = Color.web(if (value == "currentColor") style.getValue("color") else value)
        return Color(color.red, color.green, color.blue, color.opacity * opacity)
    }

    private fun fillRule(value: String): FillRule = when (value) {
        "nonzero" -> FillRule.NON_ZERO
        "evenodd" -> FillRule.EVEN_ODD
        else -> throw SvgException("Invalid fill rule: $value")
    }
}
