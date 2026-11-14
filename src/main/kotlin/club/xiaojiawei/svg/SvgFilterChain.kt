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

/** Per-view live pixel slots and frame work. Scratch pixels are released after each rasterization. */
internal class SvgFilterBudget(private val limit: Int) {
    private val owners = java.util.IdentityHashMap<SvgFilterChain, Long>()
    private var used = 0L
    private val work = java.util.IdentityHashMap<SvgFilterChain, Double>()
    private var usedWork = 0.0
    fun reserve(owner: SvgFilterChain, pixels: Long) {
        val others = used - (owners[owner] ?: 0)
        require(pixels >= 0 && pixels <= limit.toLong() - others) { "SVG filter rasters exceed maxFilterPixels ($limit)" }
        val next = others + pixels
        owners[owner] = pixels
        used = next
    }
    fun reserveWork(owner: SvgFilterChain, operations: Double) {
        val next = usedWork - (work[owner] ?: 0.0) + operations
        require(operations.isFinite() && operations >= 0 && next <= 200_000_000.0) { "SVG filter rasters exceed the per-view 200000000 component-operation work limit" }
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
    private val report: (String) -> Unit,
    private val imageSources: Map<SvgElement, SvgFilterImageSource> = emptyMap(),
    private val primitiveAtTime: (SvgElement) -> SvgElement = { it },
    private val animationTime: () -> Double? = { null }
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
        primitives.forEach { validate(primitiveAtTime(it), it) }
        transforms.setAll(source.transforms)
        source.transforms.clear()
        children.setAll(hidden, image)
        if (imageSources.isNotEmpty()) {
            val definitions = Group().apply { isManaged=false; opacity=0.0; clip=javafx.scene.shape.Rectangle() }
            imageSources.values.forEach { definitions.children += it.node; observe(it.node) }
            children += definitions
        }
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

    private fun validate(primitive: SvgElement, original: SvgElement) {
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
        } else if (primitive.name == "feComponentTransfer") {
            SvgFilterComponentTransfer.parse(primitive)
        } else if (primitive.name in LIGHTING) {
            SvgLightingParameters.parse(primitive) // Validate light subtree and numbers before reparenting.
        } else require(children.isEmpty()) { "Animated filter primitives are unsupported" }
        if (primitive.name !in setOf("feFlood", "feTurbulence", "feMerge", "feImage")) validateInput(primitive["in"])
        if (primitive.name == "feConvolveMatrix") SvgConvolveParameters.parse(primitive)
        if (primitive.name == "feColorMatrix") SvgFilterColorBlend.matrix(primitive)
        if (primitive.name == "feBlend") {
            require(primitive["no-composite"] == null) { "feBlend no-composite is unsupported" }
            validateInput(primitive["in2"])
            SvgFilterColorBlend.blendMode(primitive)
        }
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
        if (primitive.name == "feDisplacementMap") {
            validateInput(primitive["in2"])
            require((primitive["xChannelSelector"] ?: "A") in setOf("R", "G", "B", "A") &&
                (primitive["yChannelSelector"] ?: "A") in setOf("R", "G", "B", "A")) {
                "Invalid feDisplacementMap channel selector"
            }
            SvgNumbers.scalar(primitive["scale"] ?: "0")
        }
        if (primitive.name == "feMorphology") {
            require((primitive["operator"] ?: "erode") in setOf("erode", "dilate")) { "Invalid feMorphology operator" }
            require(SvgNumbers.list(primitive["radius"] ?: "0").size in 1..2) { "feMorphology needs one or two radius values" }
        }
        if (primitive.name == "feTurbulence") {
            val frequency = SvgNumbers.list(primitive["baseFrequency"] ?: "0")
            require(frequency.size in 1..2 && frequency.all { it >= 0.0 }) { "Invalid feTurbulence baseFrequency" }
            require((primitive["type"] ?: "turbulence") in setOf("turbulence", "fractalNoise")) { "Invalid feTurbulence type" }
            require((primitive["stitchTiles"] ?: "noStitch") in setOf("stitch", "noStitch")) { "Invalid feTurbulence stitchTiles" }
            turbulenceOctaves(primitive)
            SvgNumbers.scalar(primitive["seed"] ?: "0")
        }
        val interpolation = styleOf(original).getValue("color-interpolation-filters")
        require(interpolation in setOf("linearRGB", "sRGB", "auto")) { "Invalid color-interpolation-filters" }
    }

    private fun validateInput(value: String?) {
        require(value !in setOf("BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint")) {
            "Unsupported filter input: $value"
        }
    }

    private data class Result(val pixels: SvgFilterPixels, val region: Bounds, val standardInput: Boolean = false)

    // Keep a named result only until its final possible read. Duplicate definitions may retain
    // an earlier value a little longer, but forward/omitted input fallback stays document-ordered.
    private val lastInputUse = buildMap<String, Int> {
        primitives.forEachIndexed { index, primitive ->
            val inputs = when (primitive.name) {
                "feFlood", "feTurbulence", "feImage" -> emptyList()
                "feMerge" -> primitive.children.filter { it.name == "feMergeNode" }.map { it["in"] }
                "feComposite", "feDisplacementMap", "feBlend" -> listOf(primitive["in"], primitive["in2"])
                else -> listOf(primitive["in"])
            }
            inputs.filterNotNull().forEach { put(it, index) }
        }
    }

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
            val key = listOf(left, top, pw, ph, sx, sy, bounds.minX, bounds.minY, bounds.width, bounds.height) + listOfNotNull(animationTime())
            if (!dirty && key == previous) return
            val area = pw.toLong() * ph.toLong()
            val oldOutput = image.image?.let { it.width.toLong() * it.height.toLong() } ?: 0L
            // Snapshot, its transfer array and the RGBA source can coexist. Include the old
            // displayed output at its OLD dimensions, even while resizing down.
            budget.reserve(this, oldOutput + area * 4)
            var work = 0.0
            fun charge(passes: Double) {
                work += pw * ph * 4.0 * passes
                budget.reserveWork(this, work)
            }
            // Charge bounded component traversals, not a blanket count per graph edge. The
            // primitive kernels add their own taps/iterations, so those must not be charged twice.
            charge(2.0) // JavaFX snapshot transfer and source RGBA population.
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
            fun sourcePixels(): SvgFilterPixels = try {
                opacityNode.opacity = 1.0
                SvgFilterPixels.fromImage(source.snapshot(params, null))
            } finally { property.applyStyle(origin, ownerOpacity) }
            fun renderPixels(): SvgFilterPixels {
                val graphic = Result(sourcePixels(), region, standardInput = true)
                var alpha: Result? = null
                val named = mutableMapOf<String, Result>()
                var previousResult = graphic
                fun retainedBuffers(): Set<FloatArray> = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<FloatArray, Boolean>()).apply {
                    add(graphic.pixels.data)
                    alpha?.let { add(it.pixels.data) }
                    add(previousResult.pixels.data)
                    named.values.forEach { add(it.pixels.data) }
                }
                fun reserveBuffers(extra: Int) {
                    budget.reserve(this, oldOutput + area * (retainedBuffers().size + extra))
                }
                for ((index, originalPrimitive) in primitives.withIndex()) {
                    val primitive = primitiveAtTime(originalPrimitive)
                    fun input(value: String?): Result = when (value) {
                        "SourceGraphic" -> graphic
                        "SourceAlpha" -> alpha ?: run {
                            reserveBuffers(1)
                            charge(1.0)
                            Result(graphic.pixels.alpha(), region, standardInput = true).also { alpha = it }
                        }
                        // Missing/forward names use the ordinary omitted-input rule (Filter Effects 9.2).
                        else -> named[value] ?: previousResult
                    }
                    val style = styleOf(originalPrimitive)
                    val linear = style.getValue("color-interpolation-filters") == "linearRGB"
                    val inputs = when (primitive.name) {
                        "feFlood", "feTurbulence", "feImage" -> emptyList()
                        "feMerge" -> primitive.children.filter { it.name == "feMergeNode" }.map { input(it["in"]) }
                        "feComposite", "feDisplacementMap", "feBlend" -> listOf(input(primitive["in"]), input(primitive["in2"]))
                        else -> listOf(input(primitive["in"]))
                    }
                    charge(1.0) // Every primitive clips its output; full RGBA traversal is an upper bound.
                    val fallback = if (primitive.name == "feTile" || inputs.isEmpty() || inputs.any { it.standardInput }) region else union(inputs.map { it.region })
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
                        // Both the estimate and execution inspect mutable support before convolution.
                        charge(2.0)
                        work += input.blurWork(sigmaX, sigmaY)
                        budget.reserveWork(this, work)
                        return input.blur(sigmaX, sigmaY)
                    }
                    val conversionInputs = when (primitive.name) {
                        "feOffset", "feDiffuseLighting", "feSpecularLighting" -> emptyList()
                        "feDisplacementMap" -> inputs.drop(1)
                        else -> inputs
                    }
                    val conversions = conversionInputs.count { it.pixels.linear != linear }
                    // Counts are conservative component-loop passes in addition to specialized work.
                    // Reserve work before planning scans, especially for large merge fan-ins. A
                    // conversion can require two RGB scans plus the actual transfer traversal.
                    when (primitive.name) {
                        "feFlood" -> charge(1.0)
                        "feTile" -> charge(8.0 + conversions * 3.0)
                        "feImage" -> charge(8.0)
                        "feOffset" -> charge(4.0) // Four premultiplied bilinear taps.
                        "feGaussianBlur" -> charge(conversions * 3.0)
                        "feMorphology" -> charge(2.0 + conversions * 3.0) // Two fresh alpha/support scans.
                        "feConvolveMatrix" -> charge(conversions * 3.0)
                        "feColorMatrix" -> charge(16.0 + conversions * 3.0) // 64 scalar operations per pixel.
                        "feComponentTransfer" -> charge(32.0 + conversions * 3.0)
                        "feBlend" -> charge((if (SvgFilterColorBlend.blendMode(primitive) in setOf("normal", "multiply", "screen", "darken", "lighten")) 8.0 else 48.0) + conversions * 3.0) // 32 legacy or 192 extended scalar operations per pixel.
                        "feComposite" -> charge(2.0 + conversions * 3.0) // Composite and RGB/alpha clamp.
                        "feMerge" -> charge(1.0 + inputs.size * 2.0 + conversions * 3.0)
                        "feDropShadow" -> charge(10.0 + conversions * 3.0) // Alpha, offset, flood, two composites.
                        "feDisplacementMap" -> charge(2.0 + if (inputs[1].pixels.linear != linear) 3.0 else 0.0)
                    }
                    val conversionBuffers = conversionInputs.count { it.pixels.conversionNeedsBuffer(linear) }
                    val scratch = when (primitive.name) {
                        "feFlood", "feOffset", "feTurbulence", "feDiffuseLighting", "feSpecularLighting" -> 1
                        "feGaussianBlur", "feMorphology" -> conversionBuffers + 2 // Two separable pass outputs.
                        "feComposite", "feColorMatrix", "feBlend", "feConvolveMatrix", "feComponentTransfer", "feTile" -> conversionBuffers + 1
                        "feImage" -> 4
                        "feDisplacementMap" -> 1 + conversionBuffers
                        "feMerge" -> 1 + if (conversionBuffers > 0) 1 else 0 // Private accumulator and one conversion.
                        "feDropShadow" -> conversionBuffers + 5
                        else -> error("Validated primitive")
                    }
                    reserveBuffers(scratch)
                    val pixels = when (primitive.name) {
                        "feComponentTransfer" -> inputs[0].pixels.converted(linear).componentTransfer(SvgFilterComponentTransfer.parse(primitive))
                        "feFlood" -> SvgFilterPixels.flood(pw.toInt(), ph.toInt(), colour(), linear)
                        "feTurbulence" -> if (subregion.width <= 0.0 || subregion.height <= 0.0) {
                            SvgFilterPixels(pw.toInt(), ph.toInt(), linear)
                        } else {
                            val frequency = SvgNumbers.list(primitive["baseFrequency"] ?: "0")
                            val octaves = turbulenceOctaves(primitive)
                            work += SvgFilterTurbulence.work(pw.toInt(), ph.toInt(), octaves)
                            if (octaves > 0 && frequency.any { it != 0.0 }) work += 65_536 // Bounded gradient-table initialization.
                            budget.reserveWork(this, work)
                            val unitX = if (primitiveProportional) bounds.width else 1.0
                            val unitY = if (primitiveProportional) bounds.height else 1.0
                            val originX = if (primitiveProportional) bounds.minX else 0.0
                            val originY = if (primitiveProportional) bounds.minY else 0.0
                            SvgFilterTurbulence.render(pw.toInt(), ph.toInt(), linear,
                                frequency[0], frequency.getOrElse(1) { frequency[0] }, octaves,
                                SvgNumbers.scalar(primitive["seed"] ?: "0"), primitive["type"] == "fractalNoise",
                                ((left + .5) / sx - originX) / unitX,
                                ((top + .5) / sy - originY) / unitY,
                                1.0 / sx / unitX, 1.0 / sy / unitY, primitive["stitchTiles"] == "stitch",
                                (definedRegion.minX - originX) / unitX, (definedRegion.minY - originY) / unitY,
                                definedRegion.width / unitX, definedRegion.height / unitY)
                        }
                        "feMorphology" -> {
                            val radii = SvgNumbers.list(primitive["radius"] ?: "0")
                            val source = inputs[0].pixels.converted(linear)
                            // Nonpositive radii disable the primitive before device-space scaling;
                            // even a huge finite negative value must not overflow to an error.
                            if (radii.any { it <= 0.0 }) source else {
                                val radiusX = radii[0] * sx * if (primitiveProportional) bounds.width else 1.0
                                val radiusY = radii.getOrElse(1) { radii[0] } * sy * if (primitiveProportional) bounds.height else 1.0
                                work += source.morphologyWork(radiusX, radiusY)
                                budget.reserveWork(this, work)
                                source.morphology(radiusX, radiusY, primitive["operator"] ?: "erode")
                            }
                        }
                        "feDisplacementMap" -> {
                            val scale = SvgNumbers.scalar(primitive["scale"] ?: "0")
                            val scaleX = scale * sx * if (primitiveProportional) bounds.width else 1.0
                            val scaleY = scale * sy * if (primitiveProportional) bounds.height else 1.0
                            work += inputs[0].pixels.displacementWork(scaleX, scaleY)
                            budget.reserveWork(this, work)
                            inputs[0].pixels.displace(inputs[1].pixels, scaleX, scaleY,
                                primitive["xChannelSelector"] ?: "A", primitive["yChannelSelector"] ?: "A", linear)
                        }
                        "feDiffuseLighting", "feSpecularLighting" -> {
                            val parameters = SvgLightingParameters.parse(primitive).resolved(bounds, primitiveProportional)
                            val source = inputs[0].pixels
                            val stepX = 1.0 / sx
                            val stepY = 1.0 / sy
                            val grid = SvgFilterGridPlan(source.width, source.height,
                                (parameters.kernelX ?: stepX) / stepX, (parameters.kernelY ?: stepY) / stepY)
                            work += source.lightingWork(parameters.light, grid)
                            budget.reserveWork(this, work)
                            budget.reserve(this, oldOutput + area * (retainedBuffers().size + 1) + grid.extraPixels)
                            val paint = style.getValue("lighting-color").let {
                                if (it == "currentColor") style.getValue("color") else it
                            }
                            val lightColor = Color.web(paint)
                            if (subregion.width <= 0.0 || subregion.height <= 0.0) {
                                SvgFilterPixels(source.width, source.height, linear)
                            } else {
                                // A cropped input's edge uses the SVG boundary Sobel kernel when it
                                // coincides with this output edge. An expanded output still sees the
                                // transparent padding, and a narrowed output may read its input beyond
                                // the output clip. Do not globally clamp all samples to the input box.
                                val inputRegion = intersect(region, inputs[0].region)
                                fun x0(r: Bounds) = floor(r.minX * sx - left).toInt().coerceIn(0, source.width)
                                fun y0(r: Bounds) = floor(r.minY * sy - top).toInt().coerceIn(0, source.height)
                                fun x1(r: Bounds) = ceil(r.maxX * sx - left).toInt().coerceIn(0, source.width)
                                fun y1(r: Bounds) = ceil(r.maxY * sy - top).toInt().coerceIn(0, source.height)
                                // Only alpha is a height map. Input RGB needs no conversion or copying.
                                source.lighting(parameters.specular, parameters.surfaceScale, parameters.constant,
                                    parameters.exponent, lightColor, linear, parameters.light,
                                    (left + .5) / sx, (top + .5) / sy, stepX, stepY,
                                    parameters.kernelX ?: stepX, parameters.kernelY ?: stepY,
                                    if (x0(inputRegion) == x0(subregion)) x0(subregion) else 0,
                                    if (y0(inputRegion) == y0(subregion)) y0(subregion) else 0,
                                    if (x1(inputRegion) == x1(subregion)) x1(subregion) - 1 else source.width - 1,
                                    if (y1(inputRegion) == y1(subregion)) y1(subregion) - 1 else source.height - 1)
                            }
                        }
                        "feOffset" -> inputs[0].pixels.offset(distance("dx", true), distance("dy", false))
                        "feGaussianBlur" -> blur(inputs[0].pixels.converted(linear))
                        "feConvolveMatrix" -> {
                            val parameters = SvgConvolveParameters.parse(primitive)
                            val source = inputs[0].pixels
                            if (parameters.passthrough) source else {
                                val dx = parameters.kernelX?.times(sx)?.times(if (primitiveProportional) bounds.width else 1.0) ?: 1.0
                                val dy = parameters.kernelY?.times(sy)?.times(if (primitiveProportional) bounds.height else 1.0) ?: 1.0
                                val grid = SvgFilterGridPlan(source.width, source.height, dx, dy)
                                val gridWork = grid.gridPixels.toDouble() * (parameters.orderX.toDouble() * parameters.orderY * 12.0 + 24.0)
                                work += gridWork + grid.resampleWork
                                budget.reserveWork(this, work)
                                budget.reserve(this, oldOutput + area * (retainedBuffers().size + conversionBuffers + 1) + grid.extraPixels)
                                val inputRegion = intersect(region, inputs[0].region)
                                val x0 = floor((inputRegion.minX * sx - left) / dx).toInt().coerceIn(0, grid.gridWidth)
                                val y0 = floor((inputRegion.minY * sy - top) / dy).toInt().coerceIn(0, grid.gridHeight)
                                val x1 = ceil((inputRegion.maxX * sx - left) / dx).toInt().coerceIn(x0, grid.gridWidth)
                                val y1 = ceil((inputRegion.maxY * sy - top) / dy).toInt().coerceIn(y0, grid.gridHeight)
                                val sampled = grid.extendedInput(source.converted(linear),
                                    floor(inputRegion.minX * sx - left).toInt().coerceIn(0, source.width),
                                    floor(inputRegion.minY * sy - top).toInt().coerceIn(0, source.height),
                                    ceil(inputRegion.maxX * sx - left).toInt().coerceIn(0, source.width),
                                    ceil(inputRegion.maxY * sy - top).toInt().coerceIn(0, source.height), parameters.edgeMode)
                                grid.output(sampled.convolveMatrix(parameters, x0, y0, x1, y1))
                            }
                        }
                        "feImage" -> {
                            val prepared=imageSources[originalPrimitive]
                            if(prepared == null || definedRegion.width <= 0 || definedRegion.height <= 0) SvgFilterPixels(pw.toInt(),ph.toInt(),linear)
                            else {
                                prepared.configure(bounds,definedRegion,primitiveProportional)
                                prepared.node.applyCss()
                                if(prepared.node is javafx.scene.Parent) prepared.node.layout()
                                SvgFilterPixels.fromImage(prepared.node.snapshot(params,null)).converted(linear)
                            }
                        }
                        "feTile" -> inputs[0].pixels.converted(linear).tile(
                            inputs[0].region.minX*sx-left,inputs[0].region.minY*sy-top,
                            inputs[0].region.width*sx,inputs[0].region.height*sy)
                        "feColorMatrix" -> inputs[0].pixels.converted(linear).colorMatrix(SvgFilterColorBlend.matrix(primitive))
                        "feBlend" -> inputs[0].pixels.converted(linear).blend(inputs[1].pixels.converted(linear),
                            SvgFilterColorBlend.blendMode(primitive))
                        "feComposite" -> {
                            val operator = primitive["operator"] ?: "over"
                            fun coefficient(index: Int) = if (operator == "arithmetic") SvgNumbers.scalar(primitive["k$index"] ?: "0") else 0.0
                            inputs[0].pixels.converted(linear).composite(inputs[1].pixels.converted(linear),
                                operator, coefficient(1), coefficient(2), coefficient(3), coefficient(4))
                        }
                        "feMerge" -> {
                            val merged = SvgFilterPixels(pw.toInt(), ph.toInt(), linear)
                            for (part in inputs) merged.mergeOver(part.pixels.converted(linear))
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
                    // New operation results are owned here. Identity operations can alias retained
                    // inputs, so only those require a copy before the primitive subregion is applied.
                    val cropped = if (pixels.data in retainedBuffers()) {
                        reserveBuffers(1)
                        charge(1.0)
                        SvgFilterPixels(pixels.width, pixels.height, pixels.linear, pixels.data.copyOf())
                    } else pixels
                    clip(cropped, subregion, left, top, sx, sy)
                    previousResult = Result(cropped, definedRegion)
                    primitive["result"]?.takeIf { it.isNotBlank() }?.let { named[it] = previousResult }
                    named.keys.removeIf { (lastInputUse[it] ?: -1) <= index }
                    if ((lastInputUse["SourceAlpha"] ?: -1) <= index) alpha = null
                }
                return previousResult.pixels
            }
            // Leave the rendering frame before export: source/alpha/DAG buffers are now dead.
            val finalPixels = renderPixels()
            // Output conversion, integer transfer buffer and the new JavaFX image may coexist.
            budget.reserve(this, oldOutput + area * 4) // Final pixels, conversion, transfer and image.
            charge(if (finalPixels.linear) 4.0 else 2.0)
            image.x = left / sx; image.y = top / sy
            image.fitWidth = pw / sx; image.fitHeight = ph / sy
            image.opacity = ownerOpacity
            image.image = finalPixels.toImage()
            // Work remains charged per view/frame, but no source/DAG/scratch pixels survive.
            budget.reserve(this, area)
            rasterizations++
            previous = key
            dirty = false
        } catch (failure: IllegalArgumentException) {
            // Never leave a stale successful frame visible after a size/budget/validation failure.
            clear()
            report(failure.message ?: "Invalid SVG filter chain")
        } finally { refreshing = false }
    }

    private fun turbulenceOctaves(primitive: SvgElement): Int {
        val value = SvgNumbers.scalar(primitive["numOctaves"] ?: "1")
        require(value >= 0.0 && value <= 16.0 && value == floor(value)) { "feTurbulence numOctaves must be an integer between 0 and 16" }
        return value.toInt()
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
        if (node is javafx.scene.Parent) node.childrenUnmodifiable.forEach(::observe)
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
        private val LIGHTING = setOf("feDiffuseLighting", "feSpecularLighting")
        private val SUPPORTED = LIGHTING + setOf("feFlood", "feComposite", "feMerge", "feOffset", "feGaussianBlur", "feDropShadow", "feTurbulence", "feMorphology", "feDisplacementMap", "feColorMatrix", "feBlend", "feConvolveMatrix", "feComponentTransfer", "feTile", "feImage")
        private val OPERATORS = setOf("over", "in", "out", "atop", "xor", "arithmetic", "lighter")
    }
}
