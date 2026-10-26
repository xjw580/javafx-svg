package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class SvgFilterColorBlendTest {
    private fun element(xml: String) = SvgLoader.parse("<svg xmlns='http://www.w3.org/2000/svg'><defs>$xml</defs></svg>")
        .root.children.single().children.single()
    private fun matrix(attributes: String = "") = SvgFilterColorBlend.matrix(element("<feColorMatrix $attributes/>"))
    private fun pixel(r: Double, g: Double, b: Double, a: Double = 1.0, linear: Boolean = false) =
        SvgFilterPixels(1,1,linear,floatArrayOf((r*a).toFloat(),(g*a).toFloat(),(b*a).toFloat(),a.toFloat()))
    private fun check(p: SvgFilterPixels, vararg expected: Double) {
        expected.forEachIndexed { i,v -> assertEquals(v,p.data[i].toDouble(),2e-6,"channel $i") }
    }
    @Test fun omittedMatrixAndTypeAreIdentity() {
        val p=pixel(.2,.4,.9,.5)
        check(p.colorMatrix(matrix()),.1,.2,.45,.5)
        assertContentEquals(matrix(),matrix("type='matrix'"))
    }
    @Test fun allTypeDefaultsAreIdentityExceptLuminance() {
        val p=pixel(.2,.4,.6,.7)
        for (type in listOf("matrix","saturate","hueRotate"))
            assertContentEquals(p.data,p.colorMatrix(matrix("type='$type'")).data)
    }
    @Test fun twentyValuesMapStraightChannelsAndAlphaIndependently() {
        val m=matrix("values='0 1 0 0 0, 0 0 1 0 0, 1 0 0 0 0, 0 0 0 .5 .1'")
        check(pixel(.2,.4,.8,.6).colorMatrix(m),.16,.32,.08,.4)
    }
    @Test fun offsetsCreateColorFromTransparentBlack() {
        val m=matrix("values='0 0 0 0 .8 0 0 0 0 .4 0 0 0 0 .2 0 0 0 0 .5'")
        check(pixel(1.0,1.0,1.0,0.0).colorMatrix(m),.4,.2,.1,.5)
    }
    @Test fun zeroAlphaIgnoresInvalidHiddenRgb() {
        val p=SvgFilterPixels(1,1,false,floatArrayOf(1f,1f,1f,0f))
        check(p.colorMatrix(matrix()),0.0,0.0,0.0,0.0)
    }
    @Test fun clampsBeforeRepremultiplication() {
        val m=matrix("values='4 0 0 0 0 0 -2 0 0 0 0 0 1 0 0 0 0 0 0 .25'")
        check(pixel(.8,.3,.6,.5).colorMatrix(m),.25,0.0,.15,.25)
    }
    @Test fun alphaMayDependOnStraightRgb() {
        val m=matrix("values='1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 1 0 0 0 0'")
        check(pixel(.8,.4,.2,.25).colorMatrix(m),.64,.32,.16,.8)
    }
    @Test fun saturationZeroUsesPublishedLumaAndPreservesAlpha() {
        check(pixel(1.0,0.0,0.0,.5).colorMatrix(matrix("type='saturate' values='0'")),.1065,.1065,.1065,.5)
    }
    @Test fun supersaturationIsSupportedAndClamped() {
        check(pixel(1.0,0.0,0.0,.5).colorMatrix(matrix("type='saturate' values='2'")),.5,0.0,0.0,.5)
    }
    @Test fun hueQuarterTurnUsesSvgCoefficients() {
        check(pixel(1.0,0.0,0.0,.5).colorMatrix(matrix("type='hueRotate' values='90'")),0.0,.178,0.0,.5)
    }
    @Test fun hueAnglesArePeriodicAndFiniteEvenAtDoubleLimit() {
        assertContentEquals(matrix("type='hueRotate' values='90'"),matrix("type='hueRotate' values='450'"))
        assertContentEquals(matrix("type='hueRotate' values='270'"),matrix("type='hueRotate' values='-90'"))
        assertTrue(matrix("type='hueRotate' values='1e308'").all { it.isFinite() })
    }
    @Test fun luminanceUsesStraightRgbNotInputAlpha() {
        check(pixel(.2,.4,.8,.1).colorMatrix(matrix("type='luminanceToAlpha'")),0.0,0.0,0.0,.38636)
    }
    @Test fun luminanceIgnoresInapplicableValues() {
        assertContentEquals(matrix("type='luminanceToAlpha'"),matrix("type='luminanceToAlpha' values='nonsense'"))
    }
    @Test fun malformedMatrixAndScalarParametersAreRejected() {
        for (attrs in listOf("type='wrong'", "values='bad'", "type='hueRotate' values='90deg'", "type='hueRotate' values='NaN'",
            "type='hueRotate' values='1e309'"))
            assertFailsWith<IllegalArgumentException>(attrs) { matrix(attrs) }
        for (v in listOf(Double.NaN,Double.POSITIVE_INFINITY)) {
            val m=matrix();m[0]=v
            assertFailsWith<IllegalArgumentException> { pixel(.2,.3,.4).colorMatrix(m) }
        }
        assertFailsWith<IllegalArgumentException> { pixel(.2,.3,.4).colorMatrix(DoubleArray(19)) }
    }
    @Test fun validWrongNumberCountsPassThrough() {
        for (attrs in listOf("values=''", "values='1 2'", "type='saturate' values='1 2'", "type='hueRotate' values=''"))
            assertContentEquals(matrix(),matrix(attrs))
    }
    @Test fun negativeSaturationUsesTheSameMatrixAndClamps() {
        check(pixel(1.0,0.0,0.0,.5).colorMatrix(matrix("type='saturate' values='-1'")),0.0,.213,.213,.5)
    }
    @Test fun extremeFiniteRowsDoNotProduceNaNs() {
        val m=matrix(); m[0]=1e308;m[1]=1e308;m[2]=-1e308;m[3]=-1e308
        val out=pixel(1.0,1.0,1.0,1.0).colorMatrix(m)
        check(out,0.0,1.0,1.0,1.0)
        val sat=pixel(.8,.2,.1,.4).colorMatrix(matrix("type='saturate' values='1e308'"))
        assertTrue(sat.data.all { it.isFinite() && it in 0f..1f })
    }
    @Test fun kernelsPreserveInputDataAndColorSpace() {
        val p=pixel(.4,.2,.8,.6,true);val original=p.data.copyOf()
        val q=pixel(.5,.2,.9,.2,true);val qo=q.data.copyOf()
        assertTrue(p.colorMatrix(matrix()).linear)
        for (mode in SvgFilterColorBlend.BLEND_MODES) assertTrue(p.blend(q,mode).linear)
        assertContentEquals(original,p.data);assertContentEquals(qo,q.data)
    }
    @Test fun blendDefaultAndModeValidation() {
        assertEquals("normal",SvgFilterColorBlend.blendMode(element("<feBlend/>")))
        for (mode in SvgFilterColorBlend.BLEND_MODES)
            assertEquals(mode,SvgFilterColorBlend.blendMode(element("<feBlend mode='$mode'/>")))
        for (mode in listOf("overlay","NORMAL","")) assertFailsWith<IllegalArgumentException> {
            SvgFilterColorBlend.blendMode(element("<feBlend mode='$mode'/>"))
        }
    }
    @Test fun opaqueBlendModesHaveExpectedColors() {
        val a=pixel(.8,.3,.2);val b=pixel(.4,.7,.1)
        check(a.blend(b,"normal"),.8,.3,.2,1.0)
        check(a.blend(b,"multiply"),.32,.21,.02,1.0)
        check(a.blend(b,"screen"),.88,.79,.28,1.0)
        check(a.blend(b,"darken"),.4,.3,.1,1.0)
        check(a.blend(b,"lighten"),.8,.7,.2,1.0)
    }
    @Test fun partialAlphaMatchesIndependentStraightColorOracle() {
        val random=Random(726)
        repeat(250) {
            val a=DoubleArray(4){random.nextDouble()};val b=DoubleArray(4){random.nextDouble()}
            val source=pixel(a[0],a[1],a[2],a[3]);val dest=pixel(b[0],b[1],b[2],b[3])
            for (mode in SvgFilterColorBlend.BLEND_MODES) {
                val output=source.blend(dest,mode)
                val expected=DoubleArray(4);expected[3]=a[3]+b[3]-a[3]*b[3]
                for (c in 0..2) {
                    val blend=when(mode){"normal"->a[c];"multiply"->a[c]*b[c];"screen"->a[c]+b[c]-a[c]*b[c];"darken"->minOf(a[c],b[c]);else->maxOf(a[c],b[c])}
                    expected[c]=(1-b[3])*a[3]*a[c]+(1-a[3])*b[3]*b[c]+a[3]*b[3]*blend
                }
                check(output,*expected)
            }
        }
    }
    @Test fun transparentInputsBehaveForEveryMode() {
        val clear=pixel(0.0,0.0,0.0,0.0);val p=pixel(.2,.5,.7,.4)
        for(mode in SvgFilterColorBlend.BLEND_MODES) {
            assertContentEquals(p.data,p.blend(clear,mode).data)
            assertContentEquals(p.data,clear.blend(p,mode).data)
            assertTrue(clear.blend(clear,mode).data.all { it == 0f })
        }
    }
    @Test fun blendConvertsBackdropToReceiverSpace() {
        val a=pixel(.2,.4,.6,.5,true);val b=pixel(.7,.2,.3,.4,false)
        for(mode in SvgFilterColorBlend.BLEND_MODES)
            assertContentEquals(a.blend(b.converted(true),mode).data,a.blend(b,mode).data)
    }
    @Test fun blendRejectsDimensionMismatchAndUnknownMode() {
        val p=pixel(.2,.3,.4)
        assertFailsWith<IllegalArgumentException> { p.blend(SvgFilterPixels(2,1,false),"normal") }
        assertFailsWith<IllegalArgumentException> { p.blend(p,"overlay") }
    }
    @Test fun fullImageDimensionsAreRetained() {
        val p=SvgFilterPixels(4,3,false)
        val m=matrix();m[4]=.4;m[19]=.5
        val out=p.colorMatrix(m)
        assertEquals(4,out.width);assertEquals(3,out.height)
        for(i in out.data.indices step 4) {assertEquals(.2f,out.data[i]);assertEquals(.5f,out.data[i+3])}
    }
}
