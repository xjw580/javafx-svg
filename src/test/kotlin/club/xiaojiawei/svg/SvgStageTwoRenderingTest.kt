package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgStageTwoRenderingTest {
    private fun source(p:String,attrs:String="color-interpolation-filters='sRGB'",content:String="<rect x='16' y='16' width='32' height='32' fill='#804020' filter='url(#f)'/>") =
        """<svg width='64' height='64' viewBox='0 0 64 64'><defs><filter id='f' filterUnits='userSpaceOnUse' x='0' y='0' width='64' height='64' $attrs>$p</filter></defs>$content</svg>"""
    private fun render(p:String,attrs:String="color-interpolation-filters='sRGB'"):Color {
        val v=SvgView.parse(source(p,attrs),SvgOptions(strict=true)).apply { isManaged=false; Scene(Group(this)); resize(64.0,64.0); applyCss(); layout() }
        val out=v.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null)
        assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString())
        return out.pixelReader.getColor(32,32)
    }
    @Test fun transferLinearGammaTableDiscreteThroughRealPipeline() = FxTest.run {
        val p="""<feComponentTransfer><feFuncR type='linear' slope='0' intercept='.8'/><feFuncG type='gamma' amplitude='1' exponent='2'/><feFuncB type='table' tableValues='1 0'/><feFuncA type='discrete' tableValues='.5 .5'/></feComponentTransfer>"""
        val c=render(p)
        assertEquals(.8,c.red,.02);assertEquals((64/255.0)*(64/255.0),c.green,.02)
        assertEquals(1-32/255.0,c.blue,.02);assertEquals(.5,c.opacity,.01)
    }
    @Test fun omittedChannelsAndDuplicateLastDefinition() = FxTest.run {
        val c=render("<feComponentTransfer><feFuncR type='linear' slope='0' intercept='0'/><feFuncR type='linear' slope='0' intercept='1'/></feComponentTransfer>")
        assertEquals(1.0,c.red,.01);assertEquals(64/255.0,c.green,.01);assertEquals(32/255.0,c.blue,.01)
    }
    @Test fun colorSpaceConversionOccursBeforeTransfer() = FxTest.run {
        val p="<feComponentTransfer><feFuncR type='linear' slope='0' intercept='.5'/></feComponentTransfer>"
        assertEquals(.5,render(p).red,.01)
        assertEquals(.735,render(p,"").red,.015)
    }
    @Test fun alphaCreationUsesTransparentInputStraightZeroAndClipsRegion()=FxTest.run {
        val p="<feComponentTransfer x='4' y='4' width='8' height='8'><feFuncR type='linear' slope='0' intercept='1'/><feFuncA type='linear' slope='0' intercept='1'/></feComponentTransfer>"
        val v=SvgView.parse(source(p),SvgOptions(strict=true));v.isManaged=false;Scene(Group(v));v.resize(64.0,64.0);v.layout()
        val r=v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null).pixelReader
        assertEquals(Color.RED,r.getColor(8,8));assertEquals(0.0,r.getColor(32,32).opacity)
    }
    @Test fun extendedBlendPipelineMatchesIndependentOpaqueCases()=FxTest.run {
        val prefix="<feFlood flood-color='#4080c0' result='b'/><feFlood flood-color='#804020' result='s'/>"
        for(mode in listOf("overlay","hard-light","soft-light","difference","exclusion","color-dodge","color-burn","hue","saturation","color","luminosity")) {
            val c=render(prefix+"<feBlend in='s' in2='b' mode='$mode'/>")
            assertEquals(1.0,c.opacity,.01)
            assertTrue(c.red.isFinite()&&c.green.isFinite()&&c.blue.isFinite())
        }
        val diff=render(prefix+"<feBlend in='s' in2='b' mode='difference'/>")
        assertEquals(64/255.0,diff.red,.01);assertEquals(64/255.0,diff.green,.01);assertEquals(160/255.0,diff.blue,.01)
    }
    @Test fun transferThenBlendNamedAndImplicitInputsCompose()=FxTest.run {
        val c=render("<feFlood flood-color='blue' result='b'/><feComponentTransfer in='SourceGraphic'><feFuncR type='linear' slope='0' intercept='1'/></feComponentTransfer><feBlend in2='b' mode='screen'/>")
        assertEquals(1.0,c.red,.01);assertEquals(1.0,c.blue,.01)
    }
    @Test fun invalidFunctionsAndUnsupportedStructuralChildrenFail()=FxTest.run {
        for(p in listOf("<feComponentTransfer><feFuncR type='oops'/></feComponentTransfer>","<feComponentTransfer><feFuncR type='gamma' exponent='NaN'/></feComponentTransfer>","<feComponentTransfer><rect/></feComponentTransfer>","<feBlend mode='plus-lighter'/>"))
            assertFailsWith<SvgException>(p) { render(p) }
    }
    @Test fun budgetRejectsBeforeLargeTransferAndFreshViewRecovers()=FxTest.run {
        val xml=source("<feComponentTransfer/>")
        assertFailsWith<SvgException> { val v=SvgView.parse(xml,SvgOptions(strict=true,maxFilterPixels=100));v.isManaged=false;Scene(Group(v));v.resize(64.0,64.0);v.layout();v.snapshot(null,null) }
        assertEquals(128/255.0,render("<feComponentTransfer/>").red,.01)
    }
    @Test fun authoredEightPanelSampleSurvivesRepeatedScaleAndProducesEvidence()=FxTest.run {
        val xml=checkNotNull(javaClass.getResource("/svg/extended-color-filters.svg")).readText()
        val v=SvgView.parse(xml,SvgOptions(strict=true)).apply {isManaged=false;Scene(Group(this))}
        for(scale in listOf(1.0,1.5,2.0,1.0)) {
            v.resize(600*scale,280*scale);v.applyCss();v.layout()
            val image=v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null)
            assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString())
            if(scale==1.0) {
                val b=java.awt.image.BufferedImage(image.width.toInt(),image.height.toInt(),java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until b.height) for(x in 0 until b.width) b.setRGB(x,y,image.pixelReader.getArgb(x,y))
                assertTrue(javax.imageio.ImageIO.write(b,"png",java.nio.file.Path.of("target","stage2-preview.png").toFile()))
            }
        }
    }

}
