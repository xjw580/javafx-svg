package club.xiaojiawei.svg

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Seeded Perlin noise from the W3C Filter Effects reference algorithm:
 * https://www.w3.org/TR/filter-effects-1/#feTurbulenceElement
 *
 * In particular this uses the current reference's rejection of zero/outside-unit-circle
 * gradients, not the different random sequence in the older SVG 1.1 reference.
 * All coordinates are in primitiveUnits. [render] receives the coordinate of the first
 * raster sample, not the raster corner; the caller supplies any pixel-centre offset and
 * maps userSpaceOnUse/objectBoundingBox coordinates before calling it.
 */
internal object SvgFilterTurbulence {
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 4_000_000L
    private const val MAX_OCTAVES = 16
    private const val MAX_COMPONENT_TAPS = 150_000_000L
    // Preserve subpixel precision and keep lattice arithmetic exact even at the last octave.
    private const val MAX_LATTICE_COORDINATE = 1_099_511_627_776.0 // 2^40
    private const val RANDOM_MODULUS = 2_147_483_647L
    private const val PERLIN_OFFSET = 4096L

    /** Four gradient corners for each of four channels at each octave, plus output storage. */
    fun work(width: Int, height: Int, numOctaves: Int = 1): Long {
        require(width in 0..MAX_DIMENSION && height in 0..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS) {
            "feTurbulence exceeds the $MAX_DIMENSION-axis/$MAX_PIXELS-pixel raster limit"
        }
        require(numOctaves in 0..MAX_OCTAVES) {
            "feTurbulence numOctaves must be between 0 and $MAX_OCTAVES"
        }
        return width.toLong() * height * (4L + 16L * numOctaves)
    }

    /**
     * Generates straight RGBA in the chosen working colour space, then premultiplies RGB.
     * No sRGB transfer is applied to generated noise: it already belongs to [linear]'s space.
     * [tileX]/[tileY]/[tileWidth]/[tileHeight] describe the primitive subregion, not the
     * rounded raster or the intersection with the overall filter region.
     */
    fun render(
        width: Int,
        height: Int,
        linear: Boolean,
        baseFrequencyX: Double,
        baseFrequencyY: Double = baseFrequencyX,
        numOctaves: Int = 1,
        seed: Double = 0.0,
        fractalNoise: Boolean = false,
        originX: Double = 0.0,
        originY: Double = 0.0,
        stepX: Double = 1.0,
        stepY: Double = 1.0,
        stitchTiles: Boolean = false,
        tileX: Double = originX,
        tileY: Double = originY,
        tileWidth: Double = width * stepX,
        tileHeight: Double = height * stepY
    ): SvgFilterPixels {
        require(work(width, height, numOctaves) <= MAX_COMPONENT_TAPS) {
            "feTurbulence exceeds the $MAX_COMPONENT_TAPS pixel-component tap work limit"
        }
        require(baseFrequencyX.isFinite() && baseFrequencyY.isFinite() &&
            baseFrequencyX >= 0.0 && baseFrequencyY >= 0.0) {
            "feTurbulence baseFrequency must be finite and non-negative"
        }
        require(seed.isFinite()) { "Non-finite feTurbulence seed" }
        require(originX.isFinite() && originY.isFinite() && stepX.isFinite() &&
            stepY.isFinite() && stepX > 0.0 && stepY > 0.0) {
            "Invalid feTurbulence raster coordinates"
        }
        if (stitchTiles) {
            require(tileX.isFinite() && tileY.isFinite() && tileWidth.isFinite() &&
                tileHeight.isFinite() && tileWidth > 0.0 && tileHeight > 0.0) {
                "feTurbulence stitching needs a finite, positive tile region"
            }
        }

        val frequencyX = if (stitchTiles) stitchedFrequency(baseFrequencyX, tileWidth) else baseFrequencyX
        val frequencyY = if (stitchTiles) stitchedFrequency(baseFrequencyY, tileHeight) else baseFrequencyY
        val lastX = originX + max(0, width - 1) * stepX
        val lastY = originY + max(0, height - 1) * stepY
        val multiplier = (1L shl max(0, numOctaves - 1)).toDouble()
        checkCoordinates(originX, lastX, frequencyX, multiplier)
        checkCoordinates(originY, lastY, frequencyY, multiplier)
        val stitches = if (stitchTiles) {
            checkCoordinates(tileX, tileX + tileWidth, frequencyX, multiplier)
            checkCoordinates(tileY, tileY + tileHeight, frequencyY, multiplier)
            val latticeWidth = floor(tileWidth * frequencyX + 0.5).toLong()
            val latticeHeight = floor(tileHeight * frequencyY + 0.5).toLong()
            var stitch = Stitch(latticeWidth, latticeHeight,
                floor(tileX * frequencyX + PERLIN_OFFSET + latticeWidth).toLong(),
                floor(tileY * frequencyY + PERLIN_OFFSET + latticeHeight).toLong())
            Array(numOctaves) {
                stitch.also {
                    stitch = Stitch(it.width * 2, it.height * 2,
                        it.wrapX * 2 - PERLIN_OFFSET, it.wrapY * 2 - PERLIN_OFFSET)
                }
            }
        } else null

        val result = SvgFilterPixels(width, height, linear)
        if (width == 0 || height == 0) return result
        if (numOctaves == 0 || (frequencyX == 0.0 && frequencyY == 0.0)) {
            if (fractalNoise) for (i in result.data.indices step 4) {
                result.data[i] = 0.25f; result.data[i + 1] = 0.25f
                result.data[i + 2] = 0.25f; result.data[i + 3] = 0.5f
            }
            return result
        }

        val noise = Noise(seed)
        val sums = DoubleArray(4)
        for (y in 0 until height) {
            val sampleY = (originY + y * stepY) * frequencyY
            for (x in 0 until width) {
                sums.fill(0.0)
                var nx = (originX + x * stepX) * frequencyX
                var ny = sampleY
                var amplitude = 1.0
                for (octave in 0 until numOctaves) {
                    noise.accumulate(nx, ny, stitches?.get(octave), amplitude, fractalNoise, sums)
                    nx *= 2.0; ny *= 2.0; amplitude *= 0.5
                }
                for (channel in 0..3) {
                    sums[channel] = (if (fractalNoise) (sums[channel] + 1.0) * 0.5
                        else sums[channel]).coerceIn(0.0, 1.0)
                }
                val out = (y * width + x) * 4
                for (channel in 0..2) result.data[out + channel] = (sums[channel] * sums[3]).toFloat()
                result.data[out + 3] = sums[3].toFloat()
            }
        }
        return result
    }

    private fun checkCoordinates(start: Double, end: Double, frequency: Double, multiplier: Double) {
        require(start.isFinite() && end.isFinite() && frequency.isFinite() &&
            max(abs(start), abs(end)) * frequency * multiplier <= MAX_LATTICE_COORDINATE) {
            "feTurbulence coordinates/frequency exceed the supported lattice range"
        }
    }

    private fun stitchedFrequency(frequency: Double, extent: Double): Double {
        if (frequency == 0.0) return 0.0
        val periods = extent * frequency
        require(periods.isFinite()) { "feTurbulence tile frequency overflow" }
        val lower = floor(periods) / extent
        val upper = ceil(periods) / extent
        // lower == 0 gives an infinite relative change, so choose the upper frequency.
        return if (lower > 0.0 && frequency / lower < upper / frequency) lower else upper
    }

    private data class Stitch(val width: Long, val height: Long, val wrapX: Long, val wrapY: Long)

    private class Noise(seed: Double) {
        private val permutation = IntArray(514)
        private val gradients = Array(4) { DoubleArray(1028) }

        init {
            // Do the remainder before conversion, avoiding overflow for large finite seeds.
            val integerSeed = if (seed < 0.0) ceil(seed) else floor(seed)
            var state = if (integerSeed <= 0.0) (-(integerSeed % (RANDOM_MODULUS - 1)) + 1).toLong()
                else integerSeed.coerceAtMost((RANDOM_MODULUS - 1).toDouble()).toLong()
            var draws = 0
            fun random(): Long {
                require(++draws <= 65_536) { "feTurbulence gradient initialization exceeded its work limit" }
                state = state * 16_807 % RANDOM_MODULUS
                return state
            }
            for (i in 0 until 256) permutation[i] = i
            for (channel in 0..3) for (i in 0 until 256) {
                var gx: Double
                var gy: Double
                var norm: Double
                do {
                    gx = (random() % 512 - 256) / 256.0
                    gy = (random() % 512 - 256) / 256.0
                    norm = sqrt(gx * gx + gy * gy)
                } while (norm == 0.0 || norm > 1.0)
                gradients[channel][i * 2] = gx / norm
                gradients[channel][i * 2 + 1] = gy / norm
            }
            for (i in 255 downTo 1) {
                val j = (random() % 256).toInt()
                val previous = permutation[i]
                permutation[i] = permutation[j]
                permutation[j] = previous
            }
            for (i in 0 until 258) {
                permutation[256 + i] = permutation[i]
                for (channel in 0..3) {
                    gradients[channel][(256 + i) * 2] = gradients[channel][i * 2]
                    gradients[channel][(256 + i) * 2 + 1] = gradients[channel][i * 2 + 1]
                }
            }
        }

        fun accumulate(x: Double, y: Double, stitch: Stitch?, amplitude: Double,
                       fractalNoise: Boolean, sums: DoubleArray) {
            val tx = x + PERLIN_OFFSET
            val ty = y + PERLIN_OFFSET
            // Floor also extends the reference's positive-offset lattice to negative coordinates.
            var x0 = floor(tx).toLong(); var x1 = x0 + 1
            var y0 = floor(ty).toLong(); var y1 = y0 + 1
            val rx = tx - x0; val ry = ty - y0
            if (stitch != null) {
                if (x0 >= stitch.wrapX) x0 -= stitch.width
                if (x1 >= stitch.wrapX) x1 -= stitch.width
                if (y0 >= stitch.wrapY) y0 -= stitch.height
                if (y1 >= stitch.wrapY) y1 -= stitch.height
            }
            val ix0 = permutation[(x0 and 255L).toInt()]
            val ix1 = permutation[(x1 and 255L).toInt()]
            val iy0 = (y0 and 255L).toInt(); val iy1 = (y1 and 255L).toInt()
            val b00 = permutation[ix0 + iy0] * 2; val b10 = permutation[ix1 + iy0] * 2
            val b01 = permutation[ix0 + iy1] * 2; val b11 = permutation[ix1 + iy1] * 2
            val sx = rx * rx * (3.0 - 2.0 * rx); val sy = ry * ry * (3.0 - 2.0 * ry)
            for (channel in 0..3) {
                val gradient = gradients[channel]
                val u0 = rx * gradient[b00] + ry * gradient[b00 + 1]
                val v0 = (rx - 1.0) * gradient[b10] + ry * gradient[b10 + 1]
                val u1 = rx * gradient[b01] + (ry - 1.0) * gradient[b01 + 1]
                val v1 = (rx - 1.0) * gradient[b11] + (ry - 1.0) * gradient[b11 + 1]
                val upper = u0 + sx * (v0 - u0)
                val lower = u1 + sx * (v1 - u1)
                val value = upper + sy * (lower - upper)
                sums[channel] += (if (fractalNoise) value else abs(value)) * amplitude
            }
        }
    }
}
