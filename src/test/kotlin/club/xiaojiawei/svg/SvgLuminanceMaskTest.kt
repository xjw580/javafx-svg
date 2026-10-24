package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgLuminanceMaskTest {
    @Test fun defaultMaskUsesLuminanceMultipliedByAlpha() = FxTest.run {
        val view = view("""<svg viewBox="0 0 60 20"><defs><mask id="m">
            <rect width="20" height="20" fill="white"/>
            <rect x="20" width="20" height="20" fill="black"/>
            <rect x="40" width="20" height="20" fill="#808080" opacity=".5"/>
            </mask></defs><rect width="60" height="20" fill="red" mask="url(#m)"/></svg>""", 60.0, 20.0)
        val image = snapshot(view)
        assertEquals(1.0, image.pixelReader.getColor(10, 10).opacity, .02)
        assertEquals(0.0, image.pixelReader.getColor(30, 10).opacity)
        assertEquals(.25, image.pixelReader.getColor(50, 10).opacity, .02)
        assertEquals(1.0, image.pixelReader.getColor(50, 10).red, .03)
    }

    @Test fun primaryColorsUseLuminanceWeightsInsteadOfAverageOrPremultipliedRgb() = FxTest.run {
        val view = view("""<svg viewBox="0 0 30 20"><defs><mask id="m" mask-type="luminance">
            <rect width="10" height="20" fill="#ff0000"/>
            <rect x="10" width="10" height="20" fill="#00ff00"/>
            <rect x="20" width="10" height="20" fill="#0000ff"/>
            </mask></defs><rect width="30" height="20" fill="white" mask="url(#m)"/></svg>""", 30.0, 20.0)
        val image = snapshot(view)
        for ((x, expected) in listOf(5 to .2126, 15 to .7152, 25 to .0722)) {
            assertEquals(expected, image.pixelReader.getColor(x, 10).opacity, .01)
        }
    }

    @Test fun linearRgbConvertsFinalMaskColorsBeforeComputingLuminance() = FxTest.run {
        for ((space, expected) in listOf("sRGB" to .251, "linearRGB" to .108, "auto" to .251)) {
            val view = view("""<svg viewBox="0 0 20 20"><defs><g color-interpolation="$space">
                <mask id="m"><rect width="20" height="20" fill="#808080" opacity=".5"/></mask>
                </g></defs><rect width="20" height="20" fill="blue" mask="url(#m)"/></svg>""", 20.0, 20.0)
            assertEquals(expected, snapshot(view).pixelReader.getColor(10, 10).opacity, .02)
        }
    }

    @Test fun maskTypeCanBeSpecifiedThroughStylesheetAndInlineStyle() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><style>mask { mask-type: luminance; }</style>
            <defs><mask id="m" mask-type="luminance" style="mask-type: alpha">
                <rect width="20" height="20" fill="black" opacity=".5"/>
            </mask></defs><rect width="20" height="20" fill="blue" mask="url(#m)"/></svg>""", 20.0, 20.0)
        assertEquals(.5, snapshot(view).pixelReader.getColor(10, 10).opacity, .02)
    }

    @Test fun cssColorChangesRefreshLuminanceAndThemeDoesNotTintMaskContent() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs><mask id="m">
            <rect id="color" width="20" height="20" fill="red"/></mask></defs>
            <rect width="20" height="20" fill="blue" mask="url(#m)"/></svg>""", 20.0, 20.0)
        val mask = view.lookup("#color") as Rectangle
        assertEquals(.2126, snapshot(view).pixelReader.getColor(10, 10).opacity, .01)
        mask.style = "-fx-fill: #00ff00;"
        view.applyCss(); view.layout()
        assertEquals(.7152, snapshot(view).pixelReader.getColor(10, 10).opacity, .02)
        mask.style = "-fx-fill: #0000ff;"
        view.applyCss(); view.layout()
        view.svgFill = Color.RED
        view.layout()
        val pixel = snapshot(view).pixelReader.getColor(10, 10)
        assertEquals(.0722, pixel.opacity, .01)
        assertEquals(1.0, pixel.red, .03)
        assertEquals(Color.BLUE, mask.fill)
    }

    @Test fun nestedDefaultMasksMultiplyTheirFinalAlpha() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs>
            <mask id="a"><rect width="20" height="20" fill="white" opacity=".5"/></mask>
            <mask id="b"><rect width="20" height="20" fill="white" opacity=".5" mask="url(#a)"/></mask>
            </defs><rect width="20" height="20" fill="blue" mask="url(#b)"/></svg>""", 20.0, 20.0)
        assertEquals(.25, snapshot(view).pixelReader.getColor(10, 10).opacity, .03)
    }

    @Test fun gradientMaskPreservesFadeAndCanClipGaussianOutput() = FxTest.run {
        val fade = view("""<svg viewBox="0 0 40 20"><defs>
            <linearGradient id="g"><stop stop-color="black"/><stop offset="1" stop-color="white"/></linearGradient>
            <mask id="m"><rect width="40" height="20" fill="url(#g)"/></mask>
            </defs><rect width="40" height="20" fill="blue" mask="url(#m)"/></svg>""", 40.0, 20.0)
        val image = snapshot(fade)
        assertTrue(image.pixelReader.getColor(1, 10).opacity < .1)
        assertEquals(.5, image.pixelReader.getColor(20, 10).opacity, .05)
        assertTrue(image.pixelReader.getColor(38, 10).opacity > .9)

        val blur = view("""<svg viewBox="0 0 100 100"><defs>
            <filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"><feGaussianBlur stdDeviation="2"/></filter>
            <mask id="m" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
                <rect x="50" width="50" height="100" fill="white"/></mask></defs>
            <rect x="40" y="40" width="20" height="20" fill="red" filter="url(#f)" mask="url(#m)"/></svg>""", 100.0, 100.0)
        val pixels = snapshot(blur).pixelReader
        assertEquals(0.0, pixels.getColor(47, 50).opacity)
        assertTrue(pixels.getColor(55, 50).opacity > .5)
        assertTrue(pixels.getColor(62, 50).opacity > .01)
    }

    @Test fun textMasksRespondToCssFontChanges() = FxTest.run {
        val view = view("""<svg viewBox="0 0 100 40"><defs><mask id="m">
            <text id="letters" x="2" y="32" font-size="16" fill="white">SVG</text></mask></defs>
            <rect width="100" height="40" fill="blue" mask="url(#m)"/></svg>""", 100.0, 40.0)
        fun count(image: WritableImage): Int = (0 until image.height.toInt()).sumOf { y ->
            (0 until image.width.toInt()).count { x -> image.pixelReader.getColor(x, y).opacity > .5 }
        }
        val before = count(snapshot(view))
        assertTrue(before > 20)
        view.lookup("#letters").style = "-fx-font-size: 32;"
        view.applyCss(); view.layout()
        assertTrue(count(snapshot(view)) > before)
    }

    @Test fun maskChangesWhileAncestorIsHiddenAreRefreshedWhenShown() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs><mask id="m">
            <rect id="color" width="20" height="20" fill="white"/></mask></defs>
            <rect width="20" height="20" fill="blue" mask="url(#m)"/></svg>""", 20.0, 20.0)
        assertEquals(1.0, snapshot(view).pixelReader.getColor(10, 10).opacity, .02)
        view.scene.root.isVisible = false
        view.lookup("#color").style = "-fx-fill: black;"
        view.applyCss(); view.layout()
        view.scene.root.isVisible = true
        view.layout()
        assertEquals(0.0, snapshot(view).pixelReader.getColor(10, 10).opacity)
    }

    private fun view(xml: String, width: Double, height: Double): SvgView =
        SvgView.parse(xml, SvgOptions(strict = true)).also {
            it.isManaged = false
            Scene(Group(it))
            it.resize(width, height)
            it.applyCss(); it.layout()
        }
    private fun snapshot(view: SvgView): WritableImage =
        view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
}
