package club.xiaojiawei.svg

import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class SvgFilterLightingTest {
    private val overhead = SvgLightingLight.Distant(0.0, 90.0)
    private fun heightMap(w: Int, h: Int, alpha: (Int, Int) -> Float = { _, _ -> 0f }) =
        SvgFilterPixels(w, h, false).also { p ->
            for (y in 0 until h) for (x in 0 until w) p.data[(y * w + x) * 4 + 3] = alpha(x, y)
        }
    private fun render(p: SvgFilterPixels, specular: Boolean = false, scale: Double = 1.0,
        constant: Double = 1.0, exponent: Double = 1.0, color: Color = Color.WHITE,
        linear: Boolean = false, light: SvgLightingLight = overhead,
        ox: Double = 0.0, oy: Double = 0.0, sx: Double = 1.0, sy: Double = 1.0,
        kx: Double = sx, ky: Double = sy) =
        p.lighting(specular, scale, constant, exponent, color, linear, light, ox, oy, sx, sy, kx, ky)
    private fun assertPixel(p: SvgFilterPixels, x: Int, y: Int, r: Double, g: Double = r,
        b: Double = r, a: Double = 1.0, tolerance: Double = 2e-6) {
        for ((c, expected) in listOf(r, g, b, a).withIndex())
            assertEquals(expected, p.data[(y * p.width + x) * 4 + c].toDouble(), tolerance, "($x,$y) channel $c")
    }

    @Test fun missingLightProducesTransparentBlackForBothPrimitives() {
        val p=heightMap(3,2) { _, _ -> 1f }
        for (specular in listOf(false,true)) assertTrue(render(p,specular=specular,light=SvgLightingLight.None).data.all { it == 0f })
    }
    @Test fun diffuseIsOpaqueEvenOnCompletelyTransparentInput() {
        val output = render(heightMap(4, 3), color = Color.color(.2, .4, .7, .1))
        for (y in 0..2) for (x in 0..3) assertPixel(output, x, y, .2, .4, .7)
    }
    @Test fun flatConstantAlphaHasNoInventedEdgeBevel() {
        for (alpha in listOf(0f, .25f, 1f)) {
            val output = render(heightMap(4, 3) { _, _ -> alpha }, scale = 50.0,
                light = SvgLightingLight.Distant(10.0, 30.0))
            for (y in 0..2) for (x in 0..3) assertPixel(output, x, y, .5)
        }
    }
    @Test fun specularUsesHalfVectorAndMaxChannelAlphaWithoutDoublePremultiplication() {
        val output = render(heightMap(3, 3), specular = true, exponent = 4.0,
            constant = .7, color = Color.color(.3, .6, .9), light = SvgLightingLight.Distant(0.0, 0.0))
        val strength = .7 * .25
        assertPixel(output, 1, 1, .3 * strength, .6 * strength, .9 * strength, .9 * strength)
    }
    @Test fun lightingColorIsConvertedToRequestedSpaceButInputRgbIsIgnored() {
        val p = heightMap(4, 3) { _, _ -> .5f }
        val expected = render(p, linear = true, color = Color.gray(.5))
        for (i in p.data.indices step 4) { p.data[i] = .5f; p.data[i + 1] = .2f; p.data[i + 2] = .4f }
        val original = p.data.copyOf()
        assertContentEquals(expected.data, render(p, linear = true, color = Color.gray(.5)).data)
        assertContentEquals(original, p.data)
        assertTrue(expected.linear)
        assertPixel(expected, 1, 1, .21404114048223255)
        assertPixel(render(p, color = Color.gray(.5)), 1, 1, .5)
        assertContentEquals(expected.data, render(p.converted(true), linear = true, color = Color.gray(.5)).data)
    }
    @Test fun allNineSobelLocationsMatchThePublishedKernels() {
        val random = Random(518)
        val p = heightMap(3, 3) { _, _ -> random.nextFloat() }
        val kx = arrayOf(
            intArrayOf(0,0,0, 0,-2,2, 0,-1,1), intArrayOf(0,0,0, -2,0,2, -1,0,1), intArrayOf(0,0,0, -2,2,0, -1,1,0),
            intArrayOf(0,-1,1, 0,-2,2, 0,-1,1), intArrayOf(-1,0,1, -2,0,2, -1,0,1), intArrayOf(-1,1,0, -2,2,0, -1,1,0),
            intArrayOf(0,-1,1, 0,-2,2, 0,0,0), intArrayOf(-1,0,1, -2,0,2, 0,0,0), intArrayOf(-1,1,0, -2,2,0, 0,0,0))
        val ky = arrayOf(
            intArrayOf(0,0,0, 0,-2,-1, 0,2,1), intArrayOf(0,0,0, -1,-2,-1, 1,2,1), intArrayOf(0,0,0, -1,-2,0, 1,2,0),
            intArrayOf(0,-2,-1, 0,0,0, 0,2,1), intArrayOf(-1,-2,-1, 0,0,0, 1,2,1), intArrayOf(-1,-2,0, 0,0,0, 1,2,0),
            intArrayOf(0,-2,-1, 0,2,1, 0,0,0), intArrayOf(-1,-2,-1, 1,2,1, 0,0,0), intArrayOf(-1,-2,0, 1,2,0, 0,0,0))
        val fx = doubleArrayOf(2.0/3, 1.0/3, 2.0/3, .5, .25, .5, 2.0/3, 1.0/3, 2.0/3)
        val fy = doubleArrayOf(2.0/3, .5, 2.0/3, 1.0/3, .25, 1.0/3, 2.0/3, .5, 2.0/3)
        val output = render(p, scale = 1.3, sx = .7, sy = 1.1, light = SvgLightingLight.Distant(37.0, 48.0))
        for (y in 0..2) for (x in 0..2) {
            var gx = 0.0; var gy = 0.0
            val index = y * 3 + x
            for (dy in -1..1) for (dx in -1..1) {
                if (x + dx !in 0..2 || y + dy !in 0..2) continue
                val a = p.data[((y + dy) * 3 + x + dx) * 4 + 3]
                gx += a * kx[index][(dy + 1) * 3 + dx + 1]
                gy += a * ky[index][(dy + 1) * 3 + dx + 1]
            }
            val nx = -1.3 * fx[index] * gx / .7; val ny = -1.3 * fy[index] * gy / 1.1
            val length = sqrt(nx * nx + ny * ny + 1)
            val az = Math.toRadians(37.0); val el = Math.toRadians(48.0)
            val expected = ((nx*cos(az)*cos(el) + ny*sin(az)*cos(el) + sin(el))/length).coerceAtLeast(0.0)
            assertPixel(output, x, y, expected)
        }
    }
    @Test fun negativeSurfaceScaleReversesSlopeAndAnisotropicStepsMatter() {
        val p = heightMap(5, 5) { x, y -> (x * .1 + y * .05).toFloat() }
        val light = SvgLightingLight.Distant(0.0, 45.0)
        val positive = render(p, scale = 2.0, light = light, sx = 2.0, sy = .5)
        val negative = render(p, scale = -2.0, light = light, sx = 2.0, sy = .5)
        val norm = sqrt(.2*.2 + .4*.4 + 1)
        assertPixel(positive, 2, 2, (1-.2)/sqrt(2.0)/norm)
        assertPixel(negative, 2, 2, (1+.2)/sqrt(2.0)/norm)
    }
    @Test fun explicitFractionalKernelSpacingUsesBilinearAlphaSamples() {
        val p = heightMap(7, 7) { x, y -> (x * .05 + y * .03).toFloat() }
        val actual = render(p, scale = 3.0, kx = 1.5, ky = .75)
        assertPixel(actual, 3, 3, 1 / sqrt(.3*.3 + .18*.18 + 1))
        // Very large spacing is bounded work, and a dimension without neighbours has zero slope.
        assertPixel(render(p, kx = 100.0, ky = 100.0), 3, 3, 1.0)
    }
    @Test fun oneAndTwoPixelDimensionsAreFiniteAndPreserveFlatMaps() {
        for (w in 1..2) for (h in 1..2) {
            val result = render(heightMap(w,h) { _, _ -> .5f }, scale = 100.0)
            for (y in 0 until h) for (x in 0 until w) assertPixel(result,x,y,1.0)
        }
        val vertical = render(heightMap(1,3) { _, y -> y * .1f })
        for (y in 0..2) assertPixel(vertical,0,y,1/sqrt(1.04))
    }
    @Test fun pointPositionIncludesPixelOriginStepAndSurfaceHeight() {
        val p = heightMap(3, 3) { _, _ -> .5f }
        val output = render(p, scale = 4.0, ox = 10.0, oy = 20.0, sx = 2.0, sy = 3.0,
            light = SvgLightingLight.Point(12.0,23.0,12.0))
        assertPixel(output,1,1,1.0)
        assertPixel(output,0,0,10.0/sqrt(113.0))
    }
    @Test fun pointLightingHasNoDistanceAttenuation() {
        val p = heightMap(1, 1)
        assertContentEquals(render(p, light = SvgLightingLight.Point(3.0,4.0,5.0)).data,
            render(p, light = SvgLightingLight.Point(30.0,40.0,50.0)).data)
    }
    @Test fun spotAttenuationAndConeAreIndependentOfMaterialExponent() {
        val p = heightMap(3,1)
        val point = render(p, light = SvgLightingLight.Point(0.0,0.0,2.0))
        val spot = render(p, light = SvgLightingLight.Spot(0.0,0.0,2.0, exponent = 2.0))
        assertPixel(spot,1,0,point.data[4] * .8)
        val cone = render(p, light = SvgLightingLight.Spot(0.0,0.0,2.0,coneAngle = 30.0))
        assertPixel(cone,0,0,1.0); assertPixel(cone,2,0,0.0)
        val spec = render(p, specular = true, exponent = 4.0,
            light = SvgLightingLight.Spot(0.0,0.0,2.0, exponent = 2.0))
        val lz = 2.0/sqrt(5.0)
        val expected = ((1+lz)/2).pow(2) * .8
        assertPixel(spec,1,0,expected,a=expected)
    }
    @Test fun spotConeBoundaryUsesFractionalTwoByTwoCoverage() {
        val p = heightMap(5,1)
        val angle = Math.toDegrees(atan(.5))
        val light = SvgLightingLight.Spot(0.0,0.0,2.0,exponent=1.0,coneAngle=angle)
        val result = render(p,light=light)
        val centerLambert = 2.0/sqrt(5.0)
        val coveredFocus = 2.0/sqrt(.75*.75 + .25*.25 + 4.0)
        assertPixel(result,1,0,centerLambert * coveredFocus * .5)
        assertPixel(result,4,0,0.0)
        val specular = render(p,specular=true,light=light)
        val halfZ = sqrt((1+centerLambert)/2)
        val expected = halfZ * coveredFocus * .5
        assertPixel(specular,1,0,expected,a=expected)
        assertEquals(5L*320,p.lightingWork(light))
        assertEquals(5L*160,p.lightingWork(SvgLightingLight.Spot(0.0,0.0,2.0)))
    }
    @Test fun coneSubpixelOverflowIsRejectedBeforeShading() {
        val p = heightMap(1,1)
        val light = SvgLightingLight.Spot(0.0,0.0,1.0,coneAngle=30.0)
        assertFailsWith<IllegalArgumentException> { render(p,ox=1.7e308,sx=1.7e308,light=light) }
        assertFailsWith<IllegalArgumentException> { render(p,oy=-1.7e308,sy=1.7e308,light=light) }
    }
    @Test fun undefinedCenterLightCannotGainSpecularResponseFromConeSamples() {
        val p=heightMap(1,1)
        val light=SvgLightingLight.Spot(0.0,0.0,0.0,1.0,0.0,0.0,exponent=0.0,coneAngle=80.0)
        assertPixel(render(p,specular=true,light=light),0,0,0.0,a=0.0)
        assertPixel(render(p,light=light),0,0,0.0)
    }
    @Test fun lightsBehindSurfaceAndDegenerateLightsDoNotGenerateNaNs() {
        val p = heightMap(1,1)
        for (light in listOf(SvgLightingLight.Point(), SvgLightingLight.Point(0.0,0.0,-1.0),
            SvgLightingLight.Spot(0.0,0.0,1.0,0.0,0.0,1.0),
            SvgLightingLight.Spot(0.0,0.0,1.0,0.0,0.0,2.0))) {
            assertPixel(render(p, light = light),0,0,0.0)
            assertPixel(render(p, specular=true,light=light),0,0,0.0,a=0.0)
        }
    }
    @Test fun zeroConstantAndBlackLightStayFiniteWithHugeConstants() {
        val p = heightMap(1,1)
        assertPixel(render(p,constant=0.0),0,0,0.0)
        assertPixel(render(p,specular=true,constant=0.0),0,0,0.0,a=0.0)
        assertPixel(render(p,constant=Double.MAX_VALUE,color=Color.BLACK),0,0,0.0)
        assertPixel(render(p,specular=true,constant=Double.MAX_VALUE,color=Color.RED),0,0,1.0,0.0,0.0,1.0)
    }
    @Test fun hugeFiniteCoordinatesSurfaceScaleAndAnglesRemainBounded() {
        val p = heightMap(3,3) { x,y -> (x+y)*.1f }
        for (light in listOf(SvgLightingLight.Distant(Double.MAX_VALUE,Double.MAX_VALUE),
            SvgLightingLight.Point(Double.MAX_VALUE,0.0,Double.MAX_VALUE),
            SvgLightingLight.Spot(Double.MAX_VALUE,0.0,Double.MAX_VALUE,-Double.MAX_VALUE,0.0,0.0))) {
            val output = render(p,scale=Double.MAX_VALUE,light=light,ox=-Double.MAX_VALUE)
            assertTrue(output.data.all { it.isFinite() && it in 0f..1f })
        }
    }
    @Test fun invalidParametersAreRejectedBeforeRendering() {
        val p = heightMap(1,1)
        for (value in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { render(p,scale=value) }
            assertFailsWith<IllegalArgumentException> { render(p,ox=value) }
            assertFailsWith<IllegalArgumentException> { render(p,light=SvgLightingLight.Point(value,0.0,1.0)) }
            assertFailsWith<IllegalArgumentException> { render(p,light=SvgLightingLight.Distant(value,0.0)) }
        }
        assertFailsWith<IllegalArgumentException> { render(p,constant=-1.0) }
        assertFailsWith<IllegalArgumentException> { render(p,specular=true,exponent=0.0) }
        assertFailsWith<IllegalArgumentException> { render(p,specular=true,exponent=129.0) }
        assertFailsWith<IllegalArgumentException> { render(p,sx=0.0) }
        assertFailsWith<IllegalArgumentException> { render(p,kx=-1.0) }
        assertFailsWith<IllegalArgumentException> { render(p,light=SvgLightingLight.Spot(exponent=-1.0)) }
    }
    @Test fun coincidentInputOutputEdgesDoNotInventBevelOnCroppedFlatInput() {
        val p = heightMap(7,7) { x,y -> if (x in 2..4 && y in 2..4) 1f else 0f }
        val output = p.lighting(false,1.0,1.0,1.0,Color.WHITE,false,overhead,
            0.0,0.0,1.0,1.0,edgeMinX=2,edgeMinY=2,edgeMaxX=4,edgeMaxY=4)
        for (y in 2..4) for (x in 2..4) assertPixel(output,x,y,1.0)
    }
    @Test fun expandedOutputRetainsTransparentPaddingBevel() {
        val p = heightMap(7,7) { x,y -> if (x in 2..4 && y in 2..4) 1f else 0f }
        val output = render(p)
        assertPixel(output,2,3,1/sqrt(2.0))
        assertPixel(output,3,2,1/sqrt(2.0))
        assertPixel(output,3,3,1.0)
        assertTrue(output.data[(3*7+1)*4] < 1f)
    }
    @Test fun edgeLimitsApplyIndependentlyAndNeverClampInputSampling() {
        val p = heightMap(7,3) { x,_ -> if (x in 2..4) 1f else 0f }
        // Only the left edge coincides; the right output edge expands beyond input.
        val output = p.lighting(false,1.0,1.0,1.0,Color.WHITE,false,overhead,
            0.0,0.0,1.0,1.0,edgeMinX=2)
        assertPixel(output,2,1,1.0)
        assertPixel(output,4,1,1/sqrt(2.0))
        assertPixel(output,5,1,1/sqrt(2.0))
        assertFailsWith<IllegalArgumentException> {
            p.lighting(false,1.0,1.0,1.0,Color.WHITE,false,overhead,
                0.0,0.0,1.0,1.0,edgeMinX=5,edgeMaxX=2)
        }
    }
    @Test fun workCeilingAndEmptyDimensionsAreHandledWithoutRendering() {
        assertTrue(render(heightMap(0,2)).data.isEmpty())
        assertTrue(render(heightMap(2,0)).data.isEmpty())
        val large = heightMap(1251,1000)
        assertFailsWith<IllegalArgumentException> { render(large) }
    }
    @Test fun workEstimateIsFixedAndInputAndOutputNeverAlias() {
        val p = heightMap(5,7)
        assertEquals(5L*7*160,p.lightingWork())
        val original=p.data.copyOf(); val output=render(p)
        output.data[3]=0f
        assertContentEquals(original,p.data)
    }
}
