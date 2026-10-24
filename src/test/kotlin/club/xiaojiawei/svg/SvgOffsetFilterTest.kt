package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgOffsetFilterTest {
    private fun view(
        primitive: String = """dx="15" dy="25"""",
        filter: String = """filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"""",
        content: String = """<rect id="r" x="20" y="20" width="20" height="20" fill="red" filter="url(#f)"/>""",
        definitions: String = ""
    ): SvgView = SvgView.parse("""<svg viewBox="0 0 100 100"><defs>$definitions<filter id="f" $filter>
        <feOffset $primitive/></filter></defs>$content</svg>""", SvgOptions(strict = true))

    private fun pixels(view: SvgView, size: Double = 100.0): WritableImage {
        view.isManaged = false
        if (view.scene == null) Scene(Group(view))
        view.resize(size, size); view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }

    @Test fun displacesSourceGraphicWithoutLeavingTheOriginal() = FxTest.run {
        val v = view()
        val p = pixels(v).pixelReader
        assertEquals(Color.RED, p.getColor(40, 50))
        assertEquals(0.0, p.getColor(25, 25).opacity)
        assertEquals(0.0, p.getColor(60, 50).opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun inputIsClippedBeforeOffsetAndOutputAfterOffset() = FxTest.run {
        val filter = """filterUnits="userSpaceOnUse" x="30" y="0" width="50" height="100""""
        val a = pixels(view("""dx="20" dy="0"""", filter)).pixelReader
        assertEquals(0.0, a.getColor(45, 25).opacity)
        assertEquals(Color.RED, a.getColor(55, 25))
        val b = pixels(view("""dx="20" x="55" y="0" width="5" height="100"""", filter)).pixelReader
        assertEquals(0.0, b.getColor(54, 25).opacity)
        assertEquals(Color.RED, b.getColor(56, 25))
        assertEquals(0.0, b.getColor(61, 25).opacity)
    }

    @Test fun sourceAlphaRemainsBlackAndTracksCssPaintAndGeometry() = FxTest.run {
        val v = view("""in="SourceAlpha" dx="15" dy="25"""")
        val r = v.lookup("#r") as Rectangle
        r.style = "-fx-fill: rgba(0,128,255,0.5);"
        var p = pixels(v).pixelReader.getColor(40, 50)
        assertEquals(0.0, p.red); assertEquals(0.0, p.blue)
        assertEquals(.5, p.opacity, .02)
        r.width = 5.0
        p = pixels(v).pixelReader.getColor(45, 50)
        assertEquals(0.0, p.opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun objectBoundingBoxAndPercentDisplacementTrackResizing() = FxTest.run {
        val v = view("""dx=".5" dy="25%"""",
            """primitiveUnits="objectBoundingBox" x="-100%" y="-100%" width="300%" height="300%"""")
        assertEquals(Color.RED, pixels(v).pixelReader.getColor(35, 30))
        assertEquals(Color.RED, pixels(v, 200.0).pixelReader.getColor(70, 60))
        val percent = view("""dx="10%" dy="0"""")
        assertEquals(Color.RED, pixels(percent).pixelReader.getColor(35, 25))
    }

    @Test fun ownerTransformAndSvgClipApplyToTheFinalOutput() = FxTest.run {
        val v = view("""dx="15" dy="0"""",
            content = """<rect id="r" x="20" y="20" width="20" height="20" fill="red"
                transform="translate(10 10)" filter="url(#f)" clip-path="url(#c)"/>""",
            definitions = """<clipPath id="c"><rect width="45" height="100"/></clipPath>""")
        val p = pixels(v).pixelReader
        assertEquals(Color.RED, p.getColor(50, 35))
        assertEquals(0.0, p.getColor(60, 35).opacity)
    }

    @Test fun rejectsUnsupportedInputsAndInvalidDisplacementWithoutHidingDiagnostics() = FxTest.run {
        for (attributes in listOf("""in="missing"""", """dx="NaN"""")) {
            assertFailsWith<SvgException> { view(attributes).also { pixels(it) } }
            val loose = SvgView.parse("""<svg><defs><filter id="f"><feOffset $attributes/></filter></defs>
                <rect width="10" height="10" fill="red" filter="url(#f)"/></svg>""")
            assertEquals(Color.RED, pixels(loose).pixelReader.getColor(2, 2))
            assertTrue(loose.diagnostics.isNotEmpty())
        }
    }
}
