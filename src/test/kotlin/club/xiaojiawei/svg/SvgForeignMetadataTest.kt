package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgForeignMetadataTest {
    @Test fun editorMetadataIsExcludedFromRenderingWithoutStrictDiagnostics() = FxTest.run {
        val xml = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:s="http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd"
            xmlns:i="http://www.inkscape.org/namespaces/inkscape" viewBox="0 0 64 64">
            <s:namedview id="editor"><i:guide position="10,10"/><rect id="private" width="64" height="64" fill="red"/></s:namedview>
            <rect id="visible" width="64" height="64" fill="blue"/></svg>"""
        val view = SvgView.parse(xml, SvgOptions(strict = true))
        Scene(Group(view)); view.resize(64.0, 64.0); view.applyCss(); view.layout()
        assertTrue(view.diagnostics.isEmpty())
        assertNull(view.lookup("#private"))
        assertNotNull(view.lookup("#visible"))
        assertEquals(1, view.lookupAll(".svg-rect").size)
    }

    @Test fun svgStylesInsidePrivateDataDoNotAffectTheGraphic() = FxTest.run {
        val view = SvgView.parse("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:editor="urn:editor" viewBox="0 0 20 20">
            <editor:data><style>@unsupported anything {} rect {fill:red}</style></editor:data>
            <rect id="p" width="20" height="20" fill="blue"/></svg>""", SvgOptions(strict = true))
        Scene(Group(view)); view.resize(20.0, 20.0); view.applyCss(); view.layout()
        assertEquals(Color.BLUE, (view.lookup("#p") as Rectangle).fill)
        assertTrue(view.diagnostics.isEmpty())
    }

    @Test fun unknownSvgGraphicsAndForeignRootsStillFailAndParsingLimitsStillApply() = FxTest.run {
        for (name in listOf("unknownDrawing", "script"))
            assertFailsWith<SvgException> { SvgView.parse("<svg><" + name + "/></svg>", SvgOptions(strict = true)) }
        // Supporting bounded XHTML does not make arbitrary HTML elements renderable.
        assertFailsWith<SvgException> {
            SvgView.parse("""<svg><foreignObject width="20" height="20">
                <p xmlns="http://www.w3.org/1999/xhtml">Not in the supported subset</p>
                </foreignObject></svg>""", SvgOptions(strict = true))
        }
        assertFailsWith<SvgException> { SvgLoader.parse("""<svg xmlns="urn:editor"/>""") }
        assertFailsWith<SvgException> {
            SvgLoader.parse("""<svg xmlns:e="urn:editor"><e:data><e:item/><e:item/></e:data></svg>""", SvgOptions(maxElements = 2))
        }
    }

    @Test fun privateChildrenDoNotInvalidateShapesTextOrFilterPrimitives() = FxTest.run {
        val view = SvgView.parse("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:e="urn:editor" viewBox="0 0 100 100">
            <defs><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
                <e:labels/><feOffset dx="2"><e:parameter-note/></feOffset></filter>
                <path id="curve" d="M0 70H100"/></defs>
            <path id="p" d="M0 0H20V20H0z" fill="blue" filter="url(#f)"><e:info/></path>
            <text y="40">A<tspan>B<e:note/>C</tspan></text>
            <text><e:note/><textPath href="#curve">D<e:note/></textPath></text>
            </svg>""", SvgOptions(strict = true))
        Scene(Group(view)); view.resize(100.0, 100.0); view.applyCss(); view.layout()
        assertTrue(view.diagnostics.isEmpty())
        assertNotNull(view.lookup("#p"))
    }
}
