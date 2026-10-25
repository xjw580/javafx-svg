package club.xiaojiawei.svg

import kotlin.math.*

/** SVG convolution parameters. Validation happens before allocating any raster. */
internal data class SvgConvolveParameters(
    val orderX: Int, val orderY: Int, val kernel: DoubleArray, val divisor: Double,
    val bias: Double, val targetX: Int, val targetY: Int, val edgeMode: String,
    val preserveAlpha: Boolean, val kernelX: Double?, val kernelY: Double?
) {
    val passthrough get() = kernel.size != orderX * orderY
    companion object {
        fun parse(e: SvgElement): SvgConvolveParameters {
            val orders = SvgNumbers.list(e["order"] ?: "3")
            require(orders.size in 1..2) { "feConvolveMatrix order needs one or two values" }
            fun order(v: Double): Int {
                require(v >= 1 && v < 4097) { "feConvolveMatrix order must be positive and at most 4096" }
                return v.toInt()
            }
            val ox = order(orders[0]); val oy = order(orders.getOrElse(1) { orders[0] })
            require(ox.toLong() * oy <= 65536) { "feConvolveMatrix exceeds 65536 kernel cells" }
            val kernel = e["kernelMatrix"]?.let(SvgNumbers::list)?.toDoubleArray() ?: doubleArrayOf()
            val sum = kernel.sum()
            require(sum.isFinite()) { "feConvolveMatrix kernel sum exceeds numeric range" }
            val defaultDivisor = if (sum == 0.0) 1.0 else sum
            val divisor = e["divisor"]?.let(SvgNumbers::scalar)?.takeIf { it != 0.0 } ?: defaultDivisor
            fun target(name: String, size: Int): Int {
                val v = e[name]?.let(SvgNumbers::scalar) ?: (size / 2).toDouble()
                require(v >= 0 && v < size && v == floor(v)) { "Invalid feConvolveMatrix $name" }
                return v.toInt()
            }
            val edge = e["edgeMode"] ?: "duplicate"
            require(edge in setOf("duplicate", "wrap", "none")) { "Invalid feConvolveMatrix edgeMode" }
            val preserve = e["preserveAlpha"] ?: "false"
            require(preserve in setOf("true", "false")) { "Invalid feConvolveMatrix preserveAlpha" }
            val spacing = e["kernelUnitLength"]?.let(SvgNumbers::list)
            require(spacing == null || spacing.size in 1..2) { "feConvolveMatrix kernelUnitLength needs one or two values" }
            val valid = spacing?.takeIf { it.all { v -> v > 0.0 } }
            return SvgConvolveParameters(ox, oy, kernel, divisor, SvgNumbers.scalar(e["bias"] ?: "0"),
                target("targetX", ox), target("targetY", oy), edge, preserve == "true",
                valid?.get(0), valid?.let { values -> values.getOrElse(1) { values[0] } })
        }
    }
}

/** SVG rotates kernel indices 180 degrees. Components remain premultiplied unless preserveAlpha. */
internal fun SvgFilterPixels.convolveMatrix(p: SvgConvolveParameters,
    minX: Int = 0, minY: Int = 0, maxX: Int = width, maxY: Int = height): SvgFilterPixels {
    if (p.passthrough) return this
    require(convolveWork(p) <= 200_000_000.0) { "feConvolveMatrix exceeds the 200000000 operation work limit" }
    require(minX in 0..maxX && maxX <= width && minY in 0..maxY && maxY <= height) { "Invalid convolution input bounds" }
    val result = SvgFilterPixels(width, height, linear)
    fun coordinate(v: Int, lo: Int, hi: Int): Int? {
        if (lo == hi) return null
        return when (p.edgeMode) {
            "duplicate" -> v.coerceIn(lo, hi - 1)
            "wrap" -> lo + Math.floorMod(v - lo, hi - lo)
            else -> v.takeIf { it in lo until hi }
        }
    }
    for (y in 0 until height) for (x in 0 until width) {
        val out = (y * width + x) * 4
        val sums = DoubleArray(4)
        for (i in 0 until p.orderY) for (j in 0 until p.orderX) {
            val xx = coordinate(x - p.targetX + j, minX, maxX) ?: continue
            val yy = coordinate(y - p.targetY + i, minY, maxY) ?: continue
            val index = (yy * width + xx) * 4
            val weight = p.kernel[(p.orderY - i - 1) * p.orderX + p.orderX - j - 1]
            val a = data[index + 3].toDouble()
            for (c in 0..3) {
                val v = if (p.preserveAlpha && c < 3) {
                    if (a > 0.0) data[index + c] / a else 0.0
                } else data[index + c].toDouble()
                sums[c] += v * weight
            }
        }
        require(sums.all { it.isFinite() }) { "feConvolveMatrix accumulation exceeds numeric range" }
        val alpha = if (p.preserveAlpha) data[out + 3].toDouble() else (sums[3] / p.divisor + p.bias).coerceIn(0.0, 1.0)
        result.data[out + 3] = alpha.toFloat()
        for (c in 0..2) {
            val value = if (p.preserveAlpha) (sums[c] / p.divisor + p.bias).coerceIn(0.0, 1.0) * alpha
                else (sums[c] / p.divisor + p.bias * alpha).coerceIn(0.0, alpha)
            require(!value.isNaN()) { "feConvolveMatrix result exceeds numeric range" }
            result.data[out + c] = value.toFloat()
        }
    }
    return result
}

internal fun SvgFilterPixels.convolveWork(p: SvgConvolveParameters): Double =
    if (p.passthrough) 0.0 else width.toDouble() * height * (p.orderX.toDouble() * p.orderY * 12.0 + 24.0)
