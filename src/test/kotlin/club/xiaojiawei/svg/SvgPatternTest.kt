package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.shape.Shape
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgPatternTest {
    private fun view(attrs: String = """patternUnits="userSpaceOnUse" width="16" height="16" """, body: String = """<rect id="tile" width="8" height="16" fill="blue"/>""",
        target: String = """<rect id="r" width="64" height="64" fill="url(#p)"/>""", extra: String = "", options: SvgOptions = SvgOptions(strict=true)) =
        SvgView.parse("""<svg viewBox="0 0 64 64"><defs>$extra<pattern id="p" $attrs>$body</pattern></defs>$target</svg>""",options).apply {
            isManaged=false; Scene(Group(this)); resize(64.0,64.0); applyCss(); layout()
        }
    private fun pixel(v: SvgView,x: Int,y: Int)=v.snapshot(SnapshotParameters().apply { fill=Color.TRANSPARENT },null).pixelReader.getColor(x,y)

    @Test fun repeatsAndReusesRasterUntilCssChanges() = FxTest.run {
        val v=view()
        assertTrue(pixel(v,4,32).blue>.9); assertTrue(pixel(v,12,32).opacity<.1)
        val r=v.lookup("#r") as Shape
        val paint=r.fill
        v.requestLayout(); v.layout()
        assertSame(paint,r.fill)
        (v.lookup("#tile") as Shape).style="-fx-fill: red;"
        v.applyCss(); v.layout()
        assertTrue(pixel(v,4,32).red>.9)
        assertNotSame(paint,r.fill)
        v.requestLayout(); v.layout()
        assertSame(r.fill,r.fill)
    }
    @Test fun boundingBoxUnitsAndViewBox() = FxTest.run {
        val v=view("""width=".25" height=".25" patternContentUnits="objectBoundingBox" """,
            """<rect width=".125" height=".25" fill="blue"/>""")
        assertTrue(pixel(v,4,32).blue>.9); assertTrue(pixel(v,12,32).opacity<.1)
        val b=view("""width=".25" height=".25" viewBox="0 0 16 16" """)
        assertTrue(pixel(b,4,32).blue>.9); assertTrue(pixel(b,12,32).opacity<.1)
    }
    @Test fun transformAndTemplateFallback() = FxTest.run {
        val v=view("""patternUnits="userSpaceOnUse" width="16" height="16" patternTransform="rotate(90)" """)
        assertTrue(pixel(v,32,4).blue>.9); assertTrue(pixel(v,32,12).opacity<.1)
        val t=view("""href="#base" ""","",extra="""<pattern id="base" patternUnits="userSpaceOnUse" width="16" height="16"><rect width="8" height="16" fill="red"/></pattern>""")
        assertTrue(pixel(t,4,32).red>.9); assertTrue(pixel(t,12,32).opacity<.1)
    }
    @Test fun opacityStrokeAndIndependentInstances() = FxTest.run {
        val v=view(target="""<rect id="r" x="4" y="4" width="56" height="56" fill="none" stroke="url(#p)" stroke-width="8" stroke-opacity=".5"/>""")
        assertEquals(.5,pixel(v,4,32).opacity,.03)
        val a=view()
        val b=SvgView(a.document).apply { isManaged=false; Scene(Group(this)); resize(64.0,64.0); applyCss(); layout() }
        assertNotSame((a.lookup("#r") as Shape).fill,(b.lookup("#r") as Shape).fill)
        (a.lookup("#tile") as Shape).style="-fx-fill:red;"
        a.applyCss(); a.layout()
        assertTrue(pixel(b,4,32).blue>.9)
    }
    @Test fun cyclesBudgetAndDimensionsAreBounded() = FxTest.run {
        assertFailsWith<SvgException> { view(body="""<rect width="16" height="16" fill="url(#p)"/>""") }
        assertFailsWith<SvgException> { view("""href="#p" """) }
        assertFailsWith<SvgException> { view(options=SvgOptions(strict=true,maxPatternPixels=16)) }
        assertFailsWith<SvgException> { view("""width="-1" height="1" """) }
        assertTrue(pixel(view("""width="0" height="1" """),4,32).opacity<.1)
        val loose=view(options=SvgOptions(maxPatternPixels=16))
        assertTrue(loose.diagnostics.any { "maxPatternPixels" in it.message })
        assertTrue(pixel(loose,4,32).opacity<.1)
    }
}
