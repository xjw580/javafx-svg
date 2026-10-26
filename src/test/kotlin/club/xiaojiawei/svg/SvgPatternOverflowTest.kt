package club.xiaojiawei.svg
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*
class SvgPatternOverflowTest {
    private fun view(overflow:String,body:String="""<rect x="-4" y="-4" width="8" height="8" fill="red"/>""",extra:String="",options:SvgOptions=SvgOptions(strict=true))=SvgView.parse("""<svg width="40" height="40"><defs><pattern id="p" width="10" height="10" patternUnits="userSpaceOnUse" $overflow $extra>$body</pattern></defs><rect width="40" height="40" fill="url(#p)"/></svg>""",options).apply {isManaged=false;Scene(Group(this));resize(40.0,40.0);applyCss();layout()}
    private fun pixel(v:SvgView,x:Int,y:Int)=v.snapshot(SnapshotParameters().apply {fill=Color.TRANSPARENT},null).pixelReader.getColor(x,y)
    @Test fun defaultClipsButVisibleWrapsBothAxes()=FxTest.run {
        assertTrue(pixel(view(""),8,8).opacity<.1)
        assertTrue(pixel(view("overflow=\"visible\""),8,8).red>.9)
        assertTrue(pixel(view("style=\"overflow:visible\""),8,8).red>.9)
        assertTrue(pixel(view("overflow=\"visible\""),5,5).opacity<.1)
    }
    @Test fun overlapsComposeAlphaAndTransforms()=FxTest.run {
        val v=view("overflow=\"visible\"","""<rect x="-5" width="20" height="10" fill="red" fill-opacity=".5"/>""","patternTransform=\"rotate(90)\"")
        assertEquals(.75,pixel(v,5,5).opacity,.04)
    }
    @Test fun extremeOverflowRejectsBeforeSnapshot()=FxTest.run {
        assertFailsWith<SvgException> {view("overflow=\"visible\"","""<rect x="-100000" width="200000" height="10"/>""")}
    }
    @Test fun simultaneousRastersShareThePixelLimit()=FxTest.run {
        assertFailsWith<SvgException> {view("overflow=\"visible\"",options=SvgOptions(strict=true,maxPatternPixels=2000))}
    }
    @Test fun visibleOverflowWithViewBoxAndBoundingBoxUnits()=FxTest.run {
        val v=view("overflow=\"visible\"",extra="viewBox=\"0 0 20 20\"",body="""<rect x="-8" y="-8" width="16" height="16" fill="red"/>""")
        assertTrue(pixel(v,8,8).red>.9)
    }
    @Test fun failedOverflowDoesNotStarveFollowingValidPaint()=FxTest.run {
        val xml="""<svg width="40" height="40"><defs>
            <pattern id="bad" patternUnits="userSpaceOnUse" width="10" height="10" overflow="visible"><rect x="-100000" width="200000" height="10"/></pattern>
            <pattern id="good" patternUnits="userSpaceOnUse" width="10" height="10"><rect width="10" height="10" fill="red"/></pattern>
            </defs><rect width="10" height="10" fill="url(#bad)"/><rect x="20" width="10" height="10" fill="url(#good)"/></svg>"""
        val v=SvgView.parse(xml,SvgOptions(maxPatternPixels=1000)).apply{isManaged=false;Scene(Group(this));resize(40.0,40.0);applyCss();layout()}
        assertTrue(v.diagnostics.any{it.message.contains("overflow")})
        assertTrue(pixel(v,25,5).red>.9)
    }
}
