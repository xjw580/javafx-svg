package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgTextLengthTest {
    @Test fun spacingChangesAdvancesWithoutStretchingGlyphs() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 200 50"><text id="t" x="100" y="30"
            font-size="20" text-anchor="middle" textLength="120">ABC</text></svg>""", SvgOptions(strict = true))
        val target = view.lookup("#t") as Text
        val adjusted = target.parent as SvgAdjustedText
        val bounds = svgGeometryBounds(adjusted)
        assertEquals(40.0, bounds.minX, .001)
        assertEquals(120.0, bounds.width, .001)
        val runs = runs(adjusted)
        assertEquals(listOf("A", "B", "C"), runs.map { it.text })
        runs.forEach {
            assertEquals(20.0, it.font.size)
            assertTrue(it.localToParentTransform.isIdentity)
            assertEquals(Text(it.text).apply { font = target.font }.layoutBounds.width, it.layoutBounds.width, .001)
            assertEquals(30.0, it.y)
        }
    }

    @Test fun scalingGlyphsKeepsNativeTextShapingAndHonorsAllAnchors() = FxTest.run {
        for ((anchor, fraction) in listOf("start" to 0.0, "middle" to .5, "end" to 1.0)) {
            val view = SvgView.parse("""<svg><text id="t" x="100" y="30" font-size="20"
                text-anchor="$anchor" textLength="80" lengthAdjust="spacingAndGlyphs">Hello SVG</text></svg>""")
            val model = view.lookup("#t") as Text
            val adjusted = model.parent as SvgAdjustedText
            val bounds = svgGeometryBounds(adjusted)
            assertEquals(100 - fraction * 80, bounds.minX, .001)
            assertEquals(80.0, bounds.width, .001)
            assertEquals(model.layoutBounds.height, bounds.height, .001)
            assertEquals("Hello SVG", runs(adjusted).single().text)
        }
    }

    @Test fun percentageLengthTracksResizeAndCssCanChangeFontPaintAndOpacity() = FxTest.run {
        val view = SvgView.parse("""<svg><text id="t" x="50%" y="30" text-anchor="middle"
            textLength="50%" fill="red">ABC</text></svg>""")
        view.isManaged = false
        Scene(Group(view))
        view.resize(200.0, 50.0); view.layout()
        val model = view.lookup("#t") as Text
        val adjusted = model.parent as SvgAdjustedText
        assertEquals(100.0, svgGeometryBounds(adjusted).width, .001)
        model.style = "-fx-fill: blue; -fx-stroke: green; -fx-stroke-width: 2; -fx-font-size: 28; -fx-opacity: 0.5;"
        view.applyCss(); view.layout()
        runs(adjusted).forEach {
            assertEquals(Color.BLUE, it.fill)
            assertEquals(Color.GREEN, it.stroke)
            assertEquals(2.0, it.strokeWidth)
            assertEquals(28.0, it.font.size)
        }
        assertEquals(.5, adjusted.opacity)
        assertEquals(100.0, svgGeometryBounds(adjusted).width, .001)
        view.resize(400.0, 50.0); view.layout()
        assertEquals(200.0, svgGeometryBounds(adjusted).width, .001)
        assertEquals(100.0, svgGeometryBounds(adjusted).minX, .001)
        assertEquals(Color.BLUE, runs(adjusted).first().fill)
    }

    @Test fun spacingKeepsCombiningCharactersAndSurrogatePairsTogether() = FxTest.run {
        val view = SvgView.parse("""<svg><text id="t" textLength="100">Á😀B</text></svg>""", SvgOptions(strict = true))
        val adjusted = view.lookup("#t").parent as SvgAdjustedText
        assertEquals(listOf("Á", "😀", "B"), runs(adjusted).map { it.text })
        assertEquals(100.0, svgGeometryBounds(adjusted).width, .001)
    }

    @Test fun singleCharacterEmptyTextZeroLengthAndCompressionStayFinite() = FxTest.run {
        val view = SvgView.parse("""<svg><text id="one" textLength="100">A</text>
            <text id="empty" textLength="0"/>
            <text id="zero" textLength="0" lengthAdjust="spacingAndGlyphs">AB</text>
            <text id="compressed" textLength="20">ABC</text></svg>""", SvgOptions(strict = true))
        val one = view.lookup("#one") as Text
        assertEquals(one.layoutBounds.width, svgGeometryBounds(one.parent).width, .001)
        assertEquals(0.0, svgGeometryBounds(view.lookup("#empty").parent).width)
        assertEquals(0.0, svgGeometryBounds(view.lookup("#zero").parent).width)
        assertEquals(20.0, svgGeometryBounds(view.lookup("#compressed").parent).width, .001)
    }

    @Test fun rejectsInvalidValuesAndDiagnosesUnsupportedShapingAndNodeLimits() = FxTest.run {
        for (attributes in listOf("textLength=\"-1\"", "textLength=\"2\" lengthAdjust=\"invalid\"")) {
            assertFailsWith<SvgException> { SvgView.parse("<svg><text $attributes>ABC</text></svg>", SvgOptions(strict = true)) }
        }
        val xml = """<svg><text id="t" textLength="100">مرحبا</text></svg>"""
        val fallback = SvgView.parse(xml)
        assertTrue(fallback.diagnostics.any { it.message.contains("bidirectional") })
        assertTrue(fallback.lookup("#t").isVisible)
        assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true)) }
        assertFailsWith<SvgException> { SvgView.parse("""<svg><text textLength="100">ABCDE</text></svg>""",
            SvgOptions(strict = true, maxElements = 4)) }
    }

    @Test fun gradientUsesAdjustedWholeTextBounds() = FxTest.run {
        val view = SvgView.parse("""<svg><defs><linearGradient id="g"><stop stop-color="red"/>
            <stop offset="1" stop-color="blue"/></linearGradient></defs>
            <text id="t" x="10" textLength="100" fill="url(#g)">ABC</text></svg>""")
        val fill = (view.lookup("#t") as Text).fill as LinearGradient
        assertEquals(10.0, fill.startX, .001)
        assertEquals(110.0, fill.endX, .001)
    }

    private fun runs(adjusted: SvgAdjustedText): List<Text> =
        (adjusted.children.single { it is Group } as Group).children.map { it as Text }
}
