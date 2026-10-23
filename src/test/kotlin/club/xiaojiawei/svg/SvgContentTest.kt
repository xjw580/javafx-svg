package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.image.ImageView
import javafx.scene.text.Text
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgContentTest {
    @Test fun rendersBasicTextWithInheritedFontAndAnchor() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 100 50" font-size="20"><g font-size="50%" fill="red">
            <text id="t" x="50" y="30" text-anchor="middle">Hello SVG</text></g></svg>""", SvgOptions(strict = true))
        view.resize(100.0, 50.0); view.layout()
        val text = view.lookup("#t") as Text
        assertEquals("Hello SVG", text.text)
        assertEquals(10.0, text.font.size)
        assertEquals(Color.RED, text.fill)
        assertEquals(50.0, text.x + text.layoutBounds.width / 2, 1e-8)
        assertEquals(30.0, text.y)
    }

    @Test fun decodesEmbeddedImageAndRejectsExternalResources() = FxTest.run {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII="
        val view = SvgView.parse("""<svg viewBox="0 0 20 20"><image id="i" width="20" height="20" href="data:image/png;base64,$png"/></svg>""", SvgOptions(strict = true))
        view.resize(20.0, 20.0); view.layout()
        val image = (view.lookup("#i") as Group).children.single() as ImageView
        assertEquals(1.0, image.image.width)
        assertEquals(20.0, image.localToParent(1.0, 1.0).x)
        val external = SvgView.parse("""<svg><image href="https://example.com/image.png"/></svg>""")
        assertTrue(external.diagnostics.single().message.contains("embedded"))
    }

    @Test fun reportsUnsupportedTextLayout() = FxTest.run {
        val view = SvgView.parse("""<svg><text>hello<tspan>world</tspan></text></svg>""")
        assertTrue(view.diagnostics.any { it.message.contains("tspan") })
    }
}
