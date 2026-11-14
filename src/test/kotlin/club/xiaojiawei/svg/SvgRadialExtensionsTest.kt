package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.Stop
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgRadialExtensionsTest {
    private fun sample(cx:Double=0.0,r:Double=10.0,fx:Double=0.0,fr:Double=2.0,spread:String="pad",linear:Boolean=false)=
        SvgRadialSampler(Affine(),cx,0.0,r,fx,0.0,fr,spread,listOf(Stop(0.0,Color.BLACK),Stop(1.0,Color.WHITE)),linear)
    @Test fun focalCircleAndOuterCircle() {
        val s=sample(); assertEquals(0.0,s.color(2.0,0.0).red,1e-9)
        assertEquals(.5,s.color(6.0,0.0).red,1e-9); assertEquals(1.0,s.color(10.0,0.0).red,1e-9)
        assertEquals(0.0,s.color(0.0,0.0).red,1e-9)
    }
    @Test fun offCenterAndSpreadModes() {
        val s=sample(cx=2.0,fx=0.0)
        assertEquals(.5,s.color(7.0,0.0).red,1e-9)
        assertEquals(.5,sample(spread="repeat").color(14.0,0.0).red,1e-9)
        assertEquals(.5,sample(spread="reflect").color(14.0,0.0).red,1e-9)
        assertTrue(sample(linear=true).color(6.0,0.0).red>.73)
    }
    @Test fun outsideConeAndCoincidentCircles() {
        assertEquals(0.0,sample(r=2.0,fx=10.0,fr=1.0).color(0.0,50.0).opacity)
        assertEquals(0.0,sample(r=2.0,fr=2.0).color(4.0,0.0).opacity)
    }
    private fun view(extra:String="",options:SvgOptions=SvgOptions(strict=true))=SvgView.parse("""<svg width="100" height="60" viewBox="0 0 100 60"><defs>
        <radialGradient id="g" gradientTransform="translate(.1 0) scale(1 .8)" fr=".1"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs>
        <rect id="r" width="100" height="60" fill="url(#g)" $extra/></svg>""",options).apply { isManaged=false; Scene(Group(this));resize(100.0,60.0);applyCss();layout() }
    @Test fun transformedPaintCachesAndRescales()=FxTest.run {
        val v=view();val r=v.lookup("#r") as Shape; val first=r.fill
        assertIs<ImagePattern>(first);v.requestLayout();v.layout();assertSame(first,r.fill)
        v.resize(200.0,120.0);v.layout();assertNotSame(first,r.fill)
        val image=v.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null)
        assertTrue(image.pixelReader.getColor(120,48).red>.8)
    }
    @Test fun budgetAndNegativeRadiusReject()=FxTest.run {
        assertFailsWith<SvgException> {view(options=SvgOptions(strict=true,maxPatternPixels=10))}
        assertFailsWith<SvgException> {SvgView.parse("""<svg><defs><radialGradient id="g" fr="-1"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs><rect width="10" height="10" fill="url(#g)"/></svg>""",SvgOptions(strict=true))}
    }
    @Test fun sampleSnapshotsAtRepeatedScales()=FxTest.run {
        val xml=javaClass.getResource("/svg/gradient-pattern-extensions.svg")!!.readText()
        val v=SvgView.parse(xml,SvgOptions(strict=true)).apply {isManaged=false;Scene(Group(this))}
        for(scale in listOf(1.0,2.0,1.25,1.0)) {
            v.resize(640*scale,360*scale);v.applyCss();v.layout()
            assertTrue(v.diagnostics.isEmpty())
            val image=v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
            assertTrue(image.pixelReader.getColor((120*scale).toInt(),(100*scale).toInt()).opacity>.99)
            if(scale==1.0) {
                val out=java.awt.image.BufferedImage(image.width.toInt(),image.height.toInt(),java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until image.height.toInt()) for(x in 0 until image.width.toInt()) out.setRGB(x,y,image.pixelReader.getArgb(x,y))
                assertTrue(javax.imageio.ImageIO.write(out,"png",java.io.File("target/gradient-pattern-extensions.png")))
            }
        }
    }
    @Test fun transformedZeroRadiusPreservesSolidLastStop() {
        assertEquals(Color.WHITE,sample(r=0.0,fr=0.0).color(4.0,0.0))
        val sampler=SvgRadialSampler(Affine(1.0,0.0,10.0,0.0,1.0,20.0),0.0,0.0,0.0,0.0,0.0,0.0,
            "pad",listOf(Stop(0.0,Color.BLACK),Stop(1.0,Color.WHITE)),false)
        assertEquals(Color.WHITE,sampler.color(4.0,0.0))
    }
    @Test fun tinyAndHugeCoordinatesAreScaleInvariant() {
        val stops=listOf(Stop(0.0,Color.BLACK),Stop(1.0,Color.WHITE))
        val ordinary=SvgRadialSampler(Affine(),0.0,0.0,10.0,0.0,0.0,2.0,"pad",stops,false)
        for(scale in listOf(1e-8,1e8)) {
            val scaled=SvgRadialSampler(Affine(scale,0.0,0.0,0.0,scale,0.0),0.0,0.0,10*scale,0.0,0.0,2*scale,"pad",stops,false)
            assertEquals(ordinary.color(6.0,0.0).red,scaled.color(6.0,0.0).red,1e-10)
        }
        assertTrue(svgPaintInverse(Affine(1e-8,0.0,0.0,0.0,1e-8,0.0)).mxx.isFinite())
    }
    @Test fun affineInverseOpacityAndDuplicateStops() {
        val affine=Affine(2.0,.5,10.0,.25,3.0,20.0)
        val s=SvgRadialSampler(svgPaintInverse(affine),0.0,0.0,10.0,0.0,0.0,2.0,"pad",listOf(
            Stop(0.0,Color.color(1.0,0.0,0.0,.2)),Stop(.5,Color.color(0.0,0.0,1.0,.6)),Stop(.5,Color.color(0.0,1.0,0.0,.8)),Stop(1.0,Color.WHITE)),false)
        val p=affine.transform(4.0,0.0);val c=s.color(p.x,p.y)
        assertEquals(.5,c.red,1e-10);assertEquals(.5,c.blue,1e-10);assertEquals(.4,c.opacity,1e-7)
    }
    @Test fun singularTransformIsDiagnosed()=FxTest.run {
        val xml="""<svg><defs><radialGradient id="g" gradientTransform="scale(0)"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs><rect id="r" width="10" height="10" fill="url(#g) green"/></svg>"""
        val v=SvgView.parse(xml)
        assertTrue(v.diagnostics.any{it.message.contains("Singular")})
        assertEquals(Color.GREEN,(v.lookup("#r") as Shape).fill)
    }
    @Test fun tangentSpreadUsesWeightedAverage() {
        val s=SvgRadialSampler(Affine(),0.0,0.0,10.0,10.0,0.0,0.0,"repeat",listOf(
            Stop(0.0,Color.BLACK),Stop(.25,Color.WHITE),Stop(1.0,Color.BLACK)),false)
        assertEquals(.5,s.color(20.0,0.0).red,1e-10)
        assertSame(s.color(20.0,0.0),s.color(30.0,0.0))
    }
    @Test fun untransformedExternalFocusAndLinearSpaceUseSoftware()=FxTest.run {
        for(attrs in listOf("fx=\"2\" fy=\".5\"", "color-interpolation=\"linearRGB\"")) {
            val v=SvgView.parse("""<svg width="20" height="20"><defs><radialGradient id="g" $attrs><stop stop-color="black"/><stop offset="1" stop-color="white"/></radialGradient></defs><rect id="r" width="20" height="20" fill="url(#g)"/></svg>""",SvgOptions(strict=true))
            assertIs<ImagePattern>((v.lookup("#r") as Shape).fill)
        }
    }
    @Test fun mixedFocusCoordinatesKeepSoftwareSemanticsAcrossViewportResize()=FxTest.run {
        val xml="""<svg><defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="50%" cy="50%" r="30%" fx="60" fy="50%"><stop stop-color="black"/><stop offset="1" stop-color="white"/></radialGradient></defs><rect id="r" width="100%" height="100%" fill="url(#g)"/></svg>"""
        val v=SvgView.parse(xml,SvgOptions(strict=true)).apply{isManaged=false;Scene(Group(this))}
        val r=v.lookup("#r") as Shape
        for(size in listOf(100.0,400.0,100.0)) {
            v.resize(size,100.0);v.applyCss();v.layout()
            val actual=r.fill as ImagePattern
            val doc=requireNotNull(v.document);val paints=SvgPaints(doc,SvgStyles(doc){m,_->error(m)})
            val viewport=SvgViewport(size,100.0)
            val reference=paints.radial("g",1.0,viewport,svgGeometryBounds(r))
            val ix=actual.image.width.toInt()/2;val iy=actual.image.height.toInt()/2
            val expected=reference.color(actual.x+(ix+.5)*actual.width/actual.image.width,actual.y+(iy+.5)*actual.height/actual.image.height)
            val observed=actual.image.pixelReader.getColor(ix,iy)
            assertEquals(expected.opacity,observed.opacity,.01)
            if(expected.opacity>.01) assertEquals(expected.red,observed.red,.01)
            assertTrue(v.diagnostics.isEmpty())
        }
    }
}
