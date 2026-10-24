package club.xiaojiawei.svg

import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.css.PseudoClass
import javafx.fxml.FXMLLoader
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class SvgThemeTest {
    @TempDir lateinit var directory: Path

    @Test fun rootCssPreservesHollowShapesAndCanRestoreSourceStyles() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 20 20"><rect id="solid" width="20" height="20" fill="red"/>
            <path id="hollow" d="M1 1H19V19H1Z" fill="none" stroke="black" stroke-width="2"/></svg>""")
        css(view, """
            .svg-view { -svg-fill: blue; -svg-stroke: green; -svg-stroke-width: 4; }
            .svg-view:armed { -svg-fill: purple; -svg-stroke-width: 0; }
        """)
        val solid = shape(view, "solid")
        val hollow = shape(view, "hollow")
        assertEquals(Color.BLUE, solid.fill)
        assertNull(hollow.fill)
        assertEquals(Color.GREEN, solid.stroke)
        assertEquals(4.0, hollow.strokeWidth)
        assertTrue(view.cssMetaData.any { it.property == "-fx-padding" })
        view.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), true)
        refresh(view)
        assertEquals(Color.PURPLE, solid.fill)
        assertEquals(0.0, hollow.strokeWidth)
        view.scene.stylesheets.clear()
        refresh(view)
        assertNull(view.svgFill)
        assertNull(view.svgStroke)
        assertEquals(-1.0, view.svgStrokeWidth)
        assertEquals(Color.RED, solid.fill)
        assertNull(solid.stroke)
        assertEquals(Color.BLACK, hollow.stroke)
        assertEquals(2.0, hollow.strokeWidth)
    }

    @Test fun childCssWinsAndRemovingItRevealsLatestTheme() = FxTest.run {
        val view = attached("""<svg><rect id="r" width="20" height="20" fill="red" stroke="black"/></svg>""")
        view.svgFill = Color.BLUE
        css(view, """
            .svg-view:armed #r { -fx-fill: blue; -fx-stroke: orange; -fx-stroke-width: 8; }
        """)
        view.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), true)
        refresh(view)
        val rect = shape(view, "r")
        view.svgFill = Color.GREEN
        view.svgStroke = Color.PURPLE
        view.svgStrokeWidth = 3.0
        refresh(view)
        assertEquals(Color.BLUE, rect.fill)
        assertEquals(Color.ORANGE, rect.stroke)
        assertEquals(8.0, rect.strokeWidth)
        view.pseudoClassStateChanged(PseudoClass.getPseudoClass("armed"), false)
        refresh(view)
        assertEquals(Color.GREEN, rect.fill)
        assertEquals(Color.PURPLE, rect.stroke)
        assertEquals(3.0, rect.strokeWidth)
        rect.style = "-fx-fill: yellow;"
        refresh(view)
        view.svgFill = null
        refresh(view)
        assertEquals(Color.YELLOW, rect.fill)
        rect.style = ""
        refresh(view)
        assertEquals(Color.RED, rect.fill)
    }

    @Test fun boundThemePropertiesIgnoreRootCssAndUpdateIndependentViews() = FxTest.run {
        val doc = SvgLoader.parse("""<svg><rect id="r" width="10" height="10" fill="red"/></svg>""")
        val first = SvgView(doc)
        val second = SvgView(doc)
        val paint = SimpleObjectProperty<Paint?>(Color.BLUE)
        val width = SimpleDoubleProperty(3.0)
        first.svgFillProperty().bind(paint)
        first.svgStrokeWidthProperty().bind(width)
        Scene(Group(first, second))
        css(first, ".svg-view { -svg-fill: orange; -svg-stroke-width: 8; }")
        assertEquals(Color.BLUE, shape(first, "r").fill)
        assertEquals(3.0, shape(first, "r").strokeWidth)
        assertEquals(Color.ORANGE, second.svgFill)
        assertEquals(Color.ORANGE, shape(second, "r").fill)
        paint.set(Color.PURPLE); width.set(5.0)
        assertEquals(Color.PURPLE, shape(first, "r").fill)
        assertEquals(5.0, shape(first, "r").strokeWidth)
        first.svgFillProperty().unbind()
        first.style = "-svg-fill: green;"
        refresh(first)
        assertEquals(Color.GREEN, shape(first, "r").fill)
    }

    @Test fun clearingLastThemeUnderChildCssRestoresSourceWhenChildCssIsRemoved() = FxTest.run {
        val view = attached("""<svg><rect id="r" width="10" height="10" fill="red"/></svg>""")
        val rect = shape(view, "r")
        view.svgFill = Color.BLUE
        rect.style = "-fx-fill: blue;"
        refresh(view)
        view.svgFill = null
        refresh(view)
        assertEquals(Color.BLUE, rect.fill)
        rect.style = ""
        refresh(view)
        assertEquals(Color.RED, rect.fill)
    }

    @Test fun gradientsAndPercentageWidthsRestoreAtCurrentViewport() = FxTest.run {
        val view = attached("""<svg><defs><linearGradient id="g"><stop stop-color="red"/>
            <stop offset="1" stop-color="blue"/></linearGradient></defs>
            <rect id="r" width="100%" height="100%" fill="url(#g)" stroke="black" stroke-width="10%"/></svg>""")
        val rect = shape(view, "r")
        view.svgFill = Color.GREEN; view.svgStroke = Color.ORANGE; view.svgStrokeWidth = 2.0
        view.resize(200.0, 100.0); view.layout()
        assertEquals(Color.GREEN, rect.fill)
        assertEquals(2.0, rect.strokeWidth)
        view.svgFill = null; view.svgStroke = null; view.svgStrokeWidth = -1.0
        val gradient = assertIs<LinearGradient>(rect.fill)
        assertEquals(200.0, gradient.endX, .001)
        assertEquals(Color.BLACK, rect.stroke)
        assertEquals(hypot(200.0, 100.0) / kotlin.math.sqrt(2.0) * .1, rect.strokeWidth, .001)
        view.resize(400.0, 100.0); view.layout()
        assertEquals(400.0, (rect.fill as LinearGradient).endX, .001)
    }

    @Test fun replacingOrClearingDocumentsKeepsThemeAndDetachedNodesIndependent() = FxTest.run {
        val view = SvgView.parse("""<svg><rect id="old" width="10" height="10" fill="red"/></svg>""")
        view.svgFill = Color.BLUE
        val old = shape(view, "old")
        view.document = null
        assertTrue(view.childrenUnmodifiable.isEmpty())
        view.svgFill = Color.PURPLE
        view.document = SvgLoader.parse("""<svg><circle id="new" r="5" fill="orange"/></svg>""")
        assertEquals(Color.PURPLE, shape(view, "new").fill)
        assertEquals(Color.BLUE, old.fill)
        view.svgFill = null
        assertEquals(Color.ORANGE, shape(view, "new").fill)
    }

    @Test fun childBindingsRemainIntactWhileThemeChanges() = FxTest.run {
        val view = attached("""<svg><rect id="r" width="10" height="10" fill="red"/></svg>""")
        val rect = shape(view, "r")
        val paint = SimpleObjectProperty<Paint>(Color.ORANGE)
        rect.fillProperty().bind(paint)
        view.svgFill = Color.BLUE
        assertTrue(rect.fillProperty().isBound)
        assertEquals(Color.ORANGE, rect.fill)
        paint.set(Color.PURPLE)
        refresh(view)
        assertEquals(Color.PURPLE, rect.fill)
    }

    @Test fun themeColorsTextLengthGlyphsWithoutChangingClipGeometry() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 80 40"><defs><clipPath id="c">
            <rect width="40" height="40"/></clipPath></defs>
            <rect id="r" width="80" height="40" fill="red" clip-path="url(#c)"/>
            <text id="t" x="0" y="30" textLength="60" fill="red">ABC</text></svg>""")
        view.resize(80.0, 40.0)
        view.svgFill = Color.BLUE
        refresh(view)
        val text = view.lookup("#t") as Text
        assertEquals(Color.BLUE, text.fill)
        assertTrue(view.lookupAll(".svg-text-glyph").all { (it as Text).fill == Color.BLUE })
        val clip = (shape(view, "r").clip as Group).children.single() as Group
        assertEquals(Color.BLACK, (clip.children.single() as Rectangle).fill)
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertTrue(image.pixelReader.getColor(10, 5).blue > .9)
        assertEquals(0.0, image.pixelReader.getColor(70, 5).opacity)
    }

    @Test fun sameSizeThemeChangeRefreshesGaussianFilterPixels() = FxTest.run {
        val view = attached("""<svg viewBox="0 0 100 100"><defs><filter id="f" filterUnits="userSpaceOnUse"
            x="0" y="0" width="100" height="100"><feGaussianBlur stdDeviation="2"/></filter></defs>
            <rect x="40" y="40" width="20" height="20" fill="red" filter="url(#f)"/></svg>""")
        view.resize(100.0, 100.0); view.layout()
        val params = SnapshotParameters().apply { fill = Color.TRANSPARENT }
        assertTrue(view.snapshot(params, null).pixelReader.getColor(38, 50).red > .9)
        view.svgFill = Color.BLUE
        view.layout()
        val pixel = view.snapshot(params, null).pixelReader.getColor(38, 50)
        assertTrue(pixel.opacity > .05)
        assertEquals(1.0, pixel.blue, .03)
        assertEquals(0.0, pixel.red, .03)
    }

    @Test fun fxmlCanSetThemePropertiesAndCssCanUseLookedUpColors() = FxTest.run {
        val xml = """<?import club.xiaojiawei.svg.SvgView?>
            <SvgView source="@/demo/sample.svg" svgFill="red" svgStroke="blue" svgStrokeWidth="3"/>
        """.trimIndent()
        val view = FXMLLoader().load<SvgView>(ByteArrayInputStream(xml.toByteArray()))
        Scene(Group(view))
        assertEquals(Color.RED, view.svgFill)
        assertEquals(Color.BLUE, view.svgStroke)
        assertEquals(3.0, view.svgStrokeWidth)
        css(view, ".svg-view { -icon-color: #409eff; -svg-fill: -icon-color; }")
        assertEquals(Color.web("#409eff"), view.svgFill)
        assertEquals(Color.web("#409eff"), (view.lookup(".tile") as Shape).fill)
        assertNull(shape(view, "outline").fill)
    }

    @Test fun invalidWidthsAreRejectedWithoutLosingCurrentValue() = FxTest.run {
        val view = SvgView()
        view.svgStrokeWidth = 2.0
        for (invalid in listOf(-2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { view.svgStrokeWidth = invalid }
            assertFailsWith<IllegalArgumentException> { view.svgStrokeWidthProperty().set(invalid) }
            assertEquals(2.0, view.svgStrokeWidth)
        }
    }

    private fun attached(xml: String): SvgView = SvgView.parse(xml, SvgOptions(strict = true)).also {
        it.isManaged = false
        Scene(Group(it))
        it.resize(80.0, 40.0)
        refresh(it)
    }
    private fun shape(view: SvgView, id: String) = view.lookup("#$id") as Shape
    private fun refresh(view: SvgView) { view.scene.root.applyCss(); view.scene.root.layout(); view.layout() }
    private fun css(view: SvgView, value: String) {
        val file = Files.writeString(directory.resolve("theme-${view.scene.stylesheets.size}.css"), value)
        view.scene.stylesheets += file.toUri().toString()
        refresh(view)
    }
}
