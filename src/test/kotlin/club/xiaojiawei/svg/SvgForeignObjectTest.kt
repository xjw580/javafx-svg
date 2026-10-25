package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgForeignObjectTest {
    private fun snapshot(view: SvgView, width: Double = 240.0, height: Double = 160.0): WritableImage {
        view.isManaged = false
        if (view.scene == null) Scene(Group(view))
        view.resize(width, height); view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }

    private fun parse(html: String, attributes: String = "", width: String = "200", height: String = "120", options: SvgOptions = SvgOptions(strict = true)) =
        SvgView.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="240" height="160">
            <foreignObject id="foreign" width="$width" height="$height" $attributes>
            <div xmlns="http://www.w3.org/1999/xhtml">$html</div></foreignObject></svg>""", options)

    private fun texts(view: SvgView) = view.lookupAll(".svg-xhtml-text").filterIsInstance<Text>()
    private fun boxes(view: SvgView) = view.lookupAll(".svg-xhtml-box").filterIsInstance<Rectangle>()
    private fun box(view: SvgView, id: String) = (view.lookup("#$id") as Group).children.filterIsInstance<Rectangle>().single()

    @Test fun originalFixtureRendersNativeRoundedBoxAndStyledMixedTextAtMultipleScales() = FxTest.run {
        for (scale in listOf(1.0, 1.5, 2.0)) {
            val view = SvgView.load(checkNotNull(javaClass.getResource("/svg/foreign-object.svg")), SvgOptions(strict = true))
            try {
                val pixels = snapshot(view, 640 * scale, 360 * scale).pixelReader
                assertTrue(view.diagnostics.isEmpty(), view.diagnostics.toString())
                assertEquals(Color.web("#193e54"), pixels.getColor((320 * scale).toInt(), (151 * scale).toInt()))
                assertEquals(Color.web("#35d3ca"), pixels.getColor((320 * scale).toInt(), (142 * scale).toInt()))
                assertEquals(Color.web("#1b3355"), pixels.getColor((121 * scale).toInt(), (142 * scale).toInt()))
                assertEquals(Color.web("#1b3355"), pixels.getColor((320 * scale).toInt(), (230 * scale).toInt()))
                val runs = texts(view)
                assertEquals(setOf("HTML ", "inside", " an SVG ", "✓"), runs.map { it.text }.toSet())
                assertEquals(Color.web("#66e7d8"), runs.single { it.text == "inside" }.fill)
                assertEquals(Color.web("#ffc282"), runs.single { it.text == "✓" }.fill)
                assertTrue(runs.all { it.font.size == 21.0 && it.font.style.contains("bold", true) })
                val box = boxes(view).single()
                assertEquals(400.0, box.width, 0.001)
                assertEquals(34.0, box.arcWidth, 0.001)
                assertEquals(2.0, box.strokeWidth)
                val foreign = view.lookup(".svg-foreignObject") as SvgForeignObject
                assertEquals(400.0, foreign.geometryBounds().width)
                assertEquals(110.0, foreign.geometryBounds().height)
                assertTrue(box.height in 68.0..80.0, "auto height is ${box.height}, not the 110px containing block")
            } finally { view.source = null }
        }
    }

    @Test fun svgPositionTransformAndContainingBlockAreIndependentOfHtmlHeight() = FxTest.run {
        val view = parse("<div style='height:100%;background:red'/>", "x='20' y='30' transform='translate(5 7)'", "80", "60")
        try {
            val pixels = snapshot(view).pixelReader
            val foreign = view.lookup("#foreign") as SvgForeignObject
            assertEquals(20.0, foreign.layoutX); assertEquals(30.0, foreign.layoutY)
            assertEquals(80.0, svgGeometryBounds(foreign).width)
            assertEquals(60.0, svgGeometryBounds(foreign).height)
            // The root auto-height div is not a definite height for its child's percentage height.
            assertEquals(0.0, boxes(view).maxOf { it.height })
            assertEquals(0.0, pixels.getColor(30, 40).opacity)
        } finally { view.source = null }
        val direct = SvgView.parse("""<svg width="240" height="160"><foreignObject x="20" y="30" width="80" height="60" transform="translate(5 7)">
            <div xmlns="http://www.w3.org/1999/xhtml" style="height:100%;background:red"/></foreignObject></svg>""", SvgOptions(strict = true))
        try {
            val pixels = snapshot(direct).pixelReader
            assertEquals(Color.RED, pixels.getColor(30, 40))
            assertEquals(0.0, pixels.getColor(24, 40).opacity)
            assertEquals(0.0, pixels.getColor(106, 40).opacity)
            assertEquals(60.0, boxes(direct).single().height)
        } finally { direct.source = null }
    }

    @Test fun borderBoxAndContentBoxComputeDimensionsAndPercentPadding() = FxTest.run {
        val view = parse("""<div id="content" style="width:80px;height:20px;padding:10px;border:2px solid blue;background:red"/>
            <div id="border" style="box-sizing:border-box;width:80px;height:50px;padding:10px;border:2px solid blue;background:red"/>
            <div id="percent" style="height:0;padding:5% 10%;background:green"/>""")
        try {
            snapshot(view)
            assertEquals(104.0, box(view, "content").width); assertEquals(44.0, box(view, "content").height)
            assertEquals(80.0, box(view, "border").width); assertEquals(50.0, box(view, "border").height)
            assertEquals(200.0, box(view, "percent").width); assertEquals(20.0, box(view, "percent").height)
            assertEquals(44.0, view.lookup("#border").layoutY)
        } finally { view.source = null }
    }

    @Test fun percentageSvgGeometryAndHtmlBoxesReflowAfterResize() = FxTest.run {
        val view = parse("<div id='inner' style='width:50%;height:10px;background:red'/>", "x='10%' y='20%'", "50%", "50%")
        try {
            snapshot(view)
            val foreign = view.lookup("#foreign") as SvgForeignObject
            assertEquals(24.0, foreign.layoutX); assertEquals(32.0, foreign.layoutY)
            assertEquals(120.0, foreign.width); assertEquals(60.0, box(view, "inner").width)
            snapshot(view, 480.0, 320.0)
            assertEquals(48.0, foreign.layoutX); assertEquals(64.0, foreign.layoutY)
            assertEquals(240.0, foreign.width); assertEquals(120.0, box(view, "inner").width)
        } finally { view.source = null }
    }

    @Test fun whitespaceCollapsesAcrossSpanBoundariesWithoutRemovingNonBreakingSpaces() = FxTest.run {
        val view = parse("""  A <span style="color:red">  B </span>  C&#160;<div style="display:none"/> D  <br/> E  """)
        try {
            snapshot(view)
            val flow = view.lookup(".svg-xhtml-flow") as TextFlow
            assertEquals("A B C\u00a0 D\nE", flow.children.filterIsInstance<Text>().joinToString("") { it.text })
        } finally { view.source = null }
    }

    @Test fun preWhitespaceAndNoWrapHaveNativeLayoutAndTextAlign() = FxTest.run {
        val view = parse("""<div id="pre" style="white-space:pre;font:20px monospace"> A  B
 C</div><div id="nowrap" style="white-space:nowrap;text-align:right;font:20px monospace">long text cannot wrap here</div>""", width = "80")
        try {
            snapshot(view)
            val pre = view.lookup("#pre") as Group
            assertTrue(pre.lookupAll(".svg-xhtml-text").filterIsInstance<Text>().single().text.startsWith(" A  B\n C"))
            val nowrap = view.lookup("#nowrap") as Group
            val flow = nowrap.lookup(".svg-xhtml-flow") as TextFlow
            assertTrue(flow.width > 80)
            assertEquals(80.0 - flow.width, flow.layoutX, 0.01)
        } finally { view.source = null }
    }

    @Test fun fontShorthandResetsInheritedWeightAndSpanStylesInherit() = FxTest.run {
        val view = parse("""<div style="font:bold 20px sans-serif;color:blue"><span id="a">A</span>
            <span id="b" style="font:italic 10px serif;color:currentColor">B</span>
            <span id="c" style="font-size:150%;color:red !important;color:green">C</span></div>""")
        try {
            snapshot(view)
            val a = view.lookup("#a") as Text; val b = view.lookup("#b") as Text; val c = view.lookup("#c") as Text
            assertEquals(20.0, a.font.size); assertTrue(a.font.style.contains("bold", true))
            assertEquals(10.0, b.font.size); assertFalse(b.font.style.contains("bold", true)); assertTrue(b.font.style.contains("italic", true))
            assertEquals(Color.BLUE, b.fill)
            assertEquals(30.0, c.font.size); assertEquals(Color.RED, c.fill)
        } finally { view.source = null }
    }

    @Test fun svgAndHtmlOverflowHaveIndependentClipping() = FxTest.run {
        for (overflow in listOf("visible", "hidden")) {
            val view = parse("<div style='width:100px;height:40px;background:red'/>", "x='10' y='10' overflow='$overflow'", "50", "20")
            try {
                val pixels = snapshot(view).pixelReader
                assertEquals(Color.RED, pixels.getColor(20, 15))
                assertEquals(if (overflow == "visible") Color.RED else Color.TRANSPARENT, pixels.getColor(70, 15))
                assertEquals(if (overflow == "visible") Color.RED else Color.TRANSPARENT, pixels.getColor(20, 35))
            } finally { view.source = null }
        }
        val nested = parse("""<div style="width:40px;height:20px;overflow:hidden;border-radius:8px;background:blue">
            <div style="width:100px;height:50px;background:red"/></div>""")
        try {
            val pixels = snapshot(nested).pixelReader
            assertEquals(Color.RED, pixels.getColor(15, 10))
            assertEquals(Color.TRANSPARENT, pixels.getColor(50, 10))
            assertEquals(Color.TRANSPARENT, pixels.getColor(1, 1))
        } finally { nested.source = null }
    }

    @Test fun zeroDimensionsHideEvenWhenOverflowVisibleAndVisibilityCanBeOverridden() = FxTest.run {
        for ((width, height) in listOf("0" to "20", "20" to "0")) {
            val view = parse("<div style='width:100px;height:100px;background:red'/>", width = width, height = height)
            try { assertEquals(0.0, snapshot(view).pixelReader.getColor(10, 10).opacity) } finally { view.source = null }
        }
        val view = parse("<span id='hidden'>hidden</span><span id='shown' style='visibility:visible'>shown</span>", "visibility='hidden'")
        try {
            snapshot(view)
            assertFalse((view.lookup("#hidden") as Text).isVisible)
            assertTrue((view.lookup("#shown") as Text).isVisible)
            assertTrue((view.lookup("#foreign") as SvgForeignObject).isVisible)
        } finally { view.source = null }
    }

    @Test fun unsupportedHtmlCssAndExecutableAttributesAreDiagnosedWithoutResourceRequests() = FxTest.run {
        var requests = 0
        val options = SvgOptions(resourceResolver = SvgResourceResolver { requests++; null })
        val view = parse("""<span onclick="alert(1)" style="background:url(https://invalid.example/pixel);position:absolute">safe</span>
            <script src="https://invalid.example/script">unsafe</script><img src="https://invalid.example/image"/>
            <div style="display:none"><span onload="alert(2)"/><iframe src="https://invalid.example/frame"/></div>""", options = options)
        try {
            snapshot(view)
            val messages = view.diagnostics.joinToString("\n") { it.message }
            for (token in listOf("onclick", "background", "position", "script", "img", "onload", "iframe")) assertContains(messages, token)
            assertEquals(0, requests)
            assertEquals(listOf("safe"), texts(view).map { it.text })
        } finally { view.source = null }
    }

    @Test fun strictModeRejectsUnsupportedSubsetAndMalformedLengths() = FxTest.run {
        for (html in listOf("<p>unsupported</p>", "<span onclick='run()'>text</span>",
            "<div style='padding:-1px'/>", "<div style='border-radius:20%'/>", "<div style='font:small-caps 20px serif'/>",
            "<div style='display:flex'/>", "<div style='overflow:scroll'/>", "<div style='width:20'/>",
            "<span style='color:url(https://invalid.example)'/>")) {
            assertFailsWith<SvgException>(html) { parse(html) }
        }
    }

    @Test fun unsupportedEmergencyWordWrappingIsExplicitAndNoWrapRemainsAvailable() = FxTest.run {
        for (mode in listOf("normal", "pre-wrap")) {
            val html = "<div style='font:20px monospace;white-space:$mode'>abcdefghij</div>"
            assertFailsWith<SvgException>(mode) { parse(html, width = "40") }
            val view = parse(html, width = "40", options = SvgOptions())
            try {
                snapshot(view)
                assertTrue(view.diagnostics.any { "unbreakable text exceeds the line width" in it.message })
            } finally { view.source = null }
        }
        for (mode in listOf("nowrap", "pre")) {
            val view = parse("<div id='line' style='font:20px monospace;white-space:$mode'>abcdefghij</div>", width = "40")
            try {
                snapshot(view)
                assertTrue(view.diagnostics.isEmpty())
                assertTrue(box(view, "line").height in 20.0..30.0)
            } finally { view.source = null }
        }
    }

    @Test fun inheritedEmBoxLengthsUseParentComputedFontSize() = FxTest.run {
        val view = parse("""<div style="font-size:20px;padding:1em;border:0.5em solid red;width:8em">
            <div id="child" style="font-size:10px;padding:inherit;border:inherit;width:inherit;height:0"/></div>""", width = "240")
        try {
            snapshot(view)
            val child = box(view, "child")
            assertEquals(10.0, child.strokeWidth)
            assertEquals(220.0, child.width)
            assertEquals(60.0, child.height)
        } finally { view.source = null }
    }

    @Test fun absentBordersComputeZeroWidthBeforeInheritance() = FxTest.run {
        val view = parse("""<div style="border:10px none red">
            <div id="child" style="border-width:inherit;border-style:solid;height:20px"/></div>""")
        try {
            snapshot(view)
            assertEquals(0.0, box(view, "child").strokeWidth)
            assertEquals(20.0, box(view, "child").height)
        } finally { view.source = null }
    }

    @Test fun terminalBrEndsOneLineWithoutCreatingAnotherPhantomLine() = FxTest.run {
        val view = parse("""<div id="plain" style="font:20px monospace">A</div>
            <div id="one" style="font:20px monospace">A<br/></div>
            <div id="empty" style="font:20px monospace"><br/></div>
            <div id="two" style="font:20px monospace">A<br/><br/></div>""")
        try {
            snapshot(view)
            val lineHeight = box(view, "plain").height
            assertTrue(lineHeight > 0)
            assertEquals(lineHeight, box(view, "one").height, 0.001)
            assertEquals(lineHeight, box(view, "empty").height, 0.001)
            assertEquals(lineHeight * 2, box(view, "two").height, 0.001)
        } finally { view.source = null }
    }

    @Test fun filterObjectBoundingBoxUsesDeclaredViewportRatherThanAutoHeightHtml() = FxTest.run {
        val view = SvgView.parse("""<svg width="240" height="160"><defs>
            <filter id="f" x="0" y="0" width="1" height="1"><feFlood flood-color="red"/></filter></defs>
            <foreignObject x="20" y="20" width="80" height="60" filter="url(#f)">
            <div xmlns="http://www.w3.org/1999/xhtml" style="height:10px;background:blue"/></foreignObject></svg>""", SvgOptions(strict = true))
        try {
            val pixels = snapshot(view).pixelReader
            assertTrue(view.diagnostics.isEmpty())
            assertEquals(Color.RED, pixels.getColor(30, 70))
            assertEquals(Color.TRANSPARENT, pixels.getColor(30, 85))
            assertEquals(Color.TRANSPARENT, pixels.getColor(15, 25))
        } finally { view.source = null }
    }

    @Test fun objectBoundingBoxClipAndMaskUseForeignViewport() = FxTest.run {
        for (effect in listOf("clip-path", "mask")) {
            val definition = if (effect == "mask") """<mask id="effect" maskUnits="objectBoundingBox" maskContentUnits="objectBoundingBox" x="0" y="0" width="1" height="1">
                <rect width="0.5" height="1" fill="white"/></mask>"""
                else """<clipPath id="effect" clipPathUnits="objectBoundingBox"><rect width="0.5" height="1"/></clipPath>"""
            val view = SvgView.parse("""<svg width="240" height="160"><defs>$definition</defs>
                <foreignObject x="20" y="20" width="80" height="60" $effect="url(#effect)">
                <div xmlns="http://www.w3.org/1999/xhtml" style="height:100%;background:red"/></foreignObject></svg>""", SvgOptions(strict = true))
            try {
                val pixels = snapshot(view).pixelReader
                assertTrue(view.diagnostics.isEmpty())
                assertEquals(Color.RED, pixels.getColor(30, 70), effect)
                assertEquals(Color.TRANSPARENT, pixels.getColor(70, 70), effect)
            } finally { view.source = null }
        }
    }

    @Test fun expandedNativeNodeBudgetIsEnforcedForHtmlAndReuse() = FxTest.run {
        val xml = """<svg><foreignObject width="10" height="10"><div xmlns="http://www.w3.org/1999/xhtml">A<span>B</span></div></foreignObject></svg>"""
        assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true, maxElements = 5)) }
        val view = SvgView.parse(xml, SvgOptions(maxElements = 5))
        try {
            snapshot(view)
            assertTrue(view.diagnostics.any { "foreignObject node limit" in it.message })
            assertNull(view.lookup(".svg-foreignObject"))
        } finally { view.source = null }
    }
}
