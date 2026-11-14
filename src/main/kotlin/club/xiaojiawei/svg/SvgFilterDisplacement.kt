package club.xiaojiawei.svg

import kotlin.math.floor

private const val MAX_DISPLACEMENT_COMPONENT_TAPS = 150_000_000L

/** Conservative bilinear source component taps; map conversion and traversal are separate passes. */
internal fun SvgFilterPixels.displacementWork(scaleX: Double, scaleY: Double): Long {
    require(scaleX.isFinite() && scaleY.isFinite()) { "Non-finite feDisplacementMap scale" }
    return if (scaleX == 0.0 && scaleY == 0.0) 0L else width.toLong() * height * 16
}

/**
 * Samples this image at (x + scaleX * (mapX - .5), y + scaleY * (mapY - .5)).
 * Map RGB is unpremultiplied; source samples and bilinear interpolation stay premultiplied.
 * Undefined samples are transparent black. The SVG specification leaves the interpolation
 * method open, so bilinear interpolation is used consistently with [SvgFilterPixels.offset].
 *
 * Crucially, color-interpolation-filters applies ONLY to [map]. The source and returned image
 * retain this image's color space: https://www.w3.org/TR/filter-effects-1/#feDisplacementMapElement .
 * [scaleX] and [scaleY] are already resolved into raster pixels by the caller.
 */
internal fun SvgFilterPixels.displace(
    map: SvgFilterPixels,
    scaleX: Double,
    scaleY: Double,
    xChannel: String = "A",
    yChannel: String = "A",
    mapLinear: Boolean = linear
): SvgFilterPixels {
    require(width == map.width && height == map.height) { "Displacement inputs have different dimensions" }
    val xc = displacementChannel(xChannel)
    val yc = displacementChannel(yChannel)
    val work = displacementWork(scaleX, scaleY)
    require(work <= MAX_DISPLACEMENT_COMPONENT_TAPS) {
        "Displacement filter exceeds the $MAX_DISPLACEMENT_COMPONENT_TAPS pixel-component tap work limit"
    }
    if (work == 0L) return this
    val displacement = map.converted(mapLinear).data
    val result = SvgFilterPixels(width, height, linear)
    for (y in 0 until height) for (x in 0 until width) {
        val out = (y * width + x) * 4
        val alpha = displacement[out + 3]
        fun channel(index: Int): Double = if (index == 3) alpha.toDouble().coerceIn(0.0, 1.0)
            else if (alpha > 0f) (displacement[out + index].toDouble() / alpha).coerceIn(0.0, 1.0)
            else 0.0
        val sx = x + scaleX * (channel(xc) - .5)
        val sy = y + scaleY * (channel(yc) - .5)
        // Check in floating point before converting to Int, including huge finite scales.
        if (sx <= -1.0 || sx >= width || sy <= -1.0 || sy >= height) continue
        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val fx = sx - x0
        val fy = sy - y0
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
    return result
}

private fun displacementChannel(channel: String): Int = when (channel) {
    "R" -> 0
    "G" -> 1
    "B" -> 2
    "A" -> 3
    else -> throw IllegalArgumentException("Unsupported feDisplacementMap channel selector: $channel")
}
