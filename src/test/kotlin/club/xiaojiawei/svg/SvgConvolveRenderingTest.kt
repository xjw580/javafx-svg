package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class SvgConvolveRenderingTest {
    private fun xml(p:String)="""<svg width="100" height="100" viewBox="0 0 100 100"><defs><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" color-interpolation-filters="sRGB">$p</filter></defs><rect x="20" y="20" width="60" height="60" fill="red" filter="url(#f)"/></svg>"""
    private fun image(v:SvgView,scale:Double=1.0):javafx.scene.image.WritableImage {
        v.isManaged=false;if(v.scene==null)Scene(Group(v));v.resize(100*scale,100*scale);v.applyCss();v.layout()
        return v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null)
    }
    @Test fun identityDefaultPassthroughAndNamedInput()=FxTest.run {
        for(p in listOf("<feConvolveMatrix order='1' kernelMatrix='1'/>","<feConvolveMatrix/>","<feFlood flood-color='blue' result='b'/><feConvolveMatrix in='SourceGraphic' order='1' kernelMatrix='1'/>")) {
            val v=SvgView.parse(xml(p),SvgOptions(strict=true));assertEquals(Color.RED,image(v).pixelReader.getColor(50,50));assertTrue(v.diagnostics.isEmpty());v.source=null
        }
    }
    @Test fun croppedInputAndNonAlignedGridDuplicateStayOpaque()=FxTest.run {
        val v=SvgView.parse(xml("<feFlood x='21' y='21' width='30' height='30' flood-color='blue'/><feConvolveMatrix order='1' kernelMatrix='1' kernelUnitLength='2'/>"),SvgOptions(strict=true))
        val r=image(v).pixelReader
        assertEquals(Color.BLUE,r.getColor(21,21));assertEquals(Color.BLUE,r.getColor(50,50));assertEquals(0.0,r.getColor(52,52).opacity);v.source=null
    }
    @Test fun explicitGridAndColorSpaceProduceValidPixels()=FxTest.run {
        val v=SvgView.parse(xml("<feGaussianBlur stdDeviation='1'/><feConvolveMatrix order='3' kernelMatrix='0 -1 0 -1 5 -1 0 -1 0' kernelUnitLength='.75 2' preserveAlpha='true'/>"),SvgOptions(strict=true))
        for(s in listOf(1.0,1.5,2.0,1.0)) { val r=image(v,s);assertTrue(v.diagnostics.isEmpty());assertEquals(Color.RED,r.pixelReader.getColor((50*s).toInt(),(50*s).toInt())) };v.source=null
    }
    @Test fun invalidParametersFailStrictly()=FxTest.run {
        for(a in listOf("order='0'","targetX='10'","edgeMode='bad'","preserveAlpha='yes'"))assertFailsWith<SvgException>{SvgView.parse(xml("<feConvolveMatrix $a/>"),SvgOptions(strict=true))}
    }
    @Test fun resamplingMemoryBudgetAndWorkBudgetRecoverAfterReplacement()=FxTest.run {
        val src=xml("<feConvolveMatrix order='1' kernelMatrix='1' kernelUnitLength='.25'/>")
        val v=SvgView.parse(src,SvgOptions(maxFilterPixels=100_000));image(v)
        assertTrue(v.diagnostics.any {"maxFilterPixels" in it.message},v.diagnostics.toString())
        v.document=SvgLoader.parse(xml("<feConvolveMatrix order='1' kernelMatrix='1'/>"),SvgOptions(strict=true))
        assertEquals(Color.RED,image(v).pixelReader.getColor(50,50));assertTrue(v.diagnostics.isEmpty());v.source=null
        val kernel=List(81){"1"}.joinToString(" ")
        val w=SvgView.parse(xml("<feConvolveMatrix order='9' kernelMatrix='$kernel'/>"))
        image(w,5.0);assertTrue(w.diagnostics.any {"work limit" in it.message},w.diagnostics.toString())
        w.document=SvgLoader.parse(xml("<feConvolveMatrix order='1' kernelMatrix='1'/>"),SvgOptions(strict=true));assertEquals(Color.RED,image(w).pixelReader.getColor(50,50));w.source=null
    }
    @Test fun lightingIntermediateGridHonorsSameMemoryBudget()=FxTest.run {
        val v=SvgView.parse(xml("<feDiffuseLighting kernelUnitLength='.25'><feDistantLight elevation='90'/></feDiffuseLighting>"),SvgOptions(maxFilterPixels=100_000))
        image(v);assertTrue(v.diagnostics.any {"maxFilterPixels" in it.message},v.diagnostics.toString());v.source=null
    }
    @Test fun stageOneSampleRendersRepeatedScalesAndSavesScreenshot()=FxTest.run {
        val v=SvgView.load(checkNotNull(javaClass.getResource("/svg/convolve-lighting-filters.svg")),SvgOptions(strict=true));v.isManaged=false;Scene(Group(v))
        for(s in listOf(1.0,1.25,1.5,2.0,1.0)) {
            v.resize(640*s,360*s);v.applyCss();v.layout();val p=v.snapshot(null,null)
            assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString());assertEquals(4,v.lookupAll(".svg-filter-output").size)
            if(s==1.0){val b=BufferedImage(p.width.toInt(),p.height.toInt(),BufferedImage.TYPE_INT_ARGB);for(y in 0 until b.height)for(x in 0 until b.width)b.setRGB(x,y,p.pixelReader.getArgb(x,y));ImageIO.write(b,"png",Path.of("target","convolve-lighting-filters-1x.png").toFile())}
        };v.source=null
    }
}
