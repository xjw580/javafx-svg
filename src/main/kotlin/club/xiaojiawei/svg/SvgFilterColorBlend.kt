package club.xiaojiawei.svg

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** SVG color-matrix parameters; calculations follow Filter Effects 1 section 9.6. */
internal object SvgFilterColorBlend {
    val BLEND_MODES = setOf("normal", "multiply", "screen", "darken", "lighten",
        "overlay", "color-dodge", "color-burn", "hard-light", "soft-light", "difference", "exclusion",
        "hue", "saturation", "color", "luminosity")

    fun blendMode(primitive: SvgElement): String = (primitive["mode"] ?: "normal").also {
        require(it in BLEND_MODES) { "Unsupported feBlend mode: $it" }
    }

    fun matrix(primitive: SvgElement): DoubleArray {
        val type = primitive["type"] ?: "matrix"
        require(type in setOf("matrix", "saturate", "hueRotate", "luminanceToAlpha")) { "Invalid feColorMatrix type: $type" }
        // values is explicitly inapplicable for luminanceToAlpha, including malformed values.
        if (type == "luminanceToAlpha") return doubleArrayOf(
            0.0,0.0,0.0,0.0,0.0, 0.0,0.0,0.0,0.0,0.0, 0.0,0.0,0.0,0.0,0.0,
            .2126,.7152,.0722,0.0,0.0)
        val raw = primitive["values"]
        if (type == "matrix") {
            if (raw == null) return identity()
            val values = SvgNumbers.list(raw)
            return if (values.size == 20) values.toDoubleArray() else identity()
        }
        val value = if (raw == null) { if (type == "saturate") 1.0 else 0.0 } else {
            val values = SvgNumbers.list(raw)
            // Filter Effects 1: a syntactically valid list with the wrong count passes through.
            if (values.size != 1) return identity()
            values.single()
        }
        if (type == "saturate") {
            val s = value
            return doubleArrayOf(
                .213+.787*s, .715-.715*s, .072-.072*s, 0.0,0.0,
                .213-.213*s, .715+.285*s, .072-.072*s, 0.0,0.0,
                .213-.213*s, .715-.715*s, .072+.928*s, 0.0,0.0,
                0.0,0.0,0.0,1.0,0.0)
        }
        // Reduce degrees before conversion to radians, so every finite angle is safe.
        val reduced = value % 360.0
        val angle = Math.toRadians(if (reduced < 0.0) reduced + 360.0 else reduced)
        val c = cos(angle); val s = sin(angle)
        return doubleArrayOf(
            .213+.787*c-.213*s, .715-.715*c-.715*s, .072-.072*c+.928*s,0.0,0.0,
            .213-.213*c+.143*s, .715+.285*c+.140*s, .072-.072*c-.283*s,0.0,0.0,
            .213-.213*c-.787*s, .715-.715*c+.715*s, .072+.928*c+.072*s,0.0,0.0,
            0.0,0.0,0.0,1.0,0.0)
    }

    private fun identity() = doubleArrayOf(
        1.0,0.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0,0.0,
        0.0,0.0,1.0,0.0,0.0, 0.0,0.0,0.0,1.0,0.0)
}

/** Unpremultiply, transform all four channels, clamp straight values, then premultiply once. */
internal fun SvgFilterPixels.colorMatrix(matrix: DoubleArray): SvgFilterPixels {
    require(matrix.size == 20 && matrix.all { it.isFinite() }) { "Invalid color matrix" }
    val result = SvgFilterPixels(width, height, linear)
    // Only unusually large rows need normalization; ordinary matrices retain their exact arithmetic.
    val scales = DoubleArray(4) { row ->
        var largest = 0.0
        for (c in 0..4) largest = max(largest, abs(matrix[row * 5 + c]))
        if (largest > 1e300) largest else 1.0
    }
    val coefficients = DoubleArray(20) { matrix[it] / scales[it / 5] }
    fun channel(row: Int, r: Double, g: Double, b: Double, a: Double): Double {
        val j = row * 5
        val value = coefficients[j]*r + coefficients[j+1]*g + coefficients[j+2]*b + coefficients[j+3]*a + coefficients[j+4]
        // Normalized sums are finite, so overflow of the final product has a definite sign.
        return (value * scales[row]).coerceIn(0.0, 1.0)
    }
    for (i in data.indices step 4) {
        val a = data[i+3].toDouble()
        val r = if (a > 0.0) (data[i]/a).coerceIn(0.0,1.0) else 0.0
        val g = if (a > 0.0) (data[i+1]/a).coerceIn(0.0,1.0) else 0.0
        val b = if (a > 0.0) (data[i+2]/a).coerceIn(0.0,1.0) else 0.0
        val alpha = channel(3,r,g,b,a)
        result.data[i+3] = alpha.toFloat()
        result.data[i] = (channel(0,r,g,b,a)*alpha).toFloat()
        result.data[i+1] = (channel(1,r,g,b,a)*alpha).toFloat()
        result.data[i+2] = (channel(2,r,g,b,a)*alpha).toFloat()
    }
    return result
}

/** Receiver is SVG in (source A); other is in2 (backdrop B). SVG 1.1 premultiplied equations. */
internal fun SvgFilterPixels.blend(other: SvgFilterPixels, mode: String): SvgFilterPixels {
    require(width == other.width && height == other.height) { "Blend inputs have different dimensions" }
    require(mode in SvgFilterColorBlend.BLEND_MODES) { "Unsupported feBlend mode: $mode" }
    val backdrop = other.converted(linear).data
    val result = SvgFilterPixels(width, height, linear)
    val sourceColor = DoubleArray(3); val backdropColor = DoubleArray(3); val mixed = DoubleArray(3)
    val extended = mode !in setOf("normal", "multiply", "screen", "darken", "lighten")
    for (i in data.indices step 4) {
        val a = data[i+3].toDouble(); val b = backdrop[i+3].toDouble()
        if (extended) {
            for (c in 0..2) {
                sourceColor[c] = if (a > 0) (data[i+c]/a).coerceIn(0.0,1.0) else 0.0
                backdropColor[c] = if (b > 0) (backdrop[i+c]/b).coerceIn(0.0,1.0) else 0.0
            }
            svgBlendStraight(backdropColor, sourceColor, mode, mixed)
        }
        val alpha = (a+b-a*b).coerceIn(0.0,1.0)
        result.data[i+3] = alpha.toFloat()
        for (c in 0..2) {
            val ca = data[i+c].toDouble(); val cb = backdrop[i+c].toDouble()
            val sourceOver = ca+(1-a)*cb
            val backdropOver = cb+(1-b)*ca
            val color = when (mode) {
                "normal" -> sourceOver
                "multiply" -> (1-a)*cb+(1-b)*ca+ca*cb
                "screen" -> ca+cb-ca*cb
                "darken" -> min(sourceOver,backdropOver)
                "lighten" -> max(sourceOver,backdropOver)
                else -> (1-a)*cb+(1-b)*ca+a*b*mixed[c]
            }
            result.data[i+c] = color.coerceIn(0.0,alpha).toFloat()
        }
    }
    return result
}
