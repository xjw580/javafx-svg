package club.xiaojiawei.svg

import javafx.scene.shape.SVGPath
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgReferenceTest {
    @Test fun instantiatesIndependentUseNodesWithInheritedStyle() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 40 20"><defs><path id="shape" d="M0 0L10 10"/></defs>
            <use id="one" href="#shape" fill="red"/><use id="two" href="#shape" x="20" fill="blue"/>
            </svg>""", SvgOptions(strict = true))
        val first = view.lookup("#one").lookup("#shape") as SVGPath
        val second = view.lookup("#two").lookup("#shape") as SVGPath
        assertNotSame(first, second)
        assertEquals(Color.RED, first.fill); assertEquals(Color.BLUE, second.fill)
        assertEquals(20.0, second.parent.layoutX)
        assertEquals(2, view.lookupAll(".svg-path").size)
    }

    @Test fun symbolUsesItsOwnViewport() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 40 40"><defs>
            <symbol id="symbol" viewBox="0 0 10 10"><path id="p" d="M0 0L10 10"/></symbol>
            </defs><use href="#symbol" x="10" y="5" width="20" height="20"/></svg>""", SvgOptions(strict = true))
        view.resize(40.0, 40.0); view.layout()
        val point = view.lookup("#p").localToScene(10.0, 10.0)
        assertEquals(30.0, point.x); assertEquals(25.0, point.y)
    }

    @Test fun rejectsCircularMissingAndExternalReferences() = FxTest.run {
        val doc = """<svg><defs><g id="a"><use href="#a"/></g></defs><use href="#a"/></svg>"""
        assertTrue(SvgView.parse(doc).diagnostics.any { it.message.contains("Circular") })
        assertFailsWith<SvgException> { SvgView.parse(doc, SvgOptions(strict = true)) }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><use href="#missing"/></svg>""", SvgOptions(strict = true)) }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><use href="https://example.com/icon.svg#p"/></svg>""", SvgOptions(strict = true)) }
    }
}
