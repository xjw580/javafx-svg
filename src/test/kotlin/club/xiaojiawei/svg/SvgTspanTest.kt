package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class SvgTspanTest {
    @Test fun parserPreservesMixedContentAndKeepsDirectTextCompatible() {
        val document = SvgLoader.parse("""<svg><text id="t">A<![CDATA[B]]><tspan id="s">C</tspan>D</text></svg>""")
        val text = document.elementsById.getValue("t")
        assertEquals("ABD", text.text)
        assertEquals(listOf(SvgContent.Characters("AB"), SvgContent.Child(text.children.single()),
            SvgContent.Characters("D")), text.content)
        assertSame(document.elementsById["s"], (text.content[1] as SvgContent.Child).element)
        assertFailsWith<UnsupportedOperationException> { (text.content as MutableList).clear() }
    }

    @Test fun rendersOrderedNativeRunsWithNestedSvgStyles() = FxTest.run {
        val view = strict("""<svg font-size="20" fill="red"><style>.accent { fill: blue; font-weight: bold; }</style>
            <text id="t" x="10" y="30">A<tspan id="s" class="accent" font-size="50%">B<tspan font-style="italic">C</tspan>D</tspan>E</text></svg>""")
        val runs = runs(view.lookup("#t"))
        assertEquals(listOf("A", "B", "C", "D", "E"), runs.map { it.text })
        assertEquals(listOf(20.0, 10.0, 10.0, 10.0, 20.0), runs.map { it.font.size })
        assertEquals(listOf(Color.RED, Color.BLUE, Color.BLUE, Color.BLUE, Color.RED), runs.map { it.fill })
        assertTrue(runs[1].font.style.contains("Bold"))
        assertTrue(runs[2].font.style.contains("Italic"))
        assertTrue(view.lookup("#s").styleClass.containsAll(listOf("svg-tspan", "accent")))
        var x = 10.0
        runs.forEach {
            assertEquals(x, it.x, .001)
            assertEquals(30.0, it.y)
            x += it.layoutBounds.width
        }
    }

    @Test fun normalizesWhitespaceAcrossBoundariesAndHonorsXmlSpace() = FxTest.run {
        val view = strict("""<svg xml:space="preserve"><text id="a" xml:space="default">
            A <tspan> B <![CDATA[ C]]></tspan> D
            </text><text id="b"> A <tspan> B </tspan> C </text>
            <text id="c" xml:space="default">A<tspan xml:space="preserve">  B  </tspan>C</text></svg>""")
        assertEquals("A B C D", runs(view.lookup("#a")).joinToString("") { it.text })
        assertEquals(" A  B  C ", runs(view.lookup("#b")).joinToString("") { it.text })
        assertEquals("A  B  C", runs(view.lookup("#c")).joinToString("") { it.text })
    }

    @Test fun innerScalarCoordinatesOverrideTheFirstCharacterAndOffsetsContinue() = FxTest.run {
        val view = strict("""<svg><text id="t" x="10" y="20" dx="1"><tspan x="100" dx="3" dy="4">A</tspan>B<tspan dx="5">C<tspan dy="6">D</tspan>E</tspan>F</text></svg>""")
        val r = runs(view.lookup("#t"))
        assertEquals(103.0, r[0].x)
        assertEquals(24.0, r[0].y)
        for (i in 1..5) {
            assertEquals(r[i - 1].x + r[i - 1].layoutBounds.width + if (i == 2) 5 else 0, r[i].x, .001)
            assertEquals(if (i < 3) 24.0 else 30.0, r[i].y)
        }
    }

    @Test fun anchorsWholeChunksAndAbsolutePositionsStartNewChunks() = FxTest.run {
        val view = strict("""<svg><text id="t" x="100" y="20" text-anchor="middle">AB<tspan text-anchor="end">CD</tspan><tspan x="220" text-anchor="end">EF</tspan>G</text></svg>""")
        val r = runs(view.lookup("#t"))
        val firstWidth = r[0].layoutBounds.width + r[1].layoutBounds.width
        val secondWidth = r[2].layoutBounds.width + r[3].layoutBounds.width
        assertEquals(100 - firstWidth / 2, r[0].x, .001)
        assertEquals(r[0].x + r[0].layoutBounds.width, r[1].x, .001)
        assertEquals(220 - secondWidth, r[2].x, .001)
        assertEquals(220.0, r[3].x + r[3].layoutBounds.width, .001)
    }

    @Test fun emptyAndUndisplayedSpansDoNotMoveThePenButHiddenTextDoes() = FxTest.run {
        val view = strict("""<svg><text id="t" x="40" y="20" visibility="hidden"><tspan x="999"/><tspan display="none" x="500">skip</tspan>A<tspan id="s" visibility="visible">B</tspan>C</text></svg>""")
        val r = runs(view.lookup("#t"))
        assertEquals(listOf("A", "B", "C"), r.map { it.text })
        assertEquals(40.0, r.first().x)
        assertEquals(listOf(false, true, false), r.map { it.isVisible })
        assertTrue(view.lookup("#t").isVisible)
        assertTrue(view.lookup("#s").isVisible)
        assertEquals(r[0].x + r[0].layoutBounds.width, r[1].x, .001)
    }

    @Test fun percentagesResolveAgainstTheCurrentViewport() = FxTest.run {
        val view = strict("""<svg><text id="t" x="50%" y="50%">A<tspan dx="10%" dy="10%">B</tspan></text></svg>""")
        view.resize(200.0, 100.0); view.layout()
        val r = runs(view.lookup("#t"))
        assertEquals(100.0, r[0].x)
        assertEquals(50.0, r[0].y)
        assertEquals(100 + r[0].layoutBounds.width + 20, r[1].x, .001)
        assertEquals(60.0, r[1].y)
        view.resize(400.0, 200.0); view.layout()
        assertEquals(200.0, r[0].x)
        assertEquals(100.0, r[0].y)
        assertEquals(200 + r[0].layoutBounds.width + 40, r[1].x, .001)
        assertEquals(120.0, r[1].y)
    }

    @Test fun descendantCssOverridesThemeAndFontChangesReflowTheWholeChunk() = FxTest.run {
        val view = strict("""<svg><text id="t" x="100" y="35" text-anchor="middle" fill="red">A<tspan id="emphasis">B</tspan>C</text></svg>""")
        view.isManaged = false
        Scene(Group(view))
        view.resize(200.0, 50.0)
        val css = Files.createTempFile("svg-tspan-", ".css")
        try {
            Files.writeString(css, "#emphasis .svg-text-run { -fx-fill: blue; -fx-stroke: green; -fx-stroke-width: 2; -fx-font-size: 30; }")
            view.svgFill = Color.ORANGE
            view.stylesheets += css.toUri().toString()
            view.applyCss(); view.layout()
            val r = runs(view.lookup("#t"))
            assertEquals(listOf(Color.ORANGE, Color.BLUE, Color.ORANGE), r.map { it.fill })
            assertEquals(Color.GREEN, r[1].stroke)
            assertEquals(2.0, r[1].strokeWidth)
            assertEquals(30.0, r[1].font.size)
            assertEquals(100.0, r[0].x + r.sumOf { it.layoutBounds.width } / 2, .001)
            for (i in 1..2) assertEquals(r[i - 1].x + r[i - 1].layoutBounds.width, r[i].x, .001)
            view.stylesheets.clear()
            view.applyCss(); view.layout()
            assertEquals(16.0, r[1].font.size)
            assertEquals(Color.ORANGE, r[1].fill)
            view.svgFill = null
            view.layout()
            assertEquals(Color.RED, r[1].fill)
        } finally {
            Files.deleteIfExists(css)
        }
    }

    @Test fun allFragmentsShareTheWholeTextGradientBoundsAndRefreshAfterCss() = FxTest.run {
        val view = strict("""<svg><defs><linearGradient id="g"><stop stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient></defs><text id="t" x="10" y="30" fill="url(#g)">AB<tspan>CD</tspan>E</text></svg>""")
        Scene(Group(view))
        val r = runs(view.lookup("#t"))
        fun checkGradient() {
            val end = r.last().x + r.last().layoutBounds.width
            r.forEach {
                val fill = it.fill as LinearGradient
                assertFalse(fill.isProportional)
                assertEquals(10.0, fill.startX, .001)
                assertEquals(end, fill.endX, .001)
            }
        }
        checkGradient()
        r[1].style = "-fx-font-size: 32;"
        view.applyCss(); view.layout()
        checkGradient()
    }

    @Test fun rootClippingUsesCombinedGeometryAndFollowsFontChanges() = FxTest.run {
        val view = strict("""<svg><defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width=".5" height="1"/></clipPath></defs><text id="t" x="10" y="30" clip-path="url(#c)">A<tspan>B</tspan>C</text></svg>""")
        Scene(Group(view))
        val text = view.lookup("#t")
        val wrapper = text.clip as Group
        val before = wrapper.transforms.single().mxx
        assertEquals(svgGeometryBounds(text).width, before, .001)
        runs(text)[1].style = "-fx-font-size: 32;"
        view.applyCss(); view.layout()
        assertTrue(wrapper.transforms.single().mxx > before)
        assertEquals(svgGeometryBounds(text).width, wrapper.transforms.single().mxx, .001)
    }

    @Test fun unicodeFragmentsStayNativeAndDocumentInstancesRemainIndependent() = FxTest.run {
        val document = SvgLoader.parse("""<svg><text id="t">Á<tspan id="s">😀中文</tspan>B</text></svg>""", SvgOptions(strict = true))
        val first = SvgView(document)
        val second = SvgView(document)
        assertEquals(listOf("Á", "😀中文", "B"), runs(first.lookup("#t")).map { it.text })
        assertNotSame(first.lookup("#s"), second.lookup("#s"))
        runs(first.lookup("#t"))[1].fill = Color.GREEN
        assertEquals(Color.BLACK, runs(second.lookup("#t"))[1].fill)
    }

    @Test fun unsupportedFeaturesReportAndStrictModeRejectsWithoutBreakingPlainTextLength() = FxTest.run {
        val examples = listOf(
            """<text x="1 2">A<tspan>B</tspan>C</text>""" to "single length",
            """<text textLength="100">A<tspan>B</tspan>C</text>""" to "textLength",
            """<text>A<tspan rotate="20">B</tspan>C</text>""" to "rotate",
            """<text>A<tspan transform="translate(5)">B</tspan>C</text>""" to "transform",
            """<text>A<tspan style="filter: url(#f)">B</tspan>C</text>""" to "filter",
            """<text>A<tspan>مرحبا</tspan>C</text>""" to "Bidirectional",
            """<text>A<tspan>नमस्ते</tspan>C</text>""" to "Contextual"
        )
        examples.forEach { (text, diagnostic) ->
            val xml = "<svg>$text</svg>"
            val loose = SvgView.parse(xml)
            assertTrue(loose.diagnostics.any { diagnostic in it.message }, loose.diagnostics.toString())
            assertTrue(runs(loose).isNotEmpty())
            assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true)) }
        }
        val limited = """<svg><text>A<tspan>B</tspan>C</text></svg>"""
        assertFailsWith<SvgException> { SvgView.parse(limited, SvgOptions(strict = true, maxElements = 4)) }
        val loose = SvgView.parse(limited, SvgOptions(maxElements = 4))
        loose.layout()
        assertTrue(loose.diagnostics.any { "limit" in it.message })
    }

    private fun strict(xml: String) = SvgView.parse(xml, SvgOptions(strict = true))

    private fun runs(node: Node): List<Text> = when (node) {
        is Text -> listOf(node)
        is Parent -> node.childrenUnmodifiable.flatMap(::runs)
        else -> emptyList()
    }
}
