package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.*

class SvgFilterMorphologyTest {
    @Test fun dilationUsesARectangleWithIndependentRadii() {
        val input = SvgFilterPixels(9, 9, false)
        input.data[(4 * 9 + 4) * 4 + 3] = .75f
        val result = input.morphology(2.0, 1.0, "dilate")
        for (y in 0..8) for (x in 0..8) {
            assertEquals(if (x in 2..6 && y in 3..5) .75f else 0f, result.data[(y * 9 + x) * 4 + 3])
        }
        assertTrue(result.data.indices.all { it % 4 == 3 || result.data[it] == 0f })
        assertEquals(.75f, input.data.sum())
    }

    @Test fun erosionUsesTransparentBordersRatherThanDuplicateOrWrap() {
        val input = SvgFilterPixels(7, 5, true).apply { data.fill(.5f) }
        val result = input.morphology(2.0, 1.0)
        for (y in 0..4) for (x in 0..6) for (channel in 0..3) {
            assertEquals(if (x in 2..4 && y in 1..3) .5f else 0f, result.data[(y * 7 + x) * 4 + channel])
        }
        assertTrue(input.data.all { it == .5f })
        assertTrue(result.linear)
        assertTrue(input.morphology(8.0, 8.0, "erode").data.all { it == 0f })
        assertTrue(input.morphology(8.0, 8.0, "dilate").data.all { it == .5f })
    }

    @Test fun extremaArePerPremultipliedComponentNotPerPixelOrStraightColor() {
        val input = SvgFilterPixels(3, 3, false)
        for (i in input.data.indices step 4) {
            input.data[i] = .1f; input.data[i + 1] = .2f
            input.data[i + 2] = .3f; input.data[i + 3] = .8f
        }
        input.data[0] = .5f; input.data[1] = .05f; input.data[2] = .1f; input.data[3] = .5f
        input.data[4] = .2f; input.data[5] = .6f; input.data[6] = .1f; input.data[7] = .7f
        val center = (1 * 3 + 1) * 4
        assertContentEquals(floatArrayOf(.5f, .6f, .3f, .8f),
            input.morphology(1.0, 1.0, "dilate").data.copyOfRange(center, center + 4))
        assertContentEquals(floatArrayOf(.1f, .05f, .1f, .5f),
            input.morphology(1.0, 1.0, "erode").data.copyOfRange(center, center + 4))
    }

    @Test fun separableDequeMatchesIndependentTwoDimensionalReference() {
        val random = Random(7302)
        for (alphaOnly in listOf(false, true)) for (sparse in listOf(false, true)) repeat(5) {
            val input = SvgFilterPixels(random.nextInt(1, 15), random.nextInt(1, 13), random.nextBoolean())
            for (i in input.data.indices step 4) {
                if (sparse && random.nextBoolean()) continue
                val alpha = random.nextFloat()
                input.data[i + 3] = alpha
                if (!alphaOnly) for (channel in 0..2) input.data[i + channel] = random.nextFloat() * alpha
            }
            val original = input.data.copyOf()
            for ((rx, ry) in listOf(.2 to .8, .5 to 2.0, 2.0 to .5, 1.0 to 1.0,
                2.9 to 3.1, 20.0 to 20.0, 256.0 to 2.0)) for (operator in listOf("dilate", "erode")) {
                val result = input.morphology(rx, ry, operator)
                assertContentEquals(reference(input, rx, ry, operator == "dilate"), result.data,
                    "${input.width}x${input.height}, alphaOnly=$alphaOnly sparse=$sparse r=$rx,$ry $operator")
                assertEquals(input.linear, result.linear)
                for (i in result.data.indices step 4) for (channel in 0..2) {
                    assertTrue(result.data[i + channel] <= result.data[i + 3])
                }
                assertContentEquals(original, input.data)
            }
        }
    }

    @Test fun positiveFractionalRadiiIncludeOnlyPixelCentersInsideTheKernel() {
        val input = SvgFilterPixels(5, 5, false)
        input.data[(2 * 5 + 2) * 4 + 3] = 1f
        assertSame(input, input.morphology(.99, .99, "dilate"))
        val horizontal = input.morphology(1.99, .99, "dilate")
        assertEquals(3f, horizontal.data.sum())
        assertEquals(1f, horizontal.data[(2 * 5 + 1) * 4 + 3])
        assertEquals(0f, horizontal.data[(1 * 5 + 2) * 4 + 3])
    }

    @Test fun nonpositiveRadiusDisablesTheWholePrimitive() {
        val input = SvgFilterPixels(5, 5, false).apply { data[3] = 1f }
        for ((rx, ry) in listOf(0.0 to 0.0, 0.0 to 2.0, 2.0 to 0.0, -1.0 to 2.0, 2.0 to -1.0)) {
            assertSame(input, input.morphology(rx, ry, "dilate"))
            assertEquals(0L, input.morphologyWork(rx, ry))
        }
    }

    @Test fun alphaOnlyPlanningIsFreshAndLinearInImageSizeNotKernelArea() {
        val input = SvgFilterPixels(10, 8, false)
        input.data[3] = 1f
        assertEquals(10L * 8 * 2 * 6, input.morphologyWork(1.0, 1.0))
        assertEquals(input.morphologyWork(1.0, 1.0), input.morphologyWork(256.0, 256.0))
        assertEquals(10L * 8 * 6, input.morphologyWork(.5, 5.0))
        input.data[0] = .2f
        assertEquals(10L * 8 * 2 * 4 * 6, input.morphologyWork(1.0, 1.0))
        input.clip(4, 4, 4, 4)
        assertEquals(0L, input.morphologyWork(1.0, 1.0))
        assertSame(input, input.morphology(1.0, 1.0))
    }

    @Test fun malformedOperatorsNonfiniteOrExcessiveRadiiAreRejectedEvenForEmptyPixels() {
        val input = SvgFilterPixels(0, 0, false)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { input.morphology(value, 0.0) }
            assertFailsWith<IllegalArgumentException> { input.morphologyWork(1.0, value) }
        }
        for (value in listOf(256.01, 257.0, Double.MAX_VALUE)) {
            assertTrue(assertFailsWith<IllegalArgumentException> { input.morphology(value, 1.0) }
                .message!!.contains("kernel radius"))
            assertFailsWith<IllegalArgumentException> { input.morphologyWork(1.0, value) }
        }
        assertFailsWith<IllegalArgumentException> { input.morphology(0.0, 0.0, "maximum") }
    }

    @Test fun morphologyRejectsExcessiveWorkBeforeAllocatingPassOutputs() {
        val input = SvgFilterPixels(1800, 1800, false).apply { data.fill(1f) }
        assertTrue(input.morphologyWork(1.0, 1.0) > 150_000_000L)
        assertTrue(assertFailsWith<IllegalArgumentException> { input.morphology(1.0, 1.0, "dilate") }
            .message!!.contains("work limit"))
    }

    private fun reference(input: SvgFilterPixels, radiusX: Double, radiusY: Double, dilate: Boolean): FloatArray {
        val rx = floor(radiusX).toInt()
        val ry = floor(radiusY).toInt()
        val result = FloatArray(input.data.size)
        for (y in 0 until input.height) for (x in 0 until input.width) for (channel in 0..3) {
            var value = if (dilate) 0f else 1f
            // Restricting iteration to actual pixels is exact for dilation; erosion gets zero
            // as soon as its rectangle includes an off-image sample.
            if (!dilate && (x < rx || x + rx >= input.width || y < ry || y + ry >= input.height)) value = 0f
            else for (sy in max(0, y - ry)..min(input.height - 1, y + ry)) {
                for (sx in max(0, x - rx)..min(input.width - 1, x + rx)) {
                    val sample = input.data[(sy * input.width + sx) * 4 + channel]
                    value = if (dilate) max(value, sample) else min(value, sample)
                }
            }
            result[(y * input.width + x) * 4 + channel] = value
        }
        return result
    }
}
