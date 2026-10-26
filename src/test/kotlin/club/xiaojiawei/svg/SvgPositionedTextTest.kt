package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.Parent
import javafx.scene.Node
import javafx.scene.text.Text
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgPositionedTextTest {
    private fun view(text:String, width:Double=400.0):SvgView = SvgView.parse("""<svg width='400' height='150' viewBox='0 0 400 150'>$text</svg>""",SvgOptions(strict=true)).apply {
        isManaged=false; animationAutoPlay=false;Scene(Group(this));resize(width,150.0);applyCss();layout()
    }
    private fun glyphs(v:Node):List<Text> = buildList {
        fun visit(n:Node) { if(n is Text && "svg-text-run" in n.styleClass) add(n);if(n is Parent)n.childrenUnmodifiable.forEach(::visit) }
        visit(v)
    }
    @Test fun simpleTextKeepsSingleNativeRun()=FxTest.run {
        val v=view("<text x='10' y='40'>office</text>")
        assertEquals(1,v.lookupAll(".svg-shape").filterIsInstance<Text>().size)
    }
    @Test fun coordinateListsAddressEachCharacter()=FxTest.run {
        val gs=glyphs(view("<text x='10 40,70' y='30 40 50' dx='1 2 3' dy='4 5 6'>ABC</text>"))
        assertEquals(listOf("A","B","C"),gs.map {it.text})
        assertEquals(listOf(11.0,42.0,73.0),gs.map {it.x});assertEquals(listOf(34.0,45.0,56.0),gs.map {it.y})
    }
    @Test fun outerIndicesAdvanceAcrossNestedScopesAndInnerValuesOverride()=FxTest.run {
        val gs=glyphs(view("<text x='10 20 30 40 50' y='40'>A<tspan x='100 120'>BC</tspan>DE</text>"))
        assertEquals(listOf(10.0,100.0,120.0,40.0,50.0),gs.map {it.x})
    }
    @Test fun normalizedWhitespaceAndSupplementaryCodePointsConsumeOneEntry()=FxTest.run {
        val gs=glyphs(view("<text x='10 20 30 40 50' y='40'> A <tspan>😀B</tspan> </text>"))
        assertEquals(listOf("A"," ","😀","B"),gs.map {it.text})
        assertEquals(listOf(10.0,20.0,30.0,40.0),gs.map {it.x})
    }
    @Test fun lastRotationRepeatsAndInnerRotationOverridesThenReturns()=FxTest.run {
        val gs=glyphs(view("<text x='10 30 50 70' y='40' rotate='0 30'>A<tspan rotate='90'>BC</tspan>D</text>"))
        val angles=gs.map {Math.toDegrees(kotlin.math.atan2(it.transforms.single().myx,it.transforms.single().mxx))}
        assertEquals(listOf(0.0,90.0,90.0,30.0),angles.map { kotlin.math.round(it) })
    }
    @Test fun absoluteCoordinatesStartIndependentAnchoredChunks()=FxTest.run {
        val gs=glyphs(view("<text x='100 200' y='40' text-anchor='middle'>AB</text>"))
        assertEquals(100.0-gs[0].layoutBounds.width/2,gs[0].x,1.0)
        assertEquals(200.0-gs[1].layoutBounds.width/2,gs[1].x,1.0)
    }
    @Test fun negativeDxAnchorsAdvanceExtentsAsSvg2AlgorithmSpecifies()=FxTest.run {
        for ((anchor,fraction) in listOf("start" to 0.0,"middle" to 0.5,"end" to 1.0)) {
            val gs=glyphs(view("<text x='50' y='40' dx='0 -100' text-anchor='$anchor'>AB</text>"))
            val left=gs.minOf {it.x}
            val right=gs.maxOf {it.x+it.layoutBounds.width}
            assertEquals(50.0,left+fraction*(right-left),1.0)
            assertTrue(gs[1].x<gs[0].x)
        }
    }
    @Test fun absoluteResetsDiscardOnlyTheirAxisLengthAdjustment()=FxTest.run {
        // Fixed advance callbacks isolate SVG positioning from platform font metric differences.
        fun positions(adjust:String, reset:Map<String,String>, anchor:Double=0.0):List<Text> {
            val root=SvgTextLayout.Scope(mapOf("x" to "10", "y" to "40"),textLength="100",lengthAdjust=adjust)
            val inner=SvgTextLayout.Scope(reset+mapOf("dx" to "5 2", "dy" to "3 4"))
            val layout=SvgTextLayout(SvgLengthCache(),SvgViewport(400.0,150.0))
            for(i in 0..3) layout.runs += SvgTextLayout.Run(Text(('A'.code+i).toChar().toString()),
                if(i in 1..2)listOf(root,inner) else listOf(root),anchor) { 10.0 }
            layout.position()
            return layout.runs.map {it.text}
        }
        for(adjust in listOf("spacing","spacingAndGlyphs")) {
            val base=positions(adjust,emptyMap())
            val xReset=positions(adjust,mapOf("x" to "100"))
            assertEquals(105.0,xReset[1].x,0.0001)
            assertEquals(base[2].x-base[1].x,xReset[2].x-xReset[1].x,0.0001)
            assertEquals(base[3].x-base[2].x,xReset[3].x-xReset[2].x,0.0001)
            val yReset=positions(adjust,mapOf("y" to "80"))
            assertEquals(base.map {it.x},yReset.map {it.x})
            assertEquals(listOf(40.0,83.0,87.0,87.0),yReset.map {it.y})
            for(anchor in listOf(0.5,1.0)) {
                val anchored=positions(adjust,mapOf("x" to "100", "y" to "80"),anchor)
                val scales=anchored.map {it.transforms.single().mxx}
                val left=(1..3).minOf {anchored[it].x}
                val right=(1..3).maxOf {anchored[it].x+10.0*scales[it]}
                assertEquals(105.0,left+anchor*(right-left),0.0001)
            }
        }
    }
    @Test fun nestedTextLengthWithAbsoluteResetsRendersWithoutDroppingCombination()=FxTest.run {
        for(adjust in listOf("spacing","spacingAndGlyphs")) {
            val gs=glyphs(view("<text x='10' y='40' textLength='180' lengthAdjust='$adjust'>A<tspan x='100' y='70' dx='5 2' textLength='60' lengthAdjust='$adjust'>BC</tspan>D</text>"))
            assertEquals(105.0,gs[1].x,0.001)
            assertEquals(70.0,gs[1].y,0.001)
            assertEquals(60.0,gs[2].localToParent(gs[2].layoutBounds).maxX-gs[1].localToParent(gs[1].layoutBounds).minX,1.5)
        }
    }
    @Test fun nestedTextLengthSpacingPreservesInnerLength()=FxTest.run {
        val gs=glyphs(view("<text x='10' y='40' textLength='150'>A<tspan textLength='50'>BC</tspan>D</text>"))
        assertEquals(50.0,gs[2].x+gs[2].layoutBounds.width-gs[1].x,1.0)
        assertEquals(150.0,gs.last().x+gs.last().layoutBounds.width-gs.first().x,1.0)
    }
    @Test fun nestedSpacingAndGlyphsComposesScales()=FxTest.run {
        val gs=glyphs(view("<text x='10' y='40' textLength='180' lengthAdjust='spacingAndGlyphs'>A<tspan textLength='60' lengthAdjust='spacingAndGlyphs'>BC</tspan>D</text>"))
        val end=gs.last().localToParent(gs.last().layoutBounds).maxX
        val start=gs.first().localToParent(gs.first().layoutBounds).minX
        assertEquals(180.0,end-start,1.5)
        assertTrue(gs.all {it.transforms.single().mxx>0})
        assertEquals(60.0,gs[2].localToParent(gs[2].layoutBounds).maxX-gs[1].localToParent(gs[1].layoutBounds).minX,1.5)
    }
    @Test fun firstDxDoesNotConsumeTargetLengthAndFollowingTextAdvances()=FxTest.run {
        val gs=glyphs(view("<text x='10' y='40'>A<tspan dx='10' textLength='60'>BC</tspan>D</text>"))
        assertEquals(60.0,gs[2].x+gs[2].layoutBounds.width-gs[1].x,1.0)
        assertEquals(gs[2].x+gs[2].layoutBounds.width,gs[3].x,1.0)
    }
    @Test fun fontsDecorationsAndRepeatedResizeStayLive()=FxTest.run {
        val v=view("<text x='20 60 100' y='60' rotate='0 15 30' text-decoration='underline overline'>A<tspan font-size='28' fill='red'>B</tspan>C</text>")
        for(w in listOf(400.0,800.0,400.0)) {v.resize(w,w*150/400);v.applyCss();v.layout();v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null);assertTrue(v.diagnostics.isEmpty())}
        assertEquals(28.0,glyphs(v)[1].font.size)
        assertTrue(v.lookupAll(".svg-text-overline").isNotEmpty())
    }
    @Test fun invalidListsLengthsAndContextualSplittingAreDiagnosed()=FxTest.run {
        for(t in listOf("<text x='1,,2'>AB</text>","<text rotate='NaN'>AB</text>","<text><tspan textLength='-1'>AB</tspan></text>","<text rotate='0'>A&#x301;</text>","<text rotate='0'>سلام</text>"))
            assertFailsWith<SvgException>(t){view(t)}
    }
    @Test fun expandedGlyphBudgetIsCheckedBeforeAllocation()=FxTest.run {
        assertFailsWith<SvgException> { SvgView.parse("<svg><text rotate='0'>"+"A".repeat(1000)+"</text></svg>",SvgOptions(strict=true,maxElements=20)) }
    }
    @Test fun sampleRepeatedScaleAndSnapshot()=FxTest.run {
        val source=checkNotNull(javaClass.getResource("/svg/positioned-text.svg")).readText()
        val v=SvgView.parse(source,SvgOptions(strict=true)).apply { isManaged=false;Scene(Group(this));animationAutoPlay=false }
        for(scale in listOf(1.0,1.5,2.0,1.0)) {
            v.resize(640*scale,300*scale);v.applyCss();v.layout()
            val image=v.snapshot(SnapshotParameters().apply{fill=Color.TRANSPARENT},null)
            assertTrue(v.diagnostics.isEmpty(),v.diagnostics.toString())
            if(scale==1.0) {
                val png=java.awt.image.BufferedImage(image.width.toInt(),image.height.toInt(),java.awt.image.BufferedImage.TYPE_INT_ARGB)
                for(y in 0 until png.height)for(x in 0 until png.width)png.setRGB(x,y,image.pixelReader.getArgb(x,y))
                assertTrue(javax.imageio.ImageIO.write(png,"png",java.nio.file.Path.of("target","stage5-positioned-text-preview.png").toFile()))
            }
        }
    }
    @Test fun outerGlyphScalingKeepsNestedInternalDxAndCanCollapseFreeGlyphs()=FxTest.run {
        for(target in listOf(60,180)) {
            val gs=glyphs(view("<text x='10' y='40' textLength='$target' lengthAdjust='spacingAndGlyphs'>A<tspan dx='10 5' textLength='60'>BC</tspan>D</text>"))
            val child=gs[2].localToParent(gs[2].layoutBounds).maxX-gs[1].localToParent(gs[1].layoutBounds).minX
            assertEquals(60.0,child,1.5)
            assertTrue(gs.all {it.transforms.single().mxx.isFinite()})
        }
        assertFailsWith<SvgException> { view("<text textLength='20' lengthAdjust='spacingAndGlyphs'>A<tspan textLength='60'>BC</tspan>D</text>") }
    }
}
