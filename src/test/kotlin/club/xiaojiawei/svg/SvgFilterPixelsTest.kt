package club.xiaojiawei.svg

import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.test.*

class SvgFilterPixelsTest {
    @Test fun porterDuffOperatorsUsePremultipliedRgbaAndTheCorrectInputOrder() {
        val source = pixel(.4f, .1f, .2f, .5f)
        val destination = pixel(.1f, .3f, .2f, .4f)
        val expected = mapOf(
            "over" to floatArrayOf(.45f, .25f, .3f, .7f),
            "in" to floatArrayOf(.16f, .04f, .08f, .2f),
            "out" to floatArrayOf(.24f, .06f, .12f, .3f),
            "atop" to floatArrayOf(.21f, .19f, .18f, .4f),
            "xor" to floatArrayOf(.29f, .21f, .22f, .5f),
            "lighter" to floatArrayOf(.5f, .4f, .4f, .9f)
        )
        for ((operator, rgba) in expected) assertPixels(rgba, source.composite(destination, operator), operator)
        assertPixels(floatArrayOf(.4f, .1f, .2f, .5f), source)
        assertPixels(floatArrayOf(.1f, .3f, .2f, .4f), destination)
    }

    @Test fun lighterClampsColorAndOpacityInsteadOfApplyingSourceOver() {
        val result = pixel(.7f, .2f, .1f, .8f).composite(pixel(.6f, .4f, .5f, .7f), "lighter")
        assertPixels(floatArrayOf(1f, .6f, .6f, 1f), result)
    }

    @Test fun arithmeticUsesEveryCoefficientOnEveryPremultipliedChannel() {
        val result = pixel(.4f, .1f, .2f, .5f).composite(
            pixel(.1f, .3f, .2f, .4f), "arithmetic", k1 = 2.0, k2 = .25, k3 = .5, k4 = .05)
        assertPixels(floatArrayOf(.28f, .285f, .28f, .775f), result)
    }

    @Test fun arithmeticK4CreatesPixelsOutsideBothInputs() {
        val input = SvgFilterPixels(3, 2, false)
        val result = input.composite(input, "arithmetic", k4 = .25)
        assertTrue(result.data.all { it == .25f })
        assertTrue(input.data.all { it == 0f })
    }

    @Test fun arithmeticClampsAlphaThenClampsPremultipliedColorToAlpha() {
        val source = pixel(.1f, .5f, .4f, .5f)
        val result = source.composite(pixel(0f, 0f, 0f, 0f), "arithmetic", k2 = -1.0, k4 = .75)
        assertPixels(floatArrayOf(.25f, .25f, .25f, .25f), result)
        assertPixels(floatArrayOf(0f, 0f, 0f, 0f), source.composite(source, "arithmetic", k4 = -2.0))
        assertPixels(floatArrayOf(1f, 1f, 1f, 1f), source.composite(source, "arithmetic", k4 = 2.0))
    }

    @Test fun colorConversionUnpremultipliesBeforeTheTransferFunctionAndPreservesAlpha() {
        val input = pixel(.25f, .125f, .02f, .5f)
        val linear = input.converted(true)
        assertTrue(linear.linear)
        assertPixels(floatArrayOf(.10702057f, .02543804f, .00154799f, .5f), linear)
        assertPixels(input.data, linear.converted(false))
        assertSame(input, input.converted(false))
        assertPixels(floatArrayOf(0f, 0f, 0f, 0f), pixel(0f, 0f, 0f, 0f).converted(true))
    }

    @Test fun floodConvertsSrgbColorAndMultipliesOpacity() {
        val result = SvgFilterPixels.flood(2, 1, Color.color(.5, .25, .04, .5), true)
        assertPixels(floatArrayOf(.10702057f, .02543804f, .00154799f, .5f,
            .10702057f, .02543804f, .00154799f, .5f), result)
        assertPixels(floatArrayOf(.25f, .125f, .02f, .5f),
            SvgFilterPixels.flood(1, 1, Color.color(.5, .25, .04, .5), false))
    }

    @Test fun compositeConvertsDestinationToTheSourceWorkingSpace() {
        val input = SvgFilterPixels.flood(1, 1, Color.color(.5, 0.0, 0.0), false)
        val transparentLinear = SvgFilterPixels(1, 1, true)
        assertPixels(floatArrayOf(.21404114f, 0f, 0f, 1f), transparentLinear.composite(input, "over"))
    }

    @Test fun sourceAlphaIsBlackWithoutMutatingTheOriginal() {
        val input = pixel(.2f, .3f, .4f, .5f)
        assertPixels(floatArrayOf(0f, 0f, 0f, .5f), input.alpha())
        assertPixels(floatArrayOf(.2f, .3f, .4f, .5f), input)
    }

    @Test fun offsetUsesBilinearSamplesWithTransparentBorders() {
        val input = SvgFilterPixels(3, 3, false)
        input.data[0] = 1f; input.data[3] = 1f
        val result = input.offset(.5, .5)
        for (y in 0..2) for (x in 0..2) {
            val expected = if (x < 2 && y < 2) .25f else 0f
            assertEquals(expected, result.data[(y * 3 + x) * 4], 1e-6f)
            assertEquals(expected, result.data[(y * 3 + x) * 4 + 3], 1e-6f)
        }
        val backwards = input.offset(-.5, -.5)
        assertEquals(.25f, backwards.data[3], 1e-6f)
        assertEquals(.25f, backwards.data.filterIndexed { i, _ -> i % 4 == 3 }.sum(), 1e-6f)
        assertTrue(input.offset(1e100, -1e100).data.all { it == 0f })
        assertSame(input, input.offset(0.0, 0.0))
    }

    @Test fun integerOffsetDoesNotResampleOrWrapRows() {
        val input = SvgFilterPixels(3, 2, false)
        input.data[0] = .5f; input.data[3] = .5f
        val shifted = input.offset(2.0, 1.0)
        assertEquals(.5f, shifted.data[20])
        assertEquals(.5f, shifted.data[23])
        assertEquals(1f, shifted.data.sum())
    }

    @Test fun gaussianKernelIsNormalizedSymmetricAndIndependentPerAxis() {
        val input = SvgFilterPixels(9, 9, false)
        input.data[(4 * 9 + 4) * 4] = .5f
        input.data[(4 * 9 + 4) * 4 + 3] = 1f
        val horizontal = input.blur(1.0, 0.0)
        val normalizer = (-3..3).sumOf { exp(-it * it / 2.0) }
        for (x in 0..8) {
            val expected = if (x in 1..7) exp(-(x - 4) * (x - 4) / 2.0) / normalizer else 0.0
            assertEquals(expected, horizontal.data[(4 * 9 + x) * 4 + 3].toDouble(), 1e-7)
        }
        assertEquals(0f, horizontal.data[(3 * 9 + 4) * 4 + 3])
        assertEquals(1f, horizontal.data.filterIndexed { i, _ -> i % 4 == 3 }.sum(), 1e-6f)
        val vertical = input.blur(0.0, 1.0)
        for (y in 0..8) for (x in 0..8) {
            assertEquals(horizontal.data[(x * 9 + y) * 4 + 3], vertical.data[(y * 9 + x) * 4 + 3])
        }
        val both = input.blur(1.0, 1.0)
        assertEquals(1.0 / (normalizer * normalizer), both.data[(4 * 9 + 4) * 4 + 3].toDouble(), 1e-7)
        assertEquals(1f, both.data.filterIndexed { i, _ -> i % 4 == 3 }.sum(), 1e-6f)
        assertEquals(both.data[(4 * 9 + 4) * 4 + 3] / 2, both.data[(4 * 9 + 4) * 4])
    }

    @Test fun blurDoesNotRenormalizeTransparentEdgesAndHandlesTinyDeviations() {
        val input = pixel(1f, .5f, 0f, 1f)
        val weight = 1 / (-3..3).sumOf { exp(-it * it / 2.0) }
        assertEquals(weight, input.blur(1.0, 0.0).data[3].toDouble(), 1e-7)
        assertPixels(input.data, input.blur(1e-300, 0.0))
        assertSame(input, input.blur(0.0, 0.0))
        assertSame(input, input.blur(-1.0, 10.0))
        assertSame(input, input.blur(10.0, -1.0))
    }

    @Test fun blurRejectsOversizedKernelOrWorkInsteadOfSilentlyClamping() {
        assertTrue(assertFailsWith<IllegalArgumentException> { pixel(0f, 0f, 0f, 0f).blur(86.0, 0.0) }
            .message!!.contains("kernel radius"))
        assertTrue(assertFailsWith<IllegalArgumentException> { SvgFilterPixels(300, 300, false).blur(80.0, 80.0) }
            .message!!.contains("work limit"))
        assertFailsWith<IllegalArgumentException> { pixel(0f, 0f, 0f, 0f).blur(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { pixel(0f, 0f, 0f, 0f).offset(Double.POSITIVE_INFINITY, 0.0) }
    }

    @Test fun clipUsesExclusiveEdgesAndClearsEmptyOrOffCanvasRegions() {
        val input = SvgFilterPixels.flood(3, 3, Color.WHITE, false)
        assertSame(input, input.clip(1, 1, 3, 2))
        for (y in 0..2) for (x in 0..2) assertEquals(if (y == 1 && x >= 1) 1f else 0f,
            input.data[(y * 3 + x) * 4 + 3])
        assertTrue(input.clip(-10, -10, -1, -1).data.all { it == 0f })
        assertTrue(SvgFilterPixels.flood(2, 2, Color.WHITE, false).clip(2, 2, 1, 1).data.all { it == 0f })
    }

    @Test fun writableImageRoundTripUsesPremultipliedSrgbPixels() {
        val image = WritableImage(2, 1)
        val argb = intArrayOf(0x80402010.toInt(), 0)
        image.pixelWriter.setPixels(0, 0, 2, 1, PixelFormat.getIntArgbPreInstance(), argb, 0, 2)
        val pixels = SvgFilterPixels.fromImage(image)
        assertPixels(floatArrayOf(64 / 255f, 32 / 255f, 16 / 255f, 128 / 255f, 0f, 0f, 0f, 0f), pixels)
        val roundTrip = pixels.converted(true).toImage()
        val output = IntArray(2)
        roundTrip.pixelReader.getPixels(0, 0, 2, 1, PixelFormat.getIntArgbPreInstance(), output, 0, 2)
        assertContentEquals(argb, output)
    }

    @Test fun invalidDimensionsDataAndCompositeInputsAreDiagnosed() {
        assertFailsWith<IllegalArgumentException> { SvgFilterPixels(-1, 1, false) }
        assertFailsWith<IllegalArgumentException> { SvgFilterPixels(1 shl 30, 8, false) }
        assertFailsWith<IllegalArgumentException> { SvgFilterPixels(1, 1, false, FloatArray(3)) }
        val input = pixel(0f, 0f, 0f, 0f)
        assertFailsWith<IllegalArgumentException> { input.composite(SvgFilterPixels(2, 1, false), "over") }
        assertFailsWith<IllegalArgumentException> { input.composite(input, "multiply") }
        assertFailsWith<IllegalArgumentException> { input.composite(input, "arithmetic", k1 = Double.NaN) }
    }

    private fun pixel(red: Float, green: Float, blue: Float, alpha: Float): SvgFilterPixels =
        SvgFilterPixels(1, 1, false, floatArrayOf(red, green, blue, alpha))

    private fun assertPixels(expected: FloatArray, actual: SvgFilterPixels, message: String = "") {
        assertEquals(expected.size, actual.data.size, message)
        for (i in expected.indices) assertEquals(expected[i], actual.data[i], 1e-6f, "$message, component $i")
    }
}
