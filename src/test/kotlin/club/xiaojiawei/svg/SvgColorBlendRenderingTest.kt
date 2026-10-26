package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class SvgColorBlendRenderingTest {
    private val identity="1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 0 0 0 1 0"
    private fun xml(primitives:String, filter:String="color-interpolation-filters='sRGB'", content:String="<rect x='20' y='20' width='40' height='40' fill='red' filter='url(#f)'/>")=
        """<svg width="100" height="100" viewBox="0 0 100 100"><defs><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" $filter>$primitives</filter></defs>$content</svg>"""
    private fun view(p:String,filter:String="color-interpolation-filters='sRGB'",content:String="<rect x='20' y='20' width='40' height='40' fill='red' filter='url(#f)'/>",options:SvgOptions=SvgOptions(strict=true))=SvgView.parse(xml(p,filter,content),options)
    private fun image(v:SvgView,scale:Double=1.0):WritableImage {
        v.isManaged=false;if(v.scene==null)Scene(Group(v))
        v.resize(100*scale,100*scale);v.applyCss();v.layout()
        return v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
    }
    private fun at(p:String,filter:String="color-interpolation-filters='sRGB'")=image(view(p,filter)).pixelReader.getColor(40,40)
    private fun close(expected:Color,actual:Color,tolerance:Double=.012) {
        assertEquals(expected.red,actual.red,tolerance);assertEquals(expected.green,actual.green,tolerance)
        assertEquals(expected.blue,actual.blue,tolerance);assertEquals(expected.opacity,actual.opacity,tolerance)
    }
    @Test fun matrixDefaultsAndIdentityPreserveSourceAndTransparentSpace()=FxTest.run {
        for(p in listOf("<feColorMatrix/>","<feColorMatrix type='matrix'/>","<feColorMatrix values='$identity'/>")) {
            val v=view(p);val out=image(v).pixelReader
            close(Color.RED,out.getColor(40,40));assertEquals(0.0,out.getColor(80,80).opacity)
            assertTrue(v.diagnostics.isEmpty())
        }
    }
    @Test fun matrixTransformsStraightRgbThenPremultipliesNewAlpha()=FxTest.run {
        val content="<rect x='20' y='20' width='40' height='40' fill='#804020' fill-opacity='.5' filter='url(#f)'/>"
        val p="<feColorMatrix values='0 1 0 0 0 0 0 1 0 0 1 0 0 0 0 0 0 0 .5 0'/>"
        close(Color.color(64/255.0,32/255.0,128/255.0,.25),image(view(p,content=content)).pixelReader.getColor(40,40),.025)
    }
    @Test fun alphaBiasCanCreateColorOutsideOriginalGeometryWithinSubregion()=FxTest.run {
        val p="<feColorMatrix x='10' y='10' width='75' height='75' values='0 0 0 0 .2 0 0 0 0 .4 0 0 0 0 .8 0 0 0 0 .5'/>"
        val out=image(view(p)).pixelReader
        close(Color.color(.2,.4,.8,.5),out.getColor(80,80))
        assertEquals(0.0,out.getColor(90,90).opacity)
    }
    @Test fun matrixClampsChannelsAndAlphaAndKeepsTransparentRgbZero()=FxTest.run {
        close(Color.color(1.0,0.0,.5,1.0),at("<feColorMatrix values='0 0 0 0 2 0 0 0 0 -2 0 0 0 0 .5 0 0 0 0 2'/>"))
        assertEquals(0.0,at("<feColorMatrix values='0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 -1'/>").opacity)
    }
    @Test fun saturateZeroUsesSvgLuminanceAndDefaultIsIdentity()=FxTest.run {
        close(Color.color(.213,.213,.213),at("<feColorMatrix type='saturate' values='0'/>"))
        close(Color.RED,at("<feColorMatrix type='saturate'/>"))
        close(Color.RED,at("<feColorMatrix type='saturate' values='1'/>"))
    }
    @Test fun hueRotateZeroAndFullTurnAreIdentityWhileQuarterTurnUsesSpecMatrix()=FxTest.run {
        close(Color.RED,at("<feColorMatrix type='hueRotate'/>"))
        close(Color.RED,at("<feColorMatrix type='hueRotate' values='360'/>"))
        close(Color.color(0.0,.356,0.0),at("<feColorMatrix type='hueRotate' values='90'/>"))
    }
    @Test fun luminanceToAlphaIgnoresInputAlphaAndOutputsBlackRgb()=FxTest.run {
        val content="<rect x='20' y='20' width='40' height='40' fill='red' fill-opacity='.5' filter='url(#f)'/>"
        close(Color.color(0.0,0.0,0.0,.2126),image(view("<feColorMatrix type='luminanceToAlpha'/>",content=content)).pixelReader.getColor(40,40))
    }
    @Test fun colorMatrixUsesLinearRgbByDefaultAndPrimitiveOverrideWorks()=FxTest.run {
        val p="<feColorMatrix type='saturate' values='0'/>"
        val linear=at(p,"");val srgb=at(p)
        assertEquals(.499,linear.red,.012);assertEquals(.213,srgb.red,.012)
        close(srgb,at(p.replace("<feColorMatrix","<feColorMatrix color-interpolation-filters='sRGB'"),""))
    }
    @Test fun blendAllFiveModesMatchOpaqueNumericOracle()=FxTest.run {
        val prefix="<feFlood flood-color='#4080c0' result='back'/><feFlood flood-color='#804020' result='front'/>"
        val a=doubleArrayOf(128/255.0,64/255.0,32/255.0);val b=doubleArrayOf(64/255.0,128/255.0,192/255.0)
        for(mode in listOf("normal","multiply","screen","darken","lighten")) {
            val e=DoubleArray(3) {i->when(mode){"normal"->a[i];"multiply"->a[i]*b[i];"screen"->a[i]+b[i]-a[i]*b[i];"darken"->minOf(a[i],b[i]);else->maxOf(a[i],b[i])}}
            close(Color.color(e[0],e[1],e[2]),at(prefix+"<feBlend in='front' in2='back' mode='$mode'/>"))
        }
    }
    @Test fun blendPartialAlphaUsesSourceOverCoverageRatherThanChannelInterpolation()=FxTest.run {
        val p="<feFlood flood-color='blue' flood-opacity='.5' result='back'/><feFlood flood-color='red' flood-opacity='.5' result='front'/>"
        close(Color.color(2.0/3,0.0,1.0/3,.75),at(p+"<feBlend in='front' in2='back'/>"))
        close(Color.color(1.0/3,0.0,1.0/3,.75),at(p+"<feBlend in='front' in2='back' mode='multiply'/>"))
        close(Color.color(2.0/3,0.0,2.0/3,.75),at(p+"<feBlend in='front' in2='back' mode='screen'/>"))
    }
    @Test fun transparentBlendInputsPreserveTheOtherInput()=FxTest.run {
        for(mode in listOf("normal","multiply","screen","darken","lighten")) {
            close(Color.RED,at("<feFlood flood-opacity='0' result='clear'/><feBlend in='SourceGraphic' in2='clear' mode='$mode'/>"))
            close(Color.RED,at("<feFlood flood-opacity='0' result='clear'/><feBlend in='clear' in2='SourceGraphic' mode='$mode'/>"))
        }
    }
    @Test fun blendLinearRgbAndSrgbDifferAndPerPrimitiveConversionIsApplied()=FxTest.run {
        val p="<feFlood flood-color='blue' result='back'/><feFlood flood-color='red' flood-opacity='.5' result='front'/><feBlend in='front' in2='back'/>"
        close(Color.color(.735,0.0,.735),at(p,""),.02)
        close(Color.color(.5,0.0,.5),at(p),.02)
        close(at(p),at(p.replace("<feBlend","<feBlend color-interpolation-filters='sRGB'"),""))
    }
    @Test fun namedSecondInputSurvivesUnrelatedOperationsAndDuplicateNamesUseLatest()=FxTest.run {
        val p="<feFlood flood-color='red' result='back'/><feFlood flood-color='blue' result='back'/><feColorMatrix in='SourceGraphic'/><feOffset dx='0'/><feBlend in='SourceGraphic' in2='back' mode='screen'/>"
        close(Color.MAGENTA,at(p))
    }
    @Test fun omittedAndUnknownInputsUsePreviousResultAndFirstUsesSourceGraphic()=FxTest.run {
        close(Color.RED,at("<feBlend/>"))
        close(Color.BLUE,at("<feFlood flood-color='blue'/><feBlend/>"))
        close(Color.BLUE,at("<feFlood flood-color='blue'/><feColorMatrix in='future'/><feBlend in='missing' in2='future'/>"))
    }
    @Test fun blendSubregionUnionRetainsBothInputsAndExplicitClipWins()=FxTest.run {
        val prefix="<feFlood flood-color='blue' x='60' y='20' width='20' height='40' result='b'/><feBlend in='SourceGraphic' in2='b' mode='screen'"
        val p=image(view(prefix+"/>" )).pixelReader
        close(Color.RED,p.getColor(40,40));close(Color.BLUE,p.getColor(70,40));assertEquals(0.0,p.getColor(90,40).opacity)
        val q=image(view(prefix+" x='30' y='30' width='40' height='20'/><feColorMatrix/>" )).pixelReader
        close(Color.RED,q.getColor(40,40));assertEquals(0.0,q.getColor(20,40).opacity);assertEquals(0.0,q.getColor(75,40).opacity)
    }
    @Test fun matrixAlphaBiasHonorsCroppedInputDefaultButExplicitOutputCanExpand()=FxTest.run {
        val prefix="<feFlood flood-color='red' x='20' y='20' width='20' height='20' result='small'/>"
        val matrix="<feColorMatrix in='small' values='0 0 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 1'"
        val normal=image(view(prefix+matrix+"/>")).pixelReader
        close(Color.LIME,normal.getColor(30,30));assertEquals(0.0,normal.getColor(60,60).opacity)
        val expanded=image(view(prefix+matrix+" x='10' y='10' width='70' height='70'/>")).pixelReader
        close(Color.LIME,expanded.getColor(60,60));assertEquals(0.0,expanded.getColor(90,90).opacity)
    }
    @Test fun blendUnionOfTwoDisjointNamedInputsDoesNotDefaultToOneInputRegion()=FxTest.run {
        val p="<feFlood flood-color='red' x='10' y='20' width='20' height='20' result='a'/>"+
            "<feFlood flood-color='blue' x='60' y='20' width='20' height='20' result='b'/>"+
            "<feBlend in='a' in2='b' mode='screen'/>"
        val out=image(view(p)).pixelReader
        close(Color.RED,out.getColor(20,30));close(Color.BLUE,out.getColor(70,30))
        assertEquals(0.0,out.getColor(45,30).opacity);assertEquals(0.0,out.getColor(90,30).opacity)
    }
    @Test fun primitiveZeroOrNegativeSizeProducesTransparentOutput()=FxTest.run {
        for(p in listOf("<feColorMatrix width='0'/>","<feColorMatrix height='-1'/>","<feBlend width='0'/>","<feBlend height='-1'/>"))assertEquals(0.0,at(p).opacity)
    }
    @Test fun SyntacticallyValidWrongValueCountsPassSourceThrough()=FxTest.run {
        for(p in listOf("<feColorMatrix values='1 2'/>","<feColorMatrix values=''/>",
            "<feColorMatrix type='saturate' values='1 2'/>","<feColorMatrix type='hueRotate' values='1 2'/>")) {
            close(Color.RED,at(p))
        }
    }
    @Test fun malformedParametersAndUnsupportedModesProduceStrictDiagnostics()=FxTest.run {
        val invalid=listOf("<feColorMatrix type='unknown'/>","<feColorMatrix values='oops'/>","<feColorMatrix type='saturate' values='NaN'/>","<feColorMatrix type='saturate' values='1 nope'/>","<feColorMatrix type='hueRotate' values='90deg'/>","<feBlend mode='overlay'/>","<feBlend no-composite='true'/>","<feBlend mode='Multiply'/>","<feBlend in2='BackgroundImage'/>","<feColorMatrix><animate attributeName='values' values='0;1'/></feColorMatrix>")
        for(p in invalid)assertFailsWith<SvgException>(p){image(view(p))}
    }
    @Test fun pixelBudgetClearsFailedOutputAndDocumentReplacementRecovers()=FxTest.run {
        val v=view("<feColorMatrix/>",options=SvgOptions(maxFilterPixels=100))
        assertEquals(0.0,image(v).pixelReader.getColor(40,40).opacity)
        assertTrue(v.diagnostics.any {"maxFilterPixels" in it.message})
        v.document=SvgLoader.parse(xml("<feColorMatrix/>"),SvgOptions(strict=true))
        close(Color.RED,image(v).pixelReader.getColor(40,40));assertTrue(v.diagnostics.isEmpty())
    }
    @Test fun aggregateWorkBudgetRejectsLongColorMatrixChainWithoutStalePixels()=FxTest.run {
        val p=List(20){"<feColorMatrix/>"}.joinToString("")
        val v=view(p,options=SvgOptions())
        close(Color.RED,image(v).pixelReader.getColor(40,40))
        val large=image(v,8.0)
        assertTrue(v.diagnostics.any {"work limit" in it.message},v.diagnostics.toString())
        assertEquals(0.0,large.pixelReader.getColor(320,320).opacity)
        v.document=SvgLoader.parse(xml("<feBlend/>"),SvgOptions(strict=true))
        close(Color.RED,image(v).pixelReader.getColor(40,40))
    }
    @Test fun nineFilterExamplesRenderAtRepeatedPreviewScales()=FxTest.run {
        val v=SvgView.load(checkNotNull(javaClass.getResource("/svg/color-blend-filters.svg")),SvgOptions(strict=true))
        v.isManaged=false;Scene(Group(v))
        for(scale in listOf(1.0,1.25,1.5,2.0,1.0,2.0)) {
            v.resize(720*scale,550*scale);v.applyCss();v.layout()
            val pixels=v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null)
            assertTrue(v.diagnostics.isEmpty(),"scale=$scale ${v.diagnostics}")
            assertEquals(9,v.lookupAll(".svg-filter-output").size)
            if(scale==1.0) {
                val out=BufferedImage(pixels.width.toInt(),pixels.height.toInt(),BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until out.height)for(x in 0 until out.width)out.setRGB(x,y,pixels.pixelReader.getArgb(x,y))
                assertTrue(ImageIO.write(out,"png",Path.of("target","color-blend-filters-1x.png").toFile()))
            }
        }
        v.source=null
    }
}
