package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.value.ObservableValue
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.geometry.Rectangle2D
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.scene.transform.Scale
import javafx.css.StyleableProperty
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Conservative per-view budget, counting the source, retained results and temporary pixel buffers. */
internal class SvgFilterBudget(private val limit: Int) {
    private val owners = java.util.IdentityHashMap<SvgFilterChain, Long>()
    private var used = 0L
    private val work = java.util.IdentityHashMap<SvgFilterChain, Double>()
    private var usedWork = 0.0
    fun reserve(owner: SvgFilterChain, pixels: Long) {
        val next = used - (owners[owner] ?: 0) + pixels
        require(next <= limit) { "SVG filter rasters exceed maxFilterPixels ($limit)" }
        owners[owner] = pixels
        used = next
    }
    fun reserveWork(owner: SvgFilterChain, operations: Double) {
        val next = usedWork - (work[owner] ?: 0.0) + operations
        require(next <= 200_000_000.0) { "SVG filter rasters exceed the per-view 200000000 component-operation work limit" }
        work[owner] = operations
        usedWork = next
    }
    fun release(owner: SvgFilterChain) {
        used -= owners.remove(owner) ?: 0
        usedWork -= work.remove(owner) ?: 0.0
    }
}

/**
 * A bounded raster pipeline, not an approximation via JavaFX BlendMode. The original nodes stay
 * in the CSS tree. Snapshot input/output is sRGB; each primitive computes in its specified space.
 * Existing standalone native blur/offset effects keep their vector/native fast path.
 */
internal class SvgFilterChain(
    private val source: Node,
    private val opacityNode: Node,
    private val filter: SvgElement,
    private val primitives: List<SvgElement>,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache,
    private val budget: SvgFilterBudget,
    private val styleOf: (SvgElement) -> Map<String, String>,
    private val invalidateRenderer: () -> Unit,
    private val report: (String) -> Unit
) : Group() {
    private val filterProportional = svgFilterProportional(filter["filterUnits"] ?: "objectBoundingBox")
    private val primitiveProportional = svgFilterProportional(filter["primitiveUnits"] ?: "userSpaceOnUse")
    private val image = ImageView().apply { isManaged = false; isSmooth = true; isMouseTransparent = true }
    private val hidden = Group(source).apply {
        // Opacity hides drawing without removing source geometry from JavaFX hit testing.
        opacity = 0.0; isManaged = false
    }
    private var dirty = true
    private var refreshing = false
    private var enabled = false
    private var previous: List<Double>? = null
    internal var rasterizations: Int = 0
        private set
    private val changed = InvalidationListener { observable ->
        (observable as? ObservableValue<*>)?.value
        if (!refreshing) { dirty = true; requestLayout(); invalidateRenderer() }
    }
    private val transformChanged = InvalidationListener { observable ->
        (observable as? ObservableValue<*>)?.value
        // Translation/rigid rotation changes only placement. Scale changes are detected by the key.
        if (!refreshing) { requestLayout(); invalidateRenderer() }
    }
    private var observedWindow: javafx.stage.Window? = null
    private val windowChanged = javafx.beans.value.ChangeListener<javafx.stage.Window?> { _, _, next -> watchWindow(next) }

    init {
        // Validate the complete graph before reparenting or transferring the source transforms.
        primitives.forEach(::validate)
        transforms.setAll(source.transforms)
        source.transforms.clear()
        children.setAll(hidden, image)
        styleClass += "svg-filter-output"
        observe(source)
        localToSceneTransformProperty().addListener(transformChanged)
        sceneProperty().addListener { _, old, next ->
            old?.windowProperty()?.removeListener(windowChanged)
            next?.windowProperty()?.addListener(windowChanged)
            watchWindow(next?.window)
        }
    }

    private fun watchWindow(next: javafx.stage.Window?) {
        observedWindow?.outputScaleXProperty()?.removeListener(changed)
        observedWindow?.outputScaleYProperty()?.removeListener(changed)
        observedWindow = next
        next?.outputScaleXProperty()?.addListener(changed)
        next?.outputScaleYProperty()?.addListener(changed)
        dirty = true
        requestLayout()
    }

    fun geometryBounds(): Bounds = source.localToParentTransform.transform(svgGeometryBounds(source))
    fun dispose() { budget.release(this) }
    override fun layoutChildren() {
        if (enabled) rasterize()
        super.layoutChildren()
    }
    fun refresh() { enabled = true; rasterize() }

    private fun validate(primitive: SvgElement) {
        require(primitive.name in SUPPORTED) { "Unsupported filter primitive: ${primitive.name}" }
        val children = primitive.children.filter { !it.name.startsWith('{') && it.name !in DESCRIPTIVE }
        if (primitive.name == "feMerge") {
            require(children.all { it.name == "feMergeNode" }) { "feMerge only supports feMergeNode children" }
            children.forEach {
                require(it.children.all { child -> child.name.startsWith('{') || child.name in DESCRIPTIVE }) {
                    "Animated filter primitives are unsupported"
                }
                validateInput(it["in"])
            }
        } else require(children.isEmpty()) { "Animated filter primitives are unsupported" }
        if (primitive.name !in setOf("feFlood", "feMerge")) validateInput(primitive["in"])
        if (primitive.name == "feComposite") {
            validateInput(primitive["in2"])
            require((primitive["operator"] ?: "over") in OPERATORS) { "Invalid feComposite operator" }
            if (primitive["operator"] == "arithmetic") (1..4).forEach { SvgNumbers.scalar(primitive["k$it"] ?: "0") }
        }
        if (primitive.name in setOf("feGaussianBlur", "feDropShadow")) {
            require(primitive["edgeMode"] in setOf(null, "none")) { "Only Gaussian edgeMode=none is supported" }
            val values = SvgNumbers.list(primitive["stdDeviation"] ?: if (primitive.name == "feDropShadow") "2" else "0")
            require(values.size in 1..2) { "Gaussian filter needs one or two stdDeviation values" }
        }
        val interpolation = styleOf(primitive).getValue("color-interpolation-filters")
        require(interpolation in setOf("linearRGB", "sRGB", "auto")) { "Invalid color-interpolation-filters" }
    }

    private fun validateInput(value: String?) {
        require(value !in setOf("BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint")) {
            "Unsupported filter input: $value"
        }
    }

    private data class Result(val pixels: SvgFilterPixels, val region: Bounds, val standardInput: Boolean = false)

    private fun rasterize() {
        if (refreshing) return
        refreshing = true
        try {
            if (generateSequence(source) { it.parent }.any { !it.isVisible }) { clear(); return }
            val bounds = svgGeometryBounds(source)
            val region = svgFilterRegion(filter, filterProportional, bounds, null, viewport, lengths)
            if (region.width <= 0 || region.height <= 0 || primitives.isEmpty()) { clear(); return }
            val transform = localToSceneTransform
            val sx = hypot(transform.mxx, transform.myx) * (scene?.window?.outputScaleX ?: 1.0)
            val sy = hypot(transform.mxy, transform.myy) * (scene?.window?.outputScaleY ?: 1.0)
            require(sx.isFinite() && sy.isFinite()) { "Non-finite SVG filter scale" }
            if (sx <= 0 || sy <= 0) { clear(); return }
            val left = floor(region.minX * sx); val top = floor(region.minY * sy)
            val pw = ceil(region.maxX * sx) - left; val ph = ceil(region.maxY * sy) - top
            require(pw in 1.0..4096.0 && ph in 1.0..4096.0) { "SVG filter raster exceeds 4096 pixels per axis" }
            val key = listOf(left, top, pw, ph, sx, sy, bounds.minX, bounds.minY, bounds.width, bounds.height)
            if (!dirty && key == previous) return
            // Extra slots cover conversions, alpha, blur, shadow, snapshot and previous output.
            budget.reserve(this, pw.toLong() * ph.toLong() * (primitives.size + 12L))
            val params = SnapshotParameters().apply {
                fill = Color.TRANSPARENT
                this.transform = Scale(sx, sy)
                this.viewport = Rectangle2D(left, top, pw, ph)
            }
            require(!opacityNode.opacityProperty().isBound) { "Bound owner opacity on a raster filter is unsupported" }
            val ownerOpacity = opacityNode.opacity
            @Suppress("UNCHECKED_CAST")
            val property = opacityNode.opacityProperty() as StyleableProperty<Number>
            val origin = property.styleOrigin
            // Owner opacity belongs after the filter, unlike descendant opacity and fill-opacity.
            val snapshot = try {
                opacityNode.opacity = 1.0
                source.snapshot(params, null)
            } finally { property.applyStyle(origin, ownerOpacity) }
            val graphic = Result(SvgFilterPixels.fromImage(snapshot), region, standardInput = true)
            val alpha by lazy { Result(graphic.pixels.alpha(), region, standardInput = true) }
            val named = mutableMapOf<String, Result>()
            var previousResult = graphic
            var work = 0.0
            fun charge(passes: Double) {
                work += pw * ph * 4.0 * passes
                budget.reserveWork(this, work)
            }
            for (primitive in primitives) {
                fun input(value: String?): Result = when (value) {
                    "SourceGraphic" -> graphic
                    "SourceAlpha" -> alpha
                    // Missing/forward names use the ordinary omitted-input rule (Filter Effects 9.2).
                    else -> named[value] ?: previousResult
                }
                val style = styleOf(primitive)
                val linear = style.getValue("color-interpolation-filters") == "linearRGB"
                val inputs = when (primitive.name) {
                    "feFlood" -> emptyList()
                    "feMerge" -> primitive.children.filter { it.name == "feMergeNode" }.map { input(it["in"]) }
                    "feComposite" -> listOf(input(primitive["in"]), input(primitive["in2"]))
                    else -> listOf(input(primitive["in"]))
                }
                // Bound total work as well as retained memory, including large merge fan-ins.
                charge(6.0 + inputs.size * 2.0)
                val fallback = if (inputs.isEmpty() || inputs.any { it.standardInput }) region else union(inputs.map { it.region })
                val definedRegion = primitiveRegion(primitive, bounds, fallback)
                val subregion = intersect(region, definedRegion)
                fun distance(name: String, horizontal: Boolean, default: String = "0"): Double =
                    lengths.resolve(primitive[name] ?: default, if (primitiveProportional) 1.0 else if (horizontal) viewport.width else viewport.height) *
                        (if (primitiveProportional) if (horizontal) bounds.width else bounds.height else 1.0) *
                        (if (horizontal) sx else sy)
                fun colour(): Color {
                    val paint = style.getValue("flood-color").let { if (it == "currentColor") style.getValue("color") else it }
                    return Color.web(paint).deriveColor(0.0, 1.0, 1.0, SvgNumbers.fraction(style.getValue("flood-opacity")))
                }
                fun blur(input: SvgFilterPixels): SvgFilterPixels {
                    val values = SvgNumbers.list(primitive["stdDeviation"] ?: if (primitive.name == "feDropShadow") "2" else "0")
                    val x = values[0]; val y = values.getOrElse(1) { x }
                    if (x < 0 || y < 0) return input
                    val sigmaX = x * sx * if (primitiveProportional) bounds.width else 1.0
                    val sigmaY = y * sy * if (primitiveProportional) bounds.height else 1.0
                    charge((if (sigmaX == 0.0) 0.0 else ceil(sigmaX * 3) * 2 + 1) +
                        (if (sigmaY == 0.0) 0.0 else ceil(sigmaY * 3) * 2 + 1))
                    return input.blur(sigmaX, sigmaY)
                }
                val pixels = when (primitive.name) {
                    "feFlood" -> SvgFilterPixels.flood(pw.toInt(), ph.toInt(), colour(), linear)
                    "feOffset" -> inputs[0].pixels.offset(distance("dx", true), distance("dy", false))
                    "feGaussianBlur" -> blur(inputs[0].pixels.converted(linear))
                    "feComposite" -> {
                        val operator = primitive["operator"] ?: "over"
                        fun coefficient(index: Int) = if (operator == "arithmetic") SvgNumbers.scalar(primitive["k$index"] ?: "0") else 0.0
                        inputs[0].pixels.converted(linear).composite(inputs[1].pixels.converted(linear),
                            operator, coefficient(1), coefficient(2), coefficient(3), coefficient(4))
                    }
                    "feMerge" -> {
                        var merged = SvgFilterPixels(pw.toInt(), ph.toInt(), linear)
                        for (part in inputs) merged = part.pixels.converted(linear).composite(merged, "over")
                        merged
                    }
                    "feDropShadow" -> {
                        val original = inputs[0].pixels.converted(linear)
                        val deviations = SvgNumbers.list(primitive["stdDeviation"] ?: "2")
                        if (deviations.any { it < 0 }) original else {
                            val shifted = blur(original.alpha()).offset(distance("dx", true, "2"), distance("dy", false, "2"))
                            val shadow = SvgFilterPixels.flood(pw.toInt(), ph.toInt(), colour(), linear).composite(shifted, "in")
                            original.composite(shadow, "over")
                        }
                    }
                    else -> error("Validated primitive")
                }
                // Never mutate an input buffer reused under another result name.
                val cropped = SvgFilterPixels(pixels.width, pixels.height, pixels.linear, pixels.data.copyOf())
                clip(cropped, subregion, left, top, sx, sy)
                previousResult = Result(cropped, definedRegion)
                primitive["result"]?.takeIf { it.isNotBlank() }?.let { named[it] = previousResult }
            }
            image.x = left / sx; image.y = top / sy
            image.fitWidth = pw / sx; image.fitHeight = ph / sy
            image.opacity = ownerOpacity
            image.image = previousResult.pixels.toImage()
            rasterizations++
            previous = key
            dirty = false
        } catch (failure: IllegalArgumentException) {
            // Never leave a stale successful frame visible after a size/budget/validation failure.
            clear()
            report(failure.message ?: "Invalid SVG filter chain")
        } finally { refreshing = false }
    }

    private fun primitiveRegion(primitive: SvgElement, bounds: Bounds, fallback: Bounds): Bounds {
        // Zero/negative dimensions disable a primitive. Preserve the inherited default subregion.
        fun value(name: String, horizontal: Boolean, position: Boolean): Double {
            val raw = primitive[name] ?: return when (name) {
                "x" -> fallback.minX; "y" -> fallback.minY; "width" -> fallback.width; else -> fallback.height
            }
            val amount = lengths.resolve(raw, if (primitiveProportional) 1.0 else if (horizontal) viewport.width else viewport.height)
            return if (primitiveProportional) amount * (if (horizontal) bounds.width else bounds.height) +
                if (position) if (horizontal) bounds.minX else bounds.minY else 0.0 else amount
        }
        val x = value("x", true, true); val y = value("y", false, true)
        val width = value("width", true, false); val height = value("height", false, false)
        require(listOf(x, y, width, height).all { it.isFinite() }) { "Non-finite filter primitive region" }
        return BoundingBox(x, y, max(0.0, width), max(0.0, height))
    }

    private fun clip(pixels: SvgFilterPixels, region: Bounds, left: Double, top: Double, sx: Double, sy: Double) {
        val x0 = floor(region.minX * sx - left).toInt().coerceIn(0, pixels.width)
        val y0 = floor(region.minY * sy - top).toInt().coerceIn(0, pixels.height)
        val x1 = ceil(region.maxX * sx - left).toInt().coerceIn(0, pixels.width)
        val y1 = ceil(region.maxY * sy - top).toInt().coerceIn(0, pixels.height)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (region.width <= 0 || region.height <= 0 || x !in x0 until x1 || y !in y0 until y1) {
                val index = (y * pixels.width + x) * 4
                pixels.data.fill(0f, index, index + 4)
            }
        }
    }

    private fun observe(node: Node) {
        node.boundsInLocalProperty().addListener(changed)
        node.localToParentTransformProperty().addListener(changed)
        node.opacityProperty().addListener(changed)
        node.visibleProperty().addListener(changed)
        node.effectProperty().addListener(changed)
        node.clipProperty().addListener(changed)
        node.clip?.let(::observe)
        if (node is Shape) {
            node.fillProperty().addListener(changed); node.strokeProperty().addListener(changed)
            node.strokeWidthProperty().addListener(changed)
            node.strokeDashOffsetProperty().addListener(changed); node.strokeDashArray.addListener(changed)
            node.strokeTypeProperty().addListener(changed); node.strokeLineCapProperty().addListener(changed)
            node.strokeLineJoinProperty().addListener(changed); node.strokeMiterLimitProperty().addListener(changed)
            node.smoothProperty().addListener(changed)
        }
        if (node is Text) {
            node.fontProperty().addListener(changed); node.textProperty().addListener(changed)
            node.underlineProperty().addListener(changed); node.strikethroughProperty().addListener(changed)
        }
        if (node is ImageView) node.imageProperty().addListener(changed)
        if (node is Group) node.children.forEach(::observe)
    }
    private fun clear() {
        image.image = null
        budget.release(this)
        previous = null
        dirty = true
    }
    private fun union(regions: List<Bounds>): Bounds {
        val x = regions.minOf { it.minX }; val y = regions.minOf { it.minY }
        return BoundingBox(x, y, regions.maxOf { it.maxX } - x, regions.maxOf { it.maxY } - y)
    }
    private fun intersect(first: Bounds, second: Bounds): Bounds {
        val x = max(first.minX, second.minX); val y = max(first.minY, second.minY)
        return BoundingBox(x, y, max(0.0, min(first.maxX, second.maxX) - x), max(0.0, min(first.maxY, second.maxY) - y))
    }
    companion object {
        private val DESCRIPTIVE = setOf("title", "desc", "metadata")
        private val SUPPORTED = setOf("feFlood", "feComposite", "feMerge", "feOffset", "feGaussianBlur", "feDropShadow")
        private val OPERATORS = setOf("over", "in", "out", "atop", "xor", "arithmetic", "lighter")
    }
}
