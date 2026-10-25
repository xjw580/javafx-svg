package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgTextStylesTest {
    @Test fun decorationPresentationStylesheetAndInlineCascade() = FxTest.run {
        val v = strict("""<svg><style>text { text-decoration: underline } #b { text-decoration: line-through !important }</style>
            <text id="a" text-decoration="line-through">A</text>
            <text id="b" style="text-decoration: underline">B</text>
            <text id="c" style="text-decoration: underline line-through">C</text></svg>""")
        val a = v.lookup("#a") as Text
        val b = v.lookup("#b") as Text
        val c = v.lookup("#c") as Text
        assertTrue(a.isUnderline); assertFalse(a.isStrikethrough)
        assertFalse(b.isUnderline); assertTrue(b.isStrikethrough)
        assertTrue(c.isUnderline); assertTrue(c.isStrikethrough)
    }

    @Test fun decorationPropagatesThroughNoneWithoutBecomingComputedInheritance() = FxTest.run {
        val v = strict("""<svg><text id="t" text-decoration="underline">A<tspan text-decoration="none">B<tspan text-decoration="line-through">C</tspan></tspan>D</text>
            <g text-decoration="line-through"><text id="plain">E</text></g></svg>""")
        val r = runs(v.lookup("#t"))
        assertEquals(listOf(true, false, false, true), r.map { it.isUnderline })
        assertEquals(2, v.lookupAll(".svg-text-underline").size)
        assertEquals(listOf(false, false, true, false), r.map { it.isStrikethrough })
        assertFalse((v.lookup("#plain") as Text).isStrikethrough)
    }

    @Test fun javaFxDecorationOverridesSurviveLayoutAndRestoreWhenRemoved() = FxTest.run {
        val v = strict("""<svg><text id="t" text-decoration="underline line-through" textLength="100">ABC</text></svg>""")
        Scene(Group(v))
        val t = v.lookup("#t") as Text
        t.style = "-fx-underline: false; -fx-strikethrough: false; -fx-font-size: 28; -fx-fill: red;"
        v.applyCss(); v.layout()
        assertTrue(v.lookupAll(".svg-text-glyph").filterIsInstance<Text>().all { !it.isUnderline && !it.isStrikethrough && it.fill == Color.RED && it.font.size == 28.0 })
        t.style = ""
        v.applyCss(); v.layout()
        assertTrue(t.isUnderline); assertTrue(t.isStrikethrough)
        assertTrue(v.lookupAll(".svg-continuous-decoration").filterIsInstance<Rectangle>()
            .filter { "svg-text-overline" !in it.styleClass }.all { it.isVisible && it.width == 100.0 })
    }

    @Test fun overlineFollowsFontPaintAndAdjustedTextGeometry() = FxTest.run {
        for (adjust in listOf("", "textLength=\"120\"", "textLength=\"120\" lengthAdjust=\"spacingAndGlyphs\"")) {
            val v = strict("""<svg><text id="t" x="20" y="50" fill="red" font-size="20" text-decoration="overline underline" $adjust>ABC</text></svg>""")
            Scene(Group(v))
            val model = v.lookup("#t") as Text
            val lines = v.lookupAll(".svg-text-overline").filterIsInstance<Rectangle>()
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.all { it.fill == Color.RED && it.height > 0 && it.width > 0 }, lines.joinToString { "fill=${it.fill}, height=${it.height}, width=${it.width}" })
            val before = lines.first().height
            model.style = "-fx-font-size: 40; -fx-fill: blue;"
            v.applyCss(); v.layout()
            assertTrue(lines.all { it.fill == Color.BLUE })
            assertTrue(lines.first().height > before)
            if (adjust.isNotEmpty()) assertEquals(120.0, svgGeometryBounds(model.parent).width, .001)
        }
    }

    @Test fun nestedBaselineShiftsAreAdditiveNotInheritedAndDoNotMoveThePen() = FxTest.run {
        val v = strict("""<svg><text id="t" x="10" y="60">A<tspan baseline-shift="10">B<tspan>C</tspan><tspan baseline-shift="inherit">D</tspan><tspan baseline-shift="-3">E</tspan><tspan baseline-shift="baseline">F</tspan></tspan>G</text></svg>""")
        val r = runs(v.lookup("#t"))
        assertEquals(listOf(60.0, 50.0, 50.0, 40.0, 53.0, 50.0, 60.0), r.map { it.y })
        r.zipWithNext().forEach { (a, b) -> assertEquals(a.x + a.layoutBounds.width, b.x, .001) }
    }

    @Test fun baselineShiftResolvesPercentEmAndAbsoluteUnitsAndInheritedComputedLengths() = FxTest.run {
        val v = strict("""<svg><text id="t" y="100" font-size="40"><tspan baseline-shift="50%" font-size="20">A<tspan baseline-shift="inherit" font-size="10">B</tspan></tspan><tspan font-size="20" baseline-shift=".5em">C</tspan><tspan baseline-shift="6pt">D</tspan><tspan baseline-shift="sub">E</tspan><tspan baseline-shift="super">F</tspan></text></svg>""")
        assertEquals(listOf(80.0, 60.0, 90.0, 92.0, 112.0, 76.0), runs(v.lookup("#t")).map { it.y })
    }

    @Test fun baselineShiftCombinesWithDyAndAbsoluteChunks() = FxTest.run {
        val v = strict("""<svg><text id="t" y="50">A<tspan baseline-shift="10" dy="4">B<tspan x="80" y="100" baseline-shift="3">C</tspan>D</tspan>E</text></svg>""")
        val r = runs(v.lookup("#t"))
        assertEquals(listOf(50.0, 44.0, 87.0, 90.0, 100.0), r.map { it.y })
        assertEquals(80.0, r[2].x)
    }

    @Test fun baselineShiftAndDecorationsFollowTextPathNormalAndGlyphTransforms() = FxTest.run {
        val v = strict("""<svg><defs><path id="p" d="M50 0V200"/></defs><text font-size="20" text-decoration="underline overline line-through"><textPath id="tp" href="#p" baseline-shift="8">ABC</textPath></text></svg>""")
        val glyphs = v.lookupAll(".svg-text-glyph").filterIsInstance<Text>()
        assertTrue(glyphs.all { it.y == -8.0 })
        assertEquals(3, v.lookupAll(".svg-text-underline").size)
        assertTrue(v.lookupAll(".svg-text-underline").all { it.isVisible })
        assertTrue(glyphs.all { kotlin.math.abs(it.localToParentTransform.myx - 1.0) < .001 })
        assertEquals(3, v.lookupAll(".svg-text-overline").size)
        assertTrue(v.lookupAll(".svg-text-line-through").all { it.isVisible })
    }

    @Test fun invalidValuesAreDiagnosedWithoutLosingValidLowerPriorityStyle() = FxTest.run {
        val v = SvgView.parse("""<svg><text id="t" text-decoration="underline" style="text-decoration: none underline">A</text>
            <text><tspan baseline-shift="2pxx">B</tspan></text></svg>""")
        assertTrue((v.lookup("#t") as Text).isUnderline)
        assertTrue(v.diagnostics.any { "text-decoration" in it.message })
        assertTrue(v.diagnostics.any { "length" in it.message })
        for (property in listOf("text-decoration: wavy", "baseline-shift: calc(1px + 2px)", "text-decoration: blink")) {
            assertFailsWith<SvgException> { strict("<svg><text><tspan style=\"$property\">A</tspan></text></svg>") }
        }
    }

    @Test fun rootTextBaselineShiftWorksWithSpansTextLengthAndTextPath() = FxTest.run {
        val v = strict("""<svg><defs><path id="p" d="M0 80H200"/></defs>
            <text id="plain" y="80" font-size="20" baseline-shift="50%">A</text>
            <text id="spans" y="80" baseline-shift="10">A<tspan baseline-shift="5">B</tspan>C</text>
            <text id="adjusted" y="80" baseline-shift="10" textLength="100">ABC</text>
            <text baseline-shift="10"><textPath id="path" href="#p" baseline-shift="5">ABC</textPath></text></svg>""")
        assertEquals(70.0, (v.lookup("#plain") as Text).y)
        assertEquals(listOf(70.0, 65.0, 70.0), runs(v.lookup("#spans")).map { it.y })
        assertTrue(v.lookupAll(".svg-text-glyph").filterIsInstance<Text>().any { it.y == 70.0 })
        assertTrue(v.lookupAll(".svg-text-glyph").filterIsInstance<Text>().any { it.y == -15.0 })
    }

    @Test fun propagatedDecorationKeepsSourcePaintFontAndBaselineAndAllowsExplicitOverride() = FxTest.run {
        val v = strict("""<svg><text id="t" x="10" y="60" font-size="20" fill="red" text-decoration="underline line-through overline">A<tspan id="child" fill="blue" font-size="10" baseline-shift="8">B</tspan><tspan id="own" fill="green" text-decoration="underline">C</tspan></text></svg>""")
        Scene(Group(v))
        val child = v.lookup("#child")
        val lines = child.lookupAll(".svg-text-decoration-line").filterIsInstance<Rectangle>()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.fill == Color.RED })
        val nativeMetrics = SvgTextDecorationMetrics()
        val first = runs(v.lookup("#t")).first()
        lines.forEach { line ->
            val kind = when {
                "svg-text-underline" in line.styleClass -> "underline"
                "svg-text-overline" in line.styleClass -> "overline"
                else -> "line-through"
            }
            val (offset, thickness) = nativeMetrics.bounds(kind, first.font)
            assertEquals(60 + offset, line.y, .001)
            assertEquals(thickness, line.height, .001)
        }
        val own = runs(v.lookup("#own")).single()
        assertTrue(own.isUnderline)
        assertEquals(Color.GREEN, own.fill)
        first.style = "-fx-fill: purple; -fx-font-size: 30;"
        v.applyCss(); v.layout()
        assertTrue(lines.all { it.fill == Color.PURPLE })
        val childText = runs(child).single()
        childText.style = "-fx-underline: false; -fx-strikethrough: false;"
        v.applyCss(); v.layout()
        assertTrue(lines.filter { "svg-text-overline" !in it.styleClass }.none { it.isVisible })
        childText.style = ""
        v.applyCss(); v.layout()
        assertTrue(lines.all { it.isVisible })
    }

    @Test fun invalidCompositeLengthUnitsFallBackToValidPresentationValue() = FxTest.run {
        for (bad in listOf("1pxem", "1exem", "1emex")) {
            val v = SvgView.parse("""<svg><text y="40"><tspan id="s" baseline-shift="7" style="baseline-shift: $bad">A</tspan></text></svg>""")
            assertEquals(33.0, runs(v.lookup("#s")).single().y)
            assertTrue(v.diagnostics.isNotEmpty())
        }
    }

    @Test fun textLengthSpacingDecorationsCoverTheEntireAdjustedAdvance() = FxTest.run {
        val v = strict("""<svg><text id="t" x="100" y="40" text-anchor="middle" textLength="120" text-decoration="underline line-through overline">ABC</text></svg>""")
        val lines = v.lookupAll(".svg-continuous-decoration").filterIsInstance<Rectangle>()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.isVisible && it.x == 40.0 && it.width == 120.0 && it.height > 0.0 })
        assertTrue(v.lookupAll(".svg-text-glyph").filterIsInstance<Text>().none { it.isUnderline || it.isStrikethrough })
    }

    @Test fun customDecorationPaintAndCurvedPathLimitsAreExplicit() = FxTest.run {
        val radial = """<svg><defs><radialGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></radialGradient></defs><text fill="url(#g)" text-decoration="overline">ABC</text></svg>"""
        val loose = SvgView.parse(radial)
        assertTrue(loose.diagnostics.any { "proportional radial" in it.message })
        assertFailsWith<SvgException> { strict(radial) }
        val curved = """<svg><defs><path id="p" d="M0 40Q50 0 100 40"/></defs><text text-decoration="underline"><textPath href="#p">ABC</textPath></text></svg>"""
        assertTrue(SvgView.parse(curved).diagnostics.any { "glyph-aligned" in it.message })
        assertFailsWith<SvgException> { strict(curved) }
    }

    private fun strict(xml: String) = SvgView.parse(xml, SvgOptions(strict = true))
    private fun runs(node: Node): List<Text> = when (node) {
        is Text -> listOf(node)
        is Parent -> node.childrenUnmodifiable.flatMap(::runs)
        else -> emptyList()
    }
}
