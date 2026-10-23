package club.xiaojiawei.svg

import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.RadialGradient
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgGradientTest {
    @Test fun gradientCoordinatesIgnoreStrokeAndHandleNonuniformScale() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 200 100"><defs>
            <linearGradient id="g" x2="100%" y2="100%"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient>
            </defs><rect id="r" width="200" height="100" fill="url(#g)" stroke="black" stroke-width="20"/></svg>""", SvgOptions(strict = true))
        val gradient = (view.lookup("#r") as Rectangle).fill as LinearGradient
        assertFalse(gradient.isProportional)
        // The scalar gradient at (200, 0) is one half, including on a non-square bounding box.
        val dx = gradient.endX - gradient.startX
        val dy = gradient.endY - gradient.startY
        assertEquals(.5, 200 * dx / (dx * dx + dy * dy), 1e-8)
        assertEquals(Color.RED, gradient.stops.first().color)
    }

    @Test fun referencesStopsAndAppliesOpacity() = FxTest.run {
        val view = SvgView.parse("""<svg><defs>
            <linearGradient id="base"><stop stop-color="red" stop-opacity=".5"/><stop offset="1" stop-color="blue"/></linearGradient>
            <linearGradient id="g" href="#base" gradientTransform="translate(.2 0)"/>
            <radialGradient id="rad"><stop stop-color="white"/><stop offset="1" stop-color="black"/></radialGradient>
            </defs><rect id="r" width="100" height="100" fill="url(#g)" fill-opacity=".5"/>
            <circle id="c" r="10" fill="url(#rad)"/></svg>""", SvgOptions(strict = true))
        val gradient = (view.lookup("#r") as Rectangle).fill as LinearGradient
        assertEquals(20.0, gradient.startX, 1e-8)
        assertEquals(.25, gradient.stops.first().color.opacity, 1e-8)
        assertIs<RadialGradient>((view.lookup("#c") as javafx.scene.shape.Circle).fill)
    }

    @Test fun missingPaintCanUseFallbackButIsReported() = FxTest.run {
        val view = SvgView.parse("""<svg><rect id="r" width="10" height="10" fill="url(#missing) red"/></svg>""")
        assertEquals(Color.RED, (view.lookup("#r") as Rectangle).fill)
        assertTrue(view.diagnostics.any { it.message.contains("Missing paint") })
        assertFailsWith<SvgException> {
            SvgView.parse("""<svg><defs><linearGradient id="a" href="#a"/></defs><rect width="10" height="10" fill="url(#a)"/></svg>""", SvgOptions(strict = true))
        }
    }
}
