package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.*

class SvgFilterDisplacementTest {
    @Test fun neutralUnpremultipliedMapPreservesEveryPixel() {
        val input = patterned(7, 5)
        val map = solid(7, 5, .125f, .125f, .125f, .25f)
        val original = input.data.copyOf()
        val mapOriginal = map.data.copyOf()
        for (channel in listOf("R", "G", "B")) {
            assertPixels(input.data, input.displace(map, 17.0, -23.0, channel, channel, false))
        }
        val alphaMap = solid(7, 5, 0f, 0f, 0f, .5f)
        assertPixels(input.data, input.displace(alphaMap, 17.0, -23.0))
        assertContentEquals(original, input.data)
        assertContentEquals(mapOriginal, map.data)
    }

    @Test fun positiveMapDisplacementSamplesToTheRightRatherThanMovingPixelsRight() {
        val input = patterned(5, 3)
        val map = solid(5, 3, 1f, .5f, 0f, 1f)
        val result = input.displace(map, 2.0, 2.0, "R", "G")
        for (y in 0..2) for (x in 0..4) for (channel in 0..3) {
            val expected = if (x < 4) input.data[(y * 5 + x + 1) * 4 + channel] else 0f
            assertEquals(expected, result.data[(y * 5 + x) * 4 + channel])
        }
        assertPixels(input.offset(1.0, 0.0).data, input.displace(map, -2.0, 2.0, "R", "G"))
    }

    @Test fun eachRgbaSelectorControlsEachAxisIndependently() {
        val input = patterned(7, 7)
        // Straight RGBA is (1, .5, 0, .25), not (.25, .125, 0, .25).
        val map = solid(7, 7, .25f, .125f, 0f, .25f)
        val expected = mapOf("R" to 2.0, "G" to 0.0, "B" to -2.0, "A" to -1.0)
        for ((channel, delta) in expected) {
            assertPixels(input.offset(-delta, 0.0).data, input.displace(map, 4.0, 0.0, channel, "A"))
            assertPixels(input.offset(0.0, -delta).data, input.displace(map, 0.0, 4.0, "A", channel))
        }
        assertPixels(input.offset(1.0, 1.0).data, input.displace(map, 4.0, 4.0))
    }

    @Test fun bilinearInterpolationKeepsTheSourcePremultipliedAndBordersTransparent() {
        val input = SvgFilterPixels(3, 3, false)
        input.data[0] = .25f; input.data[2] = .125f; input.data[3] = .5f
        val map = solid(3, 3, 0f, 0f, 0f, 1f)
        val result = input.displace(map, 1.0, 1.0, "R", "G")
        for (y in 0..2) for (x in 0..2) {
            val factor = if (x <= 1 && y <= 1) .25f else 0f
            assertEquals(.25f * factor, result.data[(y * 3 + x) * 4])
            assertEquals(.125f * factor, result.data[(y * 3 + x) * 4 + 2])
            assertEquals(.5f * factor, result.data[(y * 3 + x) * 4 + 3])
        }
        val reverse = input.displace(map, -1.0, -1.0, "R", "G")
        assertEquals(.125f, reverse.data[3])
        assertEquals(.125f, reverse.data.filterIndexed { index, _ -> index % 4 == 3 }.sum())
    }

    @Test fun transparentDisplacementMapUsesZeroRatherThanNeutralOrHiddenRgb() {
        val input = patterned(5, 5)
        val map = SvgFilterPixels(5, 5, false)
        assertPixels(input.offset(1.0, 2.0).data, input.displace(map, 2.0, 4.0, "R", "G"))
        assertPixels(input.offset(1.0, 2.0).data, input.displace(map, 2.0, 4.0, "A", "A"))
    }

    @Test fun interpolationColorSpaceAppliesOnlyToTheMapNeverTheSource() {
        val input = patterned(5, 5)
        val map = solid(5, 5, .25f, .25f, .25f, .5f)
        val srgb = input.displace(map, 2.0, 2.0, "R", "G", false)
        assertFalse(srgb.linear)
        assertPixels(input.data, srgb)
        val linearMap = input.displace(map, 2.0, 2.0, "R", "G", true)
        assertFalse(linearMap.linear)
        assertPixels(input.displace(map.converted(true), 2.0, 2.0, "R", "G", true).data, linearMap)
        val delta = 2.0 * (.21404114048223255 - .5)
        assertPixels(input.offset(-delta, -delta).data, linearMap)
        assertFalse(input.data.contentEquals(linearMap.data))
        val linearInput = input.converted(true)
        val result = linearInput.displace(map, 2.0, 2.0, "R", "G", false)
        assertTrue(result.linear)
        assertPixels(linearInput.data, result)
    }

    @Test fun randomMapMatchesIndependentBilinearReferenceAndDoesNotMutateInputs() {
        val random = Random(18841)
        repeat(10) {
            val input = patterned(random.nextInt(1, 9), random.nextInt(1, 9))
            val map = SvgFilterPixels(input.width, input.height, false)
            for (i in map.data.indices step 4) {
                val alpha = if (random.nextInt(4) == 0) 0f else random.nextFloat()
                for (channel in 0..2) map.data[i + channel] = random.nextFloat() * alpha
                map.data[i + 3] = alpha
            }
            val original = input.data.copyOf()
            val mapOriginal = map.data.copyOf()
            for (xc in 0..3) for (yc in 0..3) {
                val scaleX = random.nextDouble(-12.0, 12.0)
                val scaleY = random.nextDouble(-12.0, 12.0)
                assertPixels(reference(input, map, scaleX, scaleY, xc, yc),
                    input.displace(map, scaleX, scaleY, "RGBA"[xc].toString(), "RGBA"[yc].toString(), false))
            }
            assertContentEquals(original, input.data)
            assertContentEquals(mapOriginal, map.data)
        }
    }

    @Test fun zeroScaleIsIdentityAndHugeFiniteScaleDoesNotOverflowCoordinates() {
        val input = patterned(3, 3)
        val map = solid(3, 3, .5f, .5f, .5f, 1f)
        assertSame(input, input.displace(map, 0.0, 0.0, mapLinear = true))
        assertEquals(0L, input.displacementWork(0.0, 0.0))
        assertPixels(input.data, input.displace(map, Double.MAX_VALUE, -Double.MAX_VALUE, "R", "G"))
        assertTrue(input.displace(map, Double.MAX_VALUE, Double.MAX_VALUE, "A", "A").data.all { it == 0f })
        assertEquals(3L * 3 * 16, input.displacementWork(1.0, -5.0))
        assertEquals(input.displacementWork(1.0, 1.0), input.displacementWork(Double.MAX_VALUE, Double.MAX_VALUE))
    }

    @Test fun invalidDimensionsChannelsAndNonfiniteScalesAreDiagnosed() {
        val input = patterned(2, 3)
        assertFailsWith<IllegalArgumentException> { input.displace(SvgFilterPixels(3, 2, false), 0.0, 0.0) }
        for (channel in listOf("r", "red", "", "Q")) {
            assertFailsWith<IllegalArgumentException> { input.displace(input, 0.0, 0.0, channel, "A") }
            assertFailsWith<IllegalArgumentException> { input.displace(input, 0.0, 0.0, "A", channel) }
        }
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { input.displace(input, value, 0.0) }
            assertFailsWith<IllegalArgumentException> { input.displacementWork(0.0, value) }
        }
        val empty = SvgFilterPixels(0, 5, false)
        assertSame(empty, empty.displace(empty, 20.0, -20.0))
    }

    private fun patterned(width: Int, height: Int): SvgFilterPixels {
        val result = SvgFilterPixels(width, height, false)
        for (y in 0 until height) for (x in 0 until width) {
            val index = (y * width + x) * 4
            val alpha = ((x + y) % 4 + 1) / 4f
            result.data[index] = (x + 1f) / (width + 1) * alpha
            result.data[index + 1] = (y + 1f) / (height + 1) * alpha
            result.data[index + 2] = .2f * alpha
            result.data[index + 3] = alpha
        }
        return result
    }

    private fun solid(width: Int, height: Int, r: Float, g: Float, b: Float, a: Float): SvgFilterPixels =
        SvgFilterPixels(width, height, false).also { result ->
            for (i in result.data.indices step 4) {
                result.data[i] = r; result.data[i + 1] = g
                result.data[i + 2] = b; result.data[i + 3] = a
            }
        }

    private fun reference(input: SvgFilterPixels, map: SvgFilterPixels, sx: Double, sy: Double, xc: Int, yc: Int): FloatArray {
        val output = FloatArray(input.data.size)
        for (y in 0 until input.height) for (x in 0 until input.width) {
            val index = (y * input.width + x) * 4
            fun straight(channel: Int): Double = when {
                channel == 3 -> map.data[index + 3].toDouble()
                map.data[index + 3] == 0f -> 0.0
                else -> map.data[index + channel].toDouble() / map.data[index + 3]
            }
            val px = x + sx * (straight(xc) - .5)
            val py = y + sy * (straight(yc) - .5)
            val x0 = floor(px).toInt(); val y0 = floor(py).toInt()
            val dx = px - x0; val dy = py - y0
            fun sample(px: Int, py: Int, channel: Int): Double =
                if (px in 0 until input.width && py in 0 until input.height)
                    input.data[(py * input.width + px) * 4 + channel].toDouble() else 0.0
            for (channel in 0..3) output[index + channel] = (
                sample(x0, y0, channel) * (1 - dx) * (1 - dy) +
                    sample(x0 + 1, y0, channel) * dx * (1 - dy) +
                    sample(x0, y0 + 1, channel) * (1 - dx) * dy +
                    sample(x0 + 1, y0 + 1, channel) * dx * dy).toFloat()
        }
        return output
    }

    private fun assertPixels(expected: FloatArray, actual: SvgFilterPixels) {
        assertEquals(expected.size, actual.data.size)
        for (i in expected.indices) assertEquals(expected[i], actual.data[i], 1e-6f, "component $i")
    }
}
