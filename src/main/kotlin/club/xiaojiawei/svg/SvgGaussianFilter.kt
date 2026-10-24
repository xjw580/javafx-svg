package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.effect.ColorAdjust
import javafx.scene.effect.Effect
import javafx.scene.effect.DropShadow
import javafx.scene.effect.BlurType
import javafx.scene.effect.GaussianBlur
import javafx.scene.effect.MotionBlur
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One Gaussian primitive, with separate input/output clips and the SVG transform outside the filter. */
internal class SvgGaussianFilter(
    private val source: Node,
    private val filter: SvgElement,
    private val primitive: SvgElement,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache,
    private val interpolation: String,
    private val shadowColor: Color = Color.BLACK,
    private val report: (String) -> Unit
) : Group() {
    private val filterProportional = units(filter["filterUnits"] ?: "objectBoundingBox")
    private val primitiveProportional = units(filter["primitiveUnits"] ?: "userSpaceOnUse")
    private val shadow = primitive.name == "feDropShadow"
    private val deviations = SvgNumbers.list(primitive["stdDeviation"] ?: if (shadow) "2" else "0").also {
        require(it.size in 1..2) { "feGaussianBlur needs one or two stdDeviation values" }
    }
    private val alphaOnly = primitive["in"] == "SourceAlpha"
    private val inputRegion = Rectangle()
    private val outputRegion = Rectangle()
    private val input = Group(source)
    private val blurred = Group(input)
    private val output = Group(blurred)
    private var lastSigma: Pair<Double, Double>? = null
    private var shadowKey: List<Double>? = null

    init {
        require(primitive["in"] in listOf(null, "SourceGraphic", "SourceAlpha")) { "Unsupported Gaussian filter input" }
        require(primitive["edgeMode"] in listOf(null, "none")) { "Only Gaussian edgeMode=none is supported" }
        require(interpolation in setOf("sRGB", "linearRGB", "auto")) { "Invalid color-interpolation-filters" }
        refresh()
        transforms.setAll(source.transforms)
        source.transforms.clear()
        children.setAll(output)
        styleClass += "svg-filter-output"
        observe(source)
    }

    override fun layoutChildren() {
        refresh()
        super.layoutChildren()
    }

    fun refresh() {
        try {
            val bounds = svgGeometryBounds(source)
            val region = region(filter, filterProportional, bounds, null)
            val subregion = region(primitive, primitiveProportional, bounds, region)
            val rawX = deviations[0]
            val rawY = deviations.getOrElse(1) { rawX }
            // Negative standard deviations disable the primitive, as defined by Filter Effects.
            val sigmaX = if (rawX < 0 || rawY < 0) 0.0 else rawX * if (primitiveProportional) bounds.width else 1.0
            val sigmaY = if (rawX < 0 || rawY < 0) 0.0 else rawY * if (primitiveProportional) bounds.height else 1.0
            require(sigmaX.isFinite() && sigmaY.isFinite()) { "Non-finite Gaussian deviation" }
            require(interpolation != "linearRGB" || if (shadow) {
                if (alphaOnly) shadowColor.red == 0.0 && shadowColor.green == 0.0 && shadowColor.blue == 0.0
                else isMonochrome(source, shadowColor)
            } else alphaOnly || (sigmaX == 0.0 && sigmaY == 0.0) || isMonochrome(source)) {
                "Multicolor linearRGB Gaussian filtering is unsupported; use color-interpolation-filters=sRGB"
            }
            val sigma = sigmaX to sigmaY
            if (shadow) {
                val dx = lengths.resolve(primitive["dx"] ?: "2", if (primitiveProportional) 1.0 else viewport.width) * if (primitiveProportional) bounds.width else 1.0
                val dy = lengths.resolve(primitive["dy"] ?: "2", if (primitiveProportional) 1.0 else viewport.height) * if (primitiveProportional) bounds.height else 1.0
                val key = listOf(sigmaX, sigmaY, dx, dy)
                if (shadowKey != key) {
                    require(sigmaX <= 127.0 / 3 && sigmaY <= 127.0 / 3) { "Drop shadow deviation exceeds the native kernel limit" }
                    blurred.effect = if (rawX < 0 || rawY < 0) null else DropShadow().apply {
                        blurType = BlurType.GAUSSIAN; color = shadowColor
                        width = 6 * sigmaX + 1; height = 6 * sigmaY + 1
                        offsetX = dx; offsetY = dy
                        if (alphaOnly) input = ColorAdjust().apply { brightness = -1.0 }
                    }
                    shadowKey = key
                }
            } else if (lastSigma != sigma) {
                blurred.effect = gaussian(sigmaX, sigmaY)
                lastSigma = sigma
            }
            setRegion(inputRegion, region)
            val left = max(region.minX, subregion.minX)
            val top = max(region.minY, subregion.minY)
            setRegion(outputRegion, BoundingBox(left, top,
                max(0.0, min(region.maxX, subregion.maxX) - left), max(0.0, min(region.maxY, subregion.maxY) - top)))
            input.clip = inputRegion
            output.clip = outputRegion
        } catch (e: IllegalArgumentException) {
            blurred.effect = null
            lastSigma = null
            shadowKey = null
            input.clip = null
            output.clip = null
            report(e.message ?: "Invalid Gaussian filter")
        }
    }

    private fun gaussian(sigmaX: Double, sigmaY: Double): Effect? {
        // OpenJFX's Gaussian kernel uses sigma = radius / 3; each radius is limited to 63.
        // Splitting variance across passes avoids silently clamping larger standard deviations.
        val passes = max(1.0, ceil(max(sigmaX * sigmaX, sigmaY * sigmaY) / (21.0 * 21.0)))
        require(passes <= 16) { "Gaussian filter exceeds the 16-pass limit" }
        var effect: Effect? = if (alphaOnly) ColorAdjust().apply { brightness = -1.0 } else null
        val radiusX = 3.0 * sigmaX / sqrt(passes)
        val radiusY = 3.0 * sigmaY / sqrt(passes)
        repeat(passes.toInt()) {
            if (sigmaX == sigmaY) {
                if (radiusX > 0) effect = GaussianBlur(radiusX).apply { input = effect }
            } else {
                if (radiusX > 0) effect = MotionBlur(0.0, radiusX).apply { input = effect }
                if (radiusY > 0) effect = MotionBlur(90.0, radiusY).apply { input = effect }
            }
        }
        return effect
    }

    private fun region(element: SvgElement, proportional: Boolean, bounds: Bounds, fallback: Bounds?): Bounds {
        fun value(name: String, horizontal: Boolean, position: Boolean): Double {
            val raw = element[name]
            if (raw == null && fallback != null) return when (name) {
                "x" -> fallback.minX; "y" -> fallback.minY; "width" -> fallback.width; else -> fallback.height
            }
            val reference = if (horizontal) viewport.width else viewport.height
            val amount = lengths.resolve(raw ?: if (position) "-10%" else "120%", if (proportional) 1.0 else reference)
            val extent = if (horizontal) bounds.width else bounds.height
            val origin = if (horizontal) bounds.minX else bounds.minY
            return if (proportional) amount * extent + if (position) origin else 0.0 else amount
        }
        val x = value("x", true, true)
        val y = value("y", false, true)
        val width = value("width", true, false)
        val height = value("height", false, false)
        require(listOf(x, y, width, height).all { it.isFinite() }) { "Non-finite filter region" }
        require(width >= 0 && height >= 0) { "Negative filter region" }
        return BoundingBox(x, y, width, height)
    }

    private fun observe(node: Node) {
        node.boundsInLocalProperty().addListener { _ -> requestLayout() }
        if (node is Shape) {
            node.fillProperty().addListener { _ -> requestLayout() }
            node.strokeProperty().addListener { _ -> requestLayout() }
        }
        if (node is Group) node.children.forEach(::observe)
    }

    private fun isMonochrome(node: Node, required: Color? = null): Boolean {
        var rgb: List<Double>? = required?.let { listOf(it.red, it.green, it.blue) }
        fun paint(value: Paint?): Boolean {
            if (value == null) return true
            if (value !is Color) return false
            if (value.opacity == 0.0) return true
            val next = listOf(value.red, value.green, value.blue)
            if (rgb == null) rgb = next
            return rgb == next
        }
        fun visit(current: Node): Boolean = when {
            !current.isVisible -> true
            current is Group -> current.children.all(::visit)
            current is Shape -> paint(current.fill) && (current.strokeWidth <= 0 || paint(current.stroke))
            else -> false
        }
        return visit(node)
    }

    private fun units(value: String): Boolean = when (value) {
        "objectBoundingBox" -> true
        "userSpaceOnUse" -> false
        else -> throw SvgException("Invalid filter coordinate units: $value")
    }

    private fun setRegion(rectangle: Rectangle, bounds: Bounds) {
        rectangle.x = bounds.minX; rectangle.y = bounds.minY
        rectangle.width = bounds.width; rectangle.height = bounds.height
    }
}
