package club.xiaojiawei.svg

import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Filter-local pixels in premultiplied RGBA order, in either sRGB or linear RGB.
 *
 * Operations do not change their inputs, except [clip] and private-accumulator [mergeOver].
 * Identity operations may return this instance or share an alpha-only backing array across
 * colour spaces, so a caller clipping a shared DAG input must first copy its data.
 */
internal class SvgFilterPixels(
    val width: Int,
    val height: Int,
    val linear: Boolean,
    val data: FloatArray = FloatArray(componentCount(width, height))
) {
    init {
        require(data.size == componentCount(width, height)) { "Filter pixel data has the wrong size" }
    }

    /** A pure-alpha image has identical values in either RGB space and needs no copied buffer. */
    fun conversionNeedsBuffer(linear: Boolean): Boolean = linear != this.linear &&
        data.indices.step(4).any { data[it] != 0f || data[it + 1] != 0f || data[it + 2] != 0f }

    fun converted(linear: Boolean): SvgFilterPixels {
        if (linear == this.linear) return this
        if (!conversionNeedsBuffer(linear)) return SvgFilterPixels(width, height, linear, data)
        val result = SvgFilterPixels(width, height, linear)
        for (i in data.indices step 4) {
            val alpha = data[i + 3]
            result.data[i + 3] = alpha
            if (alpha > 0f) {
                for (channel in 0..2) {
                    // Transfer functions apply to straight RGB, never to premultiplied RGB.
                    val straight = (data[i + channel] / alpha).toDouble().coerceIn(0.0, 1.0)
                    result.data[i + channel] = (transfer(straight, linear) * alpha).toFloat()
                }
            }
        }
        return result
    }

    fun alpha(): SvgFilterPixels = SvgFilterPixels(width, height, linear).also { result ->
        for (i in 3 until data.size step 4) result.data[i] = data[i]
    }

    /** Positive displacements move the input right/down; outside samples are transparent. */
    fun offset(dx: Double, dy: Double): SvgFilterPixels {
        require(dx.isFinite() && dy.isFinite()) { "Non-finite feOffset displacement" }
        if (dx == 0.0 && dy == 0.0) return this
        val result = SvgFilterPixels(width, height, linear)
        for (y in 0 until height) {
            val sy = y - dy
            if (sy <= -1.0 || sy >= height) continue
            val y0 = floor(sy).toInt()
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = x - dx
                if (sx <= -1.0 || sx >= width) continue
                val x0 = floor(sx).toInt()
                val fx = sx - x0
                val out = (y * width + x) * 4
                for (iy in 0..1) {
                    val sampleY = y0 + iy
                    if (sampleY !in 0 until height) continue
                    for (ix in 0..1) {
                        val sampleX = x0 + ix
                        if (sampleX !in 0 until width) continue
                        val weight = ((if (ix == 0) 1.0 - fx else fx) *
                            (if (iy == 0) 1.0 - fy else fy)).toFloat()
                        val input = (sampleY * width + sampleX) * 4
                        for (channel in 0..3) result.data[out + channel] += data[input + channel] * weight
                    }
                }
            }
        }
        return result
    }

    /**
     * Conservative component taps for the actual Gaussian support, excluding its input scan.
     * Recompute on every call: [data] and [clip] permit mutation between estimates and rendering.
     */
    fun blurWork(sigmaX: Double, sigmaY: Double): Long = blurPlan(sigmaX, sigmaY).componentTaps

    /** A separable, normalized Gaussian truncated at three standard deviations. */
    fun blur(sigmaX: Double, sigmaY: Double): SvgFilterPixels {
        val plan = blurPlan(sigmaX, sigmaY)
        require(plan.componentTaps <= MAX_BLUR_COMPONENT_TAPS) {
            "Gaussian filter exceeds the $MAX_BLUR_COMPONENT_TAPS pixel-component tap work limit"
        }
        if (plan.componentTaps == 0L) return this
        var result = this
        if (plan.radiusX > 0) result = result.convolve(kernel(sigmaX, plan.radiusX),
            horizontal = true, plan.horizontalBounds, plan.alphaOnly)
        if (plan.radiusY > 0) result = result.convolve(kernel(sigmaY, plan.radiusY),
            horizontal = false, plan.outputBounds, plan.alphaOnly)
        return result
    }

    private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Long get() = (right - left).toLong() * (bottom - top)
    }

    private data class BlurPlan(
        val radiusX: Int,
        val radiusY: Int,
        val horizontalBounds: PixelBounds,
        val outputBounds: PixelBounds,
        val alphaOnly: Boolean,
        val componentTaps: Long
    )

    private fun blurPlan(sigmaX: Double, sigmaY: Double): BlurPlan {
        require(sigmaX.isFinite() && sigmaY.isFinite()) { "Non-finite Gaussian deviation" }
        val empty = PixelBounds(0, 0, 0, 0)
        // A negative component disables the primitive; a zero component disables only that axis.
        if (sigmaX < 0.0 || sigmaY < 0.0 || (sigmaX == 0.0 && sigmaY == 0.0)) {
            return BlurPlan(0, 0, empty, empty, true, 0)
        }
        // Validate even an empty image, rather than letting empty support bypass the radius limit.
        val radiusX = kernelRadius(sigmaX)
        val radiusY = kernelRadius(sigmaY)
        var left = width; var top = height; var right = 0; var bottom = 0
        var alphaOnly = true
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            val colour = data[i] != 0f || data[i + 1] != 0f || data[i + 2] != 0f
            if (colour) alphaOnly = false
            if (colour || data[i + 3] != 0f) {
                left = min(left, x); top = min(top, y)
                right = max(right, x + 1); bottom = max(bottom, y + 1)
            }
        }
        if (right <= left || bottom <= top) return BlurPlan(radiusX, radiusY, empty, empty, true, 0)
        val horizontal = PixelBounds(max(0, left - radiusX), top, min(width, right + radiusX), bottom)
        val output = PixelBounds(horizontal.left, max(0, top - radiusY),
            horizontal.right, min(height, bottom + radiusY))
        val channels = if (alphaOnly) 1L else 4L
        val taps = channels * ((if (radiusX == 0) 0L else horizontal.area * (radiusX * 2L + 1)) +
            (if (radiusY == 0) 0L else output.area * (radiusY * 2L + 1)))
        return BlurPlan(radiusX, radiusY, horizontal, output, alphaOnly, taps)
    }

    /** This is SVG's source (in); [other] is its destination (in2). */
    fun composite(
        other: SvgFilterPixels,
        operator: String,
        k1: Double = 0.0,
        k2: Double = 0.0,
        k3: Double = 0.0,
        k4: Double = 0.0
    ): SvgFilterPixels {
        require(width == other.width && height == other.height) { "Composite inputs have different dimensions" }
        require(operator in setOf("over", "in", "out", "atop", "xor", "lighter", "arithmetic")) {
            "Unsupported feComposite operator: $operator"
        }
        if (operator == "arithmetic") {
            require(k1.isFinite() && k2.isFinite() && k3.isFinite() && k4.isFinite()) {
                "Non-finite arithmetic composite coefficient"
            }
        }
        val destination = other.converted(linear).data
        val result = SvgFilterPixels(width, height, linear)
        for (i in data.indices step 4) {
            if (operator == "arithmetic") {
                // W3C Filter Effects: all four premultiplied channels use the same polynomial.
                for (channel in 0..3) {
                    val sourceValue = data[i + channel].toDouble()
                    val destinationValue = destination[i + channel].toDouble()
                    val value = k1 * sourceValue * destinationValue + k2 * sourceValue +
                        k3 * destinationValue + k4
                    require(!value.isNaN()) { "Arithmetic composite coefficients overflowed" }
                    result.data[i + channel] = value.coerceIn(0.0, 1.0).toFloat()
                }
            } else {
                val sourceAlpha = data[i + 3].toDouble()
                val destinationAlpha = destination[i + 3].toDouble()
                val sourceFactor: Double
                val destinationFactor: Double
                when (operator) {
                    "over" -> { sourceFactor = 1.0; destinationFactor = 1.0 - sourceAlpha }
                    "in" -> { sourceFactor = destinationAlpha; destinationFactor = 0.0 }
                    "out" -> { sourceFactor = 1.0 - destinationAlpha; destinationFactor = 0.0 }
                    "atop" -> { sourceFactor = destinationAlpha; destinationFactor = 1.0 - sourceAlpha }
                    "xor" -> { sourceFactor = 1.0 - destinationAlpha; destinationFactor = 1.0 - sourceAlpha }
                    else -> { sourceFactor = 1.0; destinationFactor = 1.0 } // lighter
                }
                for (channel in 0..3) {
                    result.data[i + channel] = (data[i + channel] * sourceFactor +
                        destination[i + channel] * destinationFactor).coerceIn(0.0, 1.0).toFloat()
                }
            }
            // Arithmetic can otherwise produce colors greater than the resulting alpha.
            val alpha = result.data[i + 3]
            for (channel in 0..2) result.data[i + channel] = result.data[i + channel].coerceAtMost(alpha)
        }
        return result
    }

    /** Source-over into a private merge accumulator; never call on a retained DAG input. */
    fun mergeOver(source: SvgFilterPixels): SvgFilterPixels {
        require(width == source.width && height == source.height && linear == source.linear) {
            "Merge inputs have different dimensions or colour spaces"
        }
        for (i in data.indices step 4) {
            val destinationFactor = 1.0 - source.data[i + 3].toDouble()
            for (channel in 0..3) {
                data[i + channel] = (source.data[i + channel].toDouble() +
                    data[i + channel] * destinationFactor).coerceIn(0.0, 1.0).toFloat()
            }
            val alpha = data[i + 3]
            for (channel in 0..2) data[i + channel] = data[i + channel].coerceAtMost(alpha)
        }
        return this
    }

    /** Clear outside [left, right) x [top, bottom), in place. */
    fun clip(left: Int, top: Int, right: Int, bottom: Int): SvgFilterPixels {
        val x0 = left.coerceIn(0, width)
        val x1 = right.coerceIn(0, width)
        val y0 = top.coerceIn(0, height)
        val y1 = bottom.coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) {
            data.fill(0f)
            return this
        }
        for (y in 0 until height) {
            val row = y * width * 4
            if (y < y0 || y >= y1) data.fill(0f, row, row + width * 4)
            else {
                data.fill(0f, row, row + x0 * 4)
                data.fill(0f, row + x1 * 4, row + width * 4)
            }
        }
        return this
    }

    fun toImage(): WritableImage {
        require(width > 0 && height > 0) { "An image needs positive filter dimensions" }
        val srgb = converted(false).data
        val pixels = IntArray(width * height)
        for (pixel in pixels.indices) {
            val i = pixel * 4
            val alpha = (srgb[i + 3].coerceIn(0f, 1f) * 255).roundToInt()
            fun component(channel: Int): Int = (srgb[i + channel].coerceIn(0f, 1f) * 255)
                .roundToInt().coerceAtMost(alpha)
            pixels[pixel] = (alpha shl 24) or (component(0) shl 16) or (component(1) shl 8) or component(2)
        }
        return WritableImage(width, height).also {
            it.pixelWriter.setPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), pixels, 0, width)
        }
    }

    private fun convolve(weights: DoubleArray, horizontal: Boolean, bounds: PixelBounds, alphaOnly: Boolean): SvgFilterPixels {
        val result = SvgFilterPixels(width, height, linear)
        val radius = weights.size / 2
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                var red = 0.0; var green = 0.0; var blue = 0.0; var alpha = 0.0
                for (tap in weights.indices) {
                    val sx = if (horizontal) x + tap - radius else x
                    val sy = if (horizontal) y else y + tap - radius
                    if (sx !in 0 until width || sy !in 0 until height) continue
                    val input = (sy * width + sx) * 4
                    val weight = weights[tap]
                    if (!alphaOnly) {
                        red += data[input] * weight
                        green += data[input + 1] * weight
                        blue += data[input + 2] * weight
                    }
                    alpha += data[input + 3] * weight
                }
                val out = (y * width + x) * 4
                result.data[out] = red.toFloat()
                result.data[out + 1] = green.toFloat()
                result.data[out + 2] = blue.toFloat()
                result.data[out + 3] = alpha.toFloat()
            }
        }
        return result
    }

    companion object {
        private const val MAX_BLUR_RADIUS = 256
        private const val MAX_BLUR_COMPONENT_TAPS = 150_000_000L

        fun fromImage(image: WritableImage): SvgFilterPixels {
            val width = image.width.toInt()
            val height = image.height.toInt()
            val result = SvgFilterPixels(width, height, false)
            val pixels = IntArray(width * height)
            image.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), pixels, 0, width)
            for (pixel in pixels.indices) {
                val value = pixels[pixel]
                val i = pixel * 4
                result.data[i] = ((value ushr 16) and 255) / 255f
                result.data[i + 1] = ((value ushr 8) and 255) / 255f
                result.data[i + 2] = (value and 255) / 255f
                result.data[i + 3] = (value ushr 24) / 255f
            }
            return result
        }

        fun flood(width: Int, height: Int, color: Color, linear: Boolean): SvgFilterPixels {
            val result = SvgFilterPixels(width, height, linear)
            val alpha = color.opacity.toFloat()
            val red = ((if (linear) transfer(color.red, true) else color.red) * alpha).toFloat()
            val green = ((if (linear) transfer(color.green, true) else color.green) * alpha).toFloat()
            val blue = ((if (linear) transfer(color.blue, true) else color.blue) * alpha).toFloat()
            for (i in result.data.indices step 4) {
                result.data[i] = red; result.data[i + 1] = green
                result.data[i + 2] = blue; result.data[i + 3] = alpha
            }
            return result
        }

        private fun componentCount(width: Int, height: Int): Int {
            require(width >= 0 && height >= 0 && width.toLong() * height <= Int.MAX_VALUE / 4) {
                "Invalid or oversized filter pixel dimensions: ${width}x$height"
            }
            return width * height * 4
        }

        private fun transfer(value: Double, toLinear: Boolean): Double = if (toLinear) {
            if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        } else {
            if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055
        }

        private fun kernelRadius(sigma: Double): Int {
            val radius = ceil(3.0 * sigma)
            require(radius <= MAX_BLUR_RADIUS) { "Gaussian filter exceeds the $MAX_BLUR_RADIUS-pixel kernel radius limit" }
            return radius.toInt()
        }

        private fun kernel(sigma: Double, radius: Int): DoubleArray {
            val result = DoubleArray(radius * 2 + 1) {
                val distance = (it - radius).toDouble() / sigma
                exp(-0.5 * distance * distance)
            }
            val sum = result.sum()
            for (i in result.indices) result[i] /= sum
            return result
        }
    }
}
