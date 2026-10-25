package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgFilterTurbulenceTest {
    // Independently evaluated from the W3C Filter Effects reference, including its unit-circle
    // gradient rejection. These values deliberately distinguish it from the old SVG 1.1 RNG.
    // https://www.w3.org/TR/filter-effects-1/#feTurbulenceElement
    @Test fun seededFractalNoiseMatchesTheReferenceRgbaAndPremultiplication() {
        val result = sample(seed = 7.0, fractal = true)
        assertRgba(doubleArrayOf(0.2880929010490691, 0.22168599610369805,
            0.17777215721125345, 0.5246263561587656), result)
    }

    @Test fun turbulenceUsesAbsoluteNoiseAtEveryOctave() {
        val result = sample(seed = 7.0, fractal = false)
        assertRgba(doubleArrayOf(0.03194567747842539, 0.03303344747223445,
            0.09178905551712271, 0.21328352516662882), result)
    }

    @Test fun asymmetricFrequencyAndNegativeFractionalSeedMatchTheReference() {
        val result = SvgFilterTurbulence.render(1, 1, false, 0.13, 0.27,
            numOctaves = 4, seed = -7.9, fractalNoise = true, originX = -3.125, originY = 1.75)
        assertRgba(doubleArrayOf(0.13711964706612076, 0.24947116740361047,
            0.18565489361919907, 0.4660098892222414), result)
    }

    @Test fun stitchedFrequencyAdjustmentAndNonzeroTileOriginMatchTheReference() {
        val result = SvgFilterTurbulence.render(1, 1, false, 0.08, 0.21,
            numOctaves = 2, seed = 1.0, originX = 9.0, originY = 7.0,
            stitchTiles = true, tileX = 3.0, tileY = 5.0, tileWidth = 19.0, tileHeight = 13.0)
        assertRgba(doubleArrayOf(0.06332505886881225, 0.10428294156591641,
            0.0033930133887225124, 0.21575975805477818), result)
        val fractionalTile = SvgFilterTurbulence.render(1, 1, false, 0.11, 0.17,
            numOctaves = 5, seed = 42.0, fractalNoise = true, originX = 15.25, originY = 17.75,
            stitchTiles = true, tileX = 3.25, tileY = 5.75, tileWidth = 19.0, tileHeight = 13.0)
        assertRgba(doubleArrayOf(0.49499372036121625, 0.4851138191880227,
            0.4447950301816627, 0.775525563494183), fractionalTile)
    }

    @Test fun zeroOctavesAndZeroFrequencyHaveDifferentFractalAndTurbulenceResults() {
        for (octaves in listOf(0, 3)) for (fractal in listOf(false, true)) {
            val result = SvgFilterTurbulence.render(3, 2, false, 0.0,
                numOctaves = octaves, fractalNoise = fractal)
            for (i in result.data.indices step 4) {
                assertEquals(if (fractal) 0.25f else 0f, result.data[i])
                assertEquals(if (fractal) 0.25f else 0f, result.data[i + 1])
                assertEquals(if (fractal) 0.25f else 0f, result.data[i + 2])
                assertEquals(if (fractal) 0.5f else 0f, result.data[i + 3])
            }
        }
        assertRgba(doubleArrayOf(0.25, 0.25, 0.25, 0.5),
            SvgFilterTurbulence.render(1, 1, false, 0.123, numOctaves = 0, fractalNoise = true))
        assertRgba(doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            SvgFilterTurbulence.render(1, 1, false, 0.123, numOctaves = 0))
    }

    @Test fun aZeroFrequencyDisablesOnlyThatAxis() {
        val result = SvgFilterTurbulence.render(7, 9, false, 0.0, 0.15,
            numOctaves = 3, seed = 7.0, fractalNoise = true, originY = 0.5)
        for (y in 0 until result.height) for (x in 1 until result.width) for (channel in 0..3) {
            assertEquals(result.data[y * result.width * 4 + channel],
                result.data[(y * result.width + x) * 4 + channel])
        }
        assertTrue((0..3).any { result.data[it] != result.data[result.width * 4 + it] })
    }

    @Test fun seedTruncationNormalizationAndClampingAreDeterministic() {
        assertContentEquals(sample(7.0).data, sample(7.99).data)
        assertContentEquals(sample(8.0).data, sample(-7.99).data)
        assertContentEquals(sample(1.0).data, sample(0.0).data)
        assertContentEquals(sample(1.0).data, sample(-0.99).data)
        assertContentEquals(sample(1.0).data, sample(-2147483646.0).data)
        assertContentEquals(sample(2147483646.0).data, sample(1e100).data)
        assertFalse(sample(7.0).data.contentEquals(sample(8.0).data))
    }

    @Test fun generatedNoiseIsAlreadyInTheRequestedWorkingColorSpace() {
        val srgb = sample(7.0, linear = false)
        val linear = sample(7.0, linear = true)
        assertFalse(srgb.linear)
        assertTrue(linear.linear)
        assertContentEquals(srgb.data, linear.data)
        // The same numeric colour in linear RGB is visibly brighter when converted to sRGB.
        val displayedLinear = linear.converted(false)
        assertTrue(displayedLinear.data[0] > srgb.data[0])
        assertEquals(linear.data[3], displayedLinear.data[3])
    }

    @Test fun originAndRasterStepsPreserveUserAndObjectBoundingBoxCoordinates() {
        val whole = SvgFilterTurbulence.render(9, 7, true, 0.035,
            numOctaves = 3, seed = 7.0, fractalNoise = true,
            originX = -4.75, originY = 12.125, stepX = 0.5, stepY = 0.25)
        val crop = SvgFilterTurbulence.render(3, 2, true, 0.035,
            numOctaves = 3, seed = 7.0, fractalNoise = true,
            originX = -3.75, originY = 12.875, stepX = 0.5, stepY = 0.25)
        for (y in 0..1) for (x in 0..2) for (c in 0..3) {
            assertEquals(whole.data[((y + 3) * whole.width + x + 2) * 4 + c],
                crop.data[(y * crop.width + x) * 4 + c], 1e-7f)
        }
        // Scale frequency inversely to the primitive coordinate system, not to raster size.
        val normalized = SvgFilterTurbulence.render(9, 7, true, 7.0, 3.5,
            numOctaves = 3, seed = 7.0, fractalNoise = true,
            originX = -4.75 / 200, originY = 12.125 / 100,
            stepX = 0.5 / 200, stepY = 0.25 / 100)
        for (i in whole.data.indices) assertEquals(whole.data[i], normalized.data[i], 1e-7f)
    }

    @Test fun stitchesJoinAtBothTileEdgesAcrossAllOctaves() {
        for (fractal in listOf(false, true)) {
            val result = SvgFilterTurbulence.render(20, 14, false, 0.11, 0.17,
                numOctaves = 5, seed = 42.0, fractalNoise = fractal,
                originX = 3.25, originY = 5.75, stitchTiles = true,
                tileX = 3.25, tileY = 5.75, tileWidth = 19.0, tileHeight = 13.0)
            for (y in 0 until result.height) for (c in 0..3) {
                assertEquals(result.data[y * 20 * 4 + c], result.data[(y * 20 + 19) * 4 + c], 1e-6f)
            }
            for (x in 0 until result.width) for (c in 0..3) {
                assertEquals(result.data[x * 4 + c], result.data[(13 * 20 + x) * 4 + c], 1e-6f)
            }
        }
    }

    @Test fun stitchFrequencyBelowOnePeriodNeverDividesByZero() {
        val result = SvgFilterTurbulence.render(12, 8, false, 0.001, 0.0,
            numOctaves = 4, seed = 7.0, fractalNoise = true, stitchTiles = true,
            tileWidth = 11.0, tileHeight = 7.0)
        assertTrue(result.data.all { it.isFinite() && it in 0f..1f })
        for (y in 0 until 8) for (c in 0..3) {
            assertEquals(result.data[y * 12 * 4 + c], result.data[(y * 12 + 11) * 4 + c], 1e-6f)
        }
    }

    @Test fun channelsRemainBoundedPremultipliedAndIndependent() {
        for (fractal in listOf(false, true)) {
            val result = SvgFilterTurbulence.render(24, 17, true, 0.31, 0.17,
                numOctaves = 8, seed = 95.0, fractalNoise = fractal, originX = -123.75, originY = -41.25)
            assertTrue(result.data.all { it.isFinite() && it in 0f..1f })
            for (i in result.data.indices step 4) for (c in 0..2) {
                assertTrue(result.data[i + c] <= result.data[i + 3])
            }
            assertTrue((result.data.indices step 4).any { result.data[it] != result.data[it + 1] })
        }
    }

    @Test fun invalidValuesAndExcessiveWorkFailBeforeAllocating() {
        for (frequency in listOf(-0.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(1, 1, false, frequency) }
        }
        assertFailsWith<IllegalArgumentException> { sample(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(1, 1, false, 0.1, numOctaves = -1) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(1, 1, false, 0.1, numOctaves = 17) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(4097, 1, false, 0.1) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(2048, 2048, false, 0.1) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(4000, 1000, false, 0.1, numOctaves = 3) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(1, 1, false, 1e300, originX = 2.0) }
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.render(1, 1, false, 0.1, stepX = 0.0) }
        assertFailsWith<IllegalArgumentException> {
            SvgFilterTurbulence.render(1, 1, false, 0.1, stitchTiles = true, tileWidth = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            SvgFilterTurbulence.render(1, 1, false, 0.1, originX = Double.NEGATIVE_INFINITY)
        }
    }

    @Test fun workEstimateIncludesAllRgbaCornersAndEmptyRastersDoNotAllocatePixels() {
        assertEquals(12L * (4 + 16 * 3), SvgFilterTurbulence.work(3, 4, 3))
        assertEquals(12L * 4, SvgFilterTurbulence.work(3, 4, 0))
        assertEquals(0L, SvgFilterTurbulence.work(0, 4, 3))
        assertTrue(SvgFilterTurbulence.render(0, 4, false, 0.1).data.isEmpty())
        assertFailsWith<IllegalArgumentException> { SvgFilterTurbulence.work(Int.MAX_VALUE, Int.MAX_VALUE, 1) }
    }

    private fun sample(seed: Double, fractal: Boolean = true, linear: Boolean = false) =
        SvgFilterTurbulence.render(1, 1, linear, 0.035, numOctaves = 3,
            seed = seed, fractalNoise = fractal, originX = 13.25, originY = 27.75)

    private fun assertRgba(expected: DoubleArray, actual: SvgFilterPixels) {
        assertEquals(expected.size, actual.data.size)
        expected.indices.forEach { assertEquals(expected[it], actual.data[it].toDouble(), 1e-7, "channel $it") }
    }
}
