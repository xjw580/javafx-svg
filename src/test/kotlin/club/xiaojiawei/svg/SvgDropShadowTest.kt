package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgDropShadowTest {
    private fun view(primitive: String, filter: String = """x="-50%" y="-50%" width="200%" height="200%" color-interpolation-filters="sRGB" """,
                     style: String = ""): SvgView = SvgView.parse("""<svg viewBox="0 0 64 64">$style<defs><filter id="f" $filter>$primitive</filter></defs>
        <rect id="p" x="12" y="20" width="20" height="24" fill="red" filter="url(#f)"/></svg>""", SvgOptions(strict = true)).apply {
        isManaged = false; Scene(Group(this)); resize(64.0, 64.0); applyCss(); layout()
    }
    private fun pixel(v: SvgView, x: Int, y: Int) =
        v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null).pixelReader.getColor(x, y)

    @Test fun nativeShadowCompositesSourceOverFloodColour() = FxTest.run {
        val v = view("""<feDropShadow dx="10" dy="0" stdDeviation="1" flood-color="blue"/>""")
        val source = pixel(v, 20, 32); val shadow = pixel(v, 40, 32)
        assertTrue(source.red > .95 && source.blue < .05)
        assertTrue(shadow.blue > .95 && shadow.opacity > .5)
        assertTrue(v.diagnostics.isEmpty())
        (v.lookup("#p") as Shape).style = "-fx-fill: green;"
        v.applyCss(); v.requestLayout(); v.layout()
        assertTrue(pixel(v, 20, 32).green > .45)
        assertTrue(pixel(v, 40, 32).blue > .95)
    }

    @Test fun svgStyleFloodOpacityAndCurrentColorAreApplied() = FxTest.run {
        val v = view("""<feDropShadow id="shadow" dx="10" dy="0" stdDeviation="0"/>""",
            style = """<style>#shadow {flood-color:currentColor;flood-opacity:.25;color:blue}</style>""")
        val shadow = pixel(v, 40, 32)
        assertEquals(1.0, shadow.blue, .02)
        assertEquals(.25, shadow.opacity, .02)
    }

    @Test fun filterRegionClipsTheOffsetAndZeroDeviationIsSharp() = FxTest.run {
        val primitive = """<feDropShadow dx="10" dy="0" stdDeviation="0" flood-color="blue"/>"""
        val v = view(primitive, """color-interpolation-filters="sRGB" """)
        assertEquals(0.0, pixel(v, 40, 32).opacity)
        assertTrue(pixel(v, 20, 32).red > .95)
        val expanded = view(primitive)
        assertTrue(pixel(expanded, 40, 32).opacity > .95)
        assertEquals(0.0, pixel(expanded, 43, 32).opacity)
    }

    @Test fun unsupportedChainsAndKernelOverflowAreDiagnosed() = FxTest.run {
        assertFailsWith<SvgException> { view("""<feDropShadow/><feGaussianBlur/>""") }
        assertFailsWith<SvgException> { view("""<feDropShadow stdDeviation="1000"/>""") }
        assertFailsWith<SvgException> { view("""<feDropShadow in="unknown"/>""") }
        assertFailsWith<SvgException> { view("""<feDropShadow flood-color="blue"/>""",
            """color-interpolation-filters="linearRGB" """) }
    }
}
