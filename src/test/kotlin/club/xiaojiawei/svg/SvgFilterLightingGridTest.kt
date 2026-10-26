package club.xiaojiawei.svg

import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.*

class SvgFilterLightingGridTest {
    private fun heights(w: Int, h: Int, f: (Int, Int) -> Float) = SvgFilterPixels(w, h, false).also { p ->
        for (y in 0 until h) for (x in 0 until w) p.data[(y*w+x)*4+3]=f(x,y)
    }
    private fun shade(p: SvgFilterPixels, kx: Double = 1.0, ky: Double = 1.0,
        ox: Double = .5, oy: Double = .5, light: SvgLightingLight = SvgLightingLight.Distant(25.0, 50.0)) =
        p.lighting(false, 2.0, 1.0, 1.0, Color.WHITE, false, light, ox, oy, 1.0, 1.0, kx, ky)

    @Test fun gridUsesExactSpacingAndPixelFootprintAlignment() {
        val p=heights(4,2) { x,y -> (x + 4*y)/7f }
        val plan=SvgFilterGridPlan(4,2,2.0,2.0)
        assertEquals(2,plan.gridWidth); assertEquals(1,plan.gridHeight)
        val sampled=plan.input(p)
        assertEquals(2.5f/7f,sampled.data[3],1e-7f)
        assertEquals(4.5f/7f,sampled.data[7],1e-7f)
        val back=plan.output(sampled)
        assertEquals(sampled.data[3],back.data[3])
        assertEquals((sampled.data[3]*.75f+sampled.data[7]*.25f),back.data[7],1e-7f)
        assertEquals(sampled.data[7],back.data[15])
        val odd=SvgFilterGridPlan(5,4,2.0,1.5)
        assertEquals(3,odd.gridWidth); assertEquals(3,odd.gridHeight)
    }

    @Test fun outputInterpolatesLitGridRatherThanShadingBilinearHeightsAtEachDevicePixel() {
        val p=heights(6,4) { x,y -> if (x in 2..3 && y in 1..2) 1f else 0f }
        val actual=shade(p,2.0,2.0)
        // Independently construct the 3x2 intermediate height field from 2x2 means.
        val q=heights(3,2) { x,y ->
            var sum=0f
            for (dy in 0..1) for (dx in 0..1) sum+=p.data[(((y*2+dy)*6+x*2+dx)*4)+3]
            sum/4f
        }
        val lit=q.lighting(false,2.0,1.0,1.0,Color.WHITE,false,SvgLightingLight.Distant(25.0,50.0),
            1.0,1.0,2.0,2.0)
        for (y in 0..3) for (x in 0..5) {
            val u=((x+.5)/2-.5).coerceIn(0.0,2.0); val v=((y+.5)/2-.5).coerceIn(0.0,1.0)
            val x0=floor(u).toInt(); val y0=floor(v).toInt(); val x1=min(x0+1,2); val y1=min(y0+1,1)
            val fx=u-x0; val fy=v-y0
            for(c in 0..3) {
                fun value(xx:Int,yy:Int)=lit.data[(yy*3+xx)*4+c].toDouble()
                val expected=(value(x0,y0)*(1-fx)+value(x1,y0)*fx)*(1-fy)+(value(x0,y1)*(1-fx)+value(x1,y1)*fx)*fy
                assertEquals(expected,actual.data[(y*6+x)*4+c].toDouble(),1e-7,"($x,$y) channel$c")
            }
        }
    }

    @Test fun pointLightCoordinatesUseIntermediatePixelCenters() {
        val p=heights(6,4) { _,_->0f }
        val light=SvgLightingLight.Point(12.0,14.0,8.0)
        val actual=shade(p,2.0,2.0,10.5,12.5,light)
        val centers=doubleArrayOf(11.0,13.0,15.0)
        val first=8/sqrt((12-centers[0]).pow(2)+(14-13.0).pow(2)+64)
        assertEquals(first,actual.data[0].toDouble(),1e-7)
        val last=8/sqrt((12-centers[2]).pow(2)+(14-15.0).pow(2)+64)
        assertEquals(last,actual.data[(3*6+5)*4].toDouble(),1e-7)
    }

    @Test fun supersampledGridPreservesPremultipliedOutputAndDoesNotMutateInput() {
        val p=heights(4,3){x,y->(x+y)/5f};val original=p.data.copyOf()
        val out=p.lighting(true,2.0,1.0,4.0,Color.color(.2,.7,.4),true,
            SvgLightingLight.Point(2.0,2.0,10.0),.5,.5,1.0,1.0,.5,.75)
        for(i in out.data.indices step 4) {
            val a=out.data[i+3]
            for(c in 0..2) assertTrue(out.data[i+c] in 0f..a)
        }
        assertContentEquals(original,p.data)
    }

    @Test fun defaultGridRemainsIdentityAndNoAdditionalBuffersAreCharged() {
        val p=heights(7,4){x,y->(x+y)/10f};val plan=SvgFilterGridPlan(7,4,1.0,1.0)
        assertSame(p,plan.input(p));assertSame(p,plan.output(p))
        assertEquals(0L,plan.extraPixels);assertEquals(0L,plan.resampleWork)
        assertEquals(p.lightingWork(),p.lightingWork(null,plan))
    }

    @Test fun gridBudgetIsKnownBeforeAnyImageAllocation() {
        val p=heights(4,3){_,_->0f};val plan=SvgFilterGridPlan(4,3,.5,.5)
        assertEquals(48L,plan.gridPixels);assertEquals(96L,plan.extraPixels)
        assertEquals(16L*(48+12),plan.resampleWork)
        assertEquals(48L*160+plan.resampleWork,p.lightingWork(null,plan))
        assertFailsWith<IllegalArgumentException>{SvgFilterGridPlan(4,3,1e-20,1.0)}
        assertFailsWith<IllegalArgumentException>{SvgFilterGridPlan(4,3,Double.NaN,1.0)}
        assertFailsWith<IllegalArgumentException>{SvgFilterGridPlan(4,3,0.0,1.0)}
        // A legal-axis but excessive-work grid must fail before its large image is allocated.
        assertFailsWith<IllegalArgumentException>{shade(p,.001,.001)}
    }

    @Test fun hugeKernelAndEmptyInputHaveBoundedDefinedResults() {
        val p=heights(3,2){x,y->(x+y)/3f}
        val out=p.lighting(false,1.0,1.0,1.0,Color.WHITE,false,SvgLightingLight.Distant(0.0,90.0),
            .5,.5,1.0,1.0,100.0,100.0)
        for(i in out.data.indices) assertEquals(1f,out.data[i])
        assertTrue(shade(heights(0,3){_,_->0f},2.0,2.0).data.isEmpty())
    }
    @Test fun fractionalGridDoesNotInventBevelAtCoincidentCroppedEdges() {
        val p=heights(7,7) { x,y -> if(x in 2..4 && y in 2..4) 1f else 0f }
        for(k in listOf(.75,1.5,2.0,3.0)) {
            val out=p.lighting(false,3.0,1.0,1.0,Color.WHITE,false,SvgLightingLight.Distant(0.0,90.0),
                .5,.5,1.0,1.0,k,k,2,2,4,4)
            for(y in 2..4) for(x in 2..4) assertEquals(1f,out.data[(y*7+x)*4],1e-7f,"kernel $k ($x,$y)")
        }
    }

}
