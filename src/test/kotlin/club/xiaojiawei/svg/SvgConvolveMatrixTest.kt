package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgConvolveMatrixTest {
    private fun p(attrs: String) = SvgConvolveParameters.parse(SvgLoader.parse("<svg><defs><feConvolveMatrix $attrs/></defs></svg>").root.children.single().children.single())
    private fun row(vararg v: Double) = SvgFilterPixels(v.size, 1, false).also { r -> v.forEachIndexed { x,a -> for(c in 0..3) r.data[x*4+c]=a.toFloat() } }
    private fun red(r: SvgFilterPixels) = (0 until r.width).map { r.data[it*4].toDouble() }
    @Test fun identityAndDivisorDefaults() {
        val s=row(.1,.5,.9)
        assertContentEquals(s.data,s.convolveMatrix(p("order='1' kernelMatrix='2'")).data)
        assertContentEquals(s.data,s.convolveMatrix(p("order='1' kernelMatrix='2' divisor='0'")).data)
    }
    @Test fun reversedKernelAndTarget() {
        val s=row(.1,.5,.9)
        assertEquals(listOf(.5f.toDouble(),.9f.toDouble(),.9f.toDouble()),red(s.convolveMatrix(p("order='3 1' kernelMatrix='1 0 0'"))))
        assertEquals(listOf(.9f.toDouble(),.9f.toDouble(),.9f.toDouble()),red(s.convolveMatrix(p("order='3 1' targetX='0' kernelMatrix='1 0 0'"))))
    }
    @Test fun edgeModes() {
        val s=row(.1,.5,.9)
        assertEquals(.1f, s.convolveMatrix(p("order='3 1' kernelMatrix='0 0 1'" )).data[0])
        assertEquals(.9f, s.convolveMatrix(p("order='3 1' kernelMatrix='0 0 1' edgeMode='wrap'" )).data[0])
        assertEquals(0f, s.convolveMatrix(p("order='3 1' kernelMatrix='0 0 1' edgeMode='none'" )).data[0])
    }
    @Test fun croppedInputDefinesEdges() {
        val s=row(.1,.5,.9)
        val out=s.convolveMatrix(p("order='3 1' kernelMatrix='0 0 1'"),1,0,2,1)
        assertEquals(listOf(.5,.5,.5),red(out))
    }
    @Test fun preserveAlphaUsesStraightRgb() {
        val s=SvgFilterPixels(2,1,false,floatArrayOf(.2f,.1f,0f,.25f,.1f,.2f,0f,.5f))
        val r=s.convolveMatrix(p("order='2 1' targetX='0' kernelMatrix='1 0' preserveAlpha='true'"))
        assertEquals(.05,r.data[0].toDouble(),1e-6);assertEquals(.1,r.data[1].toDouble(),1e-6);assertEquals(.25f,r.data[3])
    }
    @Test fun premultipliedBiasAndOutputClamp() {
        val s=SvgFilterPixels(1,1,false,floatArrayOf(.1f,.2f,0f,.5f))
        val r=s.convolveMatrix(p("order='1' kernelMatrix='1' bias='.25'"))
        assertEquals(.75f,r.data[3]);assertEquals(.2875,r.data[0].toDouble(),1e-6)
        val q=s.convolveMatrix(p("order='1' kernelMatrix='0' bias='2'"))
        assertTrue(q.data.all { it==1f })
    }
    @Test fun zeroSumDefaultAndPreservedTransparentPixels() {
        assertEquals(1.0,p("order='2 1' kernelMatrix='-1 1'").divisor)
        val s=SvgFilterPixels(1,1,false)
        assertTrue(s.convolveMatrix(p("order='1' kernelMatrix='1' bias='1' preserveAlpha='true'")).data.all { it==0f })
    }
    @Test fun wrongKernelCountPassesThrough() {
        val s=row(.2,.4)
        assertSame(s,s.convolveMatrix(p("order='3' kernelMatrix='1 2'")))
        assertSame(s,s.convolveMatrix(p("")))
    }
    @Test fun fractionalOrderTruncatesAndNonpositiveSpacingDefaults() {
        val a=p("order='2.9 1.9' kernelMatrix='1 0' kernelUnitLength='0 2'")
        assertEquals(2,a.orderX);assertEquals(1,a.orderY);assertNull(a.kernelX)
    }
    @Test fun badParametersFailEarly() {
        for (a in listOf("order='0'","order='-2'","order='5000'","order='256 257'","order='1 2 3'","targetX='-1'","targetY='3'","targetX='.5'","edgeMode='bad'","preserveAlpha='yes'","kernelUnitLength='1 2 3'","kernelMatrix='NaN'","kernelMatrix='1e308 1e308'"))
            assertFailsWith<IllegalArgumentException>(a) { p(a) }
    }
    @Test fun workIsRejectedBeforeOutputAllocation() {
        val s=SvgFilterPixels(512,512,false)
        assertFailsWith<IllegalArgumentException> { s.convolveMatrix(p("order='9' kernelMatrix='${List(81){"1"}.joinToString(" ")}'")) }
    }
    @Test fun publishedTwoDimensionalAsymmetricOracle() {
        val values=listOf(0,20,40,235,235,100,120,140,235,235,200,220,240,235,235,255,255,255,255,255,255,255,255,255,255)
        val s=SvgFilterPixels(5,5,false)
        values.forEachIndexed { i,v -> s.data[i*4]=v/255f;s.data[i*4+3]=1f }
        val r=s.convolveMatrix(p("kernelMatrix='1 2 3 4 5 6 7 8 9'"))
        val expected=(9*0+8*20+7*40+6*100+5*120+4*140+3*200+2*220+240)/45.0/255
        assertEquals(expected,r.data[(1*5+1)*4].toDouble(),1e-6)
    }
    @Test fun verticalTargetAndNegativeLobesClampPremultiplied() {
        val s=SvgFilterPixels(1,3,false,floatArrayOf(.1f,0f,0f,.2f,.4f,0f,0f,.8f,.2f,0f,0f,.4f))
        val r=s.convolveMatrix(p("order='1 3' targetY='0' kernelMatrix='1 0 0'"))
        assertEquals(.2f,r.data[0]);assertEquals(.4f,r.data[3])
        val q=s.convolveMatrix(p("order='1 3' kernelMatrix='-1 2 -1' bias='.1'"))
        for(i in q.data.indices step 4)assertTrue(q.data[i]>=0 && q.data[i]<=q.data[i+3])
    }
}
