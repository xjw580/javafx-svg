package club.xiaojiawei.svg

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The implicit kernelUnitLength grid. It is anchored at the source pixel footprint's top-left,
 * not at a device pixel center. Fractional last cells extend past the footprint and use duplicate
 * edge sampling. The exact requested spacing is retained (never stretched to fit rounded sizes).
 * SVG permits bilinear interpolation for both resampling passes.
 */
internal class SvgFilterGridPlan(
    val width: Int,
    val height: Int,
    val scaleX: Double,
    val scaleY: Double
) {
    val identity: Boolean = scaleX == 1.0 && scaleY == 1.0
    val gridWidth: Int
    val gridHeight: Int
    val gridPixels: Long
    /** Both resampled input and filtered grid coexist with the original-size output. */
    val extraPixels: Long
    val resampleWork: Long

    init {
        require(width >= 0 && height >= 0) { "Invalid filter grid size" }
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0.0 && scaleY > 0.0) {
            "Filter kernel grid spacing must be finite and positive"
        }
        fun dimension(size: Int, scale: Double): Int {
            if (size == 0) return 0
            val extent = ceil(size / scale)
            require(extent.isFinite() && extent in 1.0..4096.0) {
                "SVG filter kernel grid exceeds 4096 pixels per axis"
            }
            return extent.toInt()
        }
        gridWidth = dimension(width, scaleX)
        gridHeight = dimension(height, scaleY)
        gridPixels = gridWidth.toLong() * gridHeight
        extraPixels = if (identity) 0 else gridPixels * 2
        resampleWork = if (identity) 0 else 16L * (gridPixels + width.toLong() * height)
    }

    fun input(source: SvgFilterPixels, minX: Int = 0, minY: Int = 0,
        maxX: Int = width - 1, maxY: Int = height - 1): SvgFilterPixels {
        require(source.width == width && source.height == height) { "Wrong source grid size" }
        if (identity) return source
        require(minX in 0..width && minY in 0..height && maxX in -1 until width && maxY in -1 until height &&
            minX <= maxX + 1 && minY <= maxY + 1) { "Invalid filter sample bounds" }
        return resample(source, gridWidth, gridHeight, scaleX, scaleY, minX, minY, maxX, maxY)
    }

    /** Convolution extends the actual input image before sampling its implicit grid. */
    fun extendedInput(source: SvgFilterPixels, minX: Int, minY: Int, maxX: Int, maxY: Int, edgeMode: String): SvgFilterPixels {
        require(source.width == width && source.height == height)
        require(minX in 0..maxX && maxX <= width && minY in 0..maxY && maxY <= height)
        require(edgeMode in setOf("duplicate", "wrap", "none"))
        if (identity) return source
        val result = SvgFilterPixels(gridWidth, gridHeight, source.linear)
        if (minX == maxX || minY == maxY) return result
        fun coordinate(v: Double, lo: Int, hi: Int): Int? = when (edgeMode) {
            "duplicate" -> v.coerceIn(lo.toDouble(), (hi - 1).toDouble()).toInt()
            "wrap" -> lo + (((v - lo) % (hi - lo) + (hi - lo)) % (hi - lo)).toInt()
            else -> if (v >= lo && v < hi) v.toInt() else null
        }
        for (y in 0 until gridHeight) for (x in 0 until gridWidth) {
            val px = (x + .5) * scaleX - .5; val py = (y + .5) * scaleY - .5
            val x0 = floor(px); val y0 = floor(py); val fx = px - x0; val fy = py - y0
            val out = (y * gridWidth + x) * 4
            for (iy in 0..1) for (ix in 0..1) {
                val xx = coordinate(x0 + ix, minX, maxX) ?: continue
                val yy = coordinate(y0 + iy, minY, maxY) ?: continue
                val weight = (if (ix == 0) 1 - fx else fx) * (if (iy == 0) 1 - fy else fy)
                val sourceIndex = (yy * width + xx) * 4
                for (c in 0..3) result.data[out + c] += (source.data[sourceIndex + c] * weight).toFloat()
            }
        }
        return result
    }

    fun output(filtered: SvgFilterPixels): SvgFilterPixels {
        require(filtered.width == gridWidth && filtered.height == gridHeight) { "Wrong filtered grid size" }
        if (identity) return filtered
        return resample(filtered, width, height, 1.0 / scaleX, 1.0 / scaleY)
    }

    private fun resample(source: SvgFilterPixels, w: Int, h: Int, sx: Double, sy: Double,
        minX: Int = 0, minY: Int = 0, maxX: Int = source.width - 1, maxY: Int = source.height - 1): SvgFilterPixels {
        val result = SvgFilterPixels(w, h, source.linear)
        if (minX > maxX || minY > maxY) return result
        for (y in 0 until h) {
            val py = ((y + .5) * sy - .5).coerceIn(minY.toDouble(), maxY.toDouble())
            val y0 = floor(py).toInt(); val y1 = min(y0 + 1, maxY)
            val fy = py - y0
            for (x in 0 until w) {
                val px = ((x + .5) * sx - .5).coerceIn(minX.toDouble(), maxX.toDouble())
                val x0 = floor(px).toInt(); val x1 = min(x0 + 1, maxX)
                val fx = px - x0
                val a = (y0 * source.width + x0) * 4; val b = (y0 * source.width + x1) * 4
                val c = (y1 * source.width + x0) * 4; val d = (y1 * source.width + x1) * 4
                val out = (y * w + x) * 4
                for (channel in 0..3) {
                    result.data[out + channel] = ((source.data[a + channel] * (1 - fx) + source.data[b + channel] * fx) * (1 - fy) +
                        (source.data[c + channel] * (1 - fx) + source.data[d + channel] * fx) * fy).toFloat()
                }
            }
        }
        return result
    }
}
