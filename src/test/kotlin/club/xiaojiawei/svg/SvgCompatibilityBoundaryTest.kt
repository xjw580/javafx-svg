package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

/** Exact user reproductions must render their effects, not merely suppress diagnostics. */
class SvgCompatibilityBoundaryTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/svg/$name.svg"))

    private fun snapshot(view: SvgView, scale: Double = 1.0): WritableImage {
        view.isManaged = false
        Scene(Group(view))
        view.resize(640.0 * scale, 360.0 * scale)
        view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }

    private fun close(actual: Color, expected: Color, tolerance: Double = 2.0 / 255.0): Boolean =
        abs(actual.red - expected.red) <= tolerance && abs(actual.green - expected.green) <= tolerance &&
            abs(actual.blue - expected.blue) <= tolerance && abs(actual.opacity - expected.opacity) <= tolerance

    @Test fun advancedFixtureRendersDisplacedOutlineAndMorphologyGlowAtSupportedScalesInStrictMode() = FxTest.run {
        val source = fixture("advanced-filters").readText()
        for (scale in listOf(1.0, 1.25, 1.5)) {
            val view = SvgView.load(fixture("advanced-filters"), SvgOptions(strict = true))
            val unfiltered = SvgView.parse(source.replace(" filter=\"url(#rough)\"", "")
                .replace(" filter=\"url(#morphGlow)\"", ""), SvgOptions(strict = true))
            try {
                val pixels = snapshot(view, scale).pixelReader
                val plain = snapshot(unfiltered, scale).pixelReader
                assertTrue(view.diagnostics.isEmpty(), "scale=$scale: ${view.diagnostics}")
                assertTrue(close(pixels.getColor((100 * scale).toInt(), (180 * scale).toInt()), Color.web("#24b9cf")))
                assertTrue(close(pixels.getColor((189 * scale).toInt(), (180 * scale).toInt()), Color.web("#0b1327")))
                assertTrue(close(pixels.getColor((450 * scale).toInt(), (110 * scale).toInt()), Color.web("#614cab")))

                // Compare only the cyan ring, so a glow elsewhere cannot hide a skipped displacement.
                var displacedPixels = 0
                for (y in (55 * scale).toInt() until (305 * scale).toInt())
                    for (x in (60 * scale).toInt() until (318 * scale).toInt())
                        if (!close(pixels.getColor(x, y), plain.getColor(x, y), 0.08)) displacedPixels++
                assertTrue(displacedPixels > 80 * scale * scale,
                    "The turbulence/displacement must visibly change the outline at scale=$scale; changed=$displacedPixels")

                val glow = pixels.getColor((365 * scale).toInt(), (180 * scale).toInt())
                val background = plain.getColor((365 * scale).toInt(), (180 * scale).toInt())
                assertTrue(glow.red > background.red + 0.1 && glow.blue > background.blue + 0.1,
                    "The dilated purple glow must extend outside the rectangle at scale=$scale: $glow")
            } finally { view.source = null; unfiltered.source = null }
        }
    }

    @Test fun foreignObjectFixtureRendersHtmlBoxAndColoredSpansInStrictMode() = FxTest.run {
        for (scale in listOf(1.0, 1.25, 1.5)) {
            val view = SvgView.load(fixture("foreign-object"), SvgOptions(strict = true))
            try {
                val pixels = snapshot(view, scale).pixelReader
                assertTrue(view.diagnostics.isEmpty(), "scale=$scale: ${view.diagnostics}")
                assertTrue(close(pixels.getColor((10 * scale).toInt(), (10 * scale).toInt()), Color.web("#0b1327")))
                assertTrue(close(pixels.getColor((130 * scale).toInt(), (180 * scale).toInt()), Color.web("#193e54")),
                    "The XHTML box background must be painted")
                assertTrue(close(pixels.getColor((121 * scale).toInt(), (180 * scale).toInt()), Color.web("#35d3ca"), 0.05),
                    "The XHTML border must be painted")
                assertContains(view.lookupAll(".svg-text").filterIsInstance<Text>().map { it.text }, "SVG + foreignObject")
                val html = view.lookupAll(".svg-xhtml-text").filterIsInstance<Text>()
                val text = html.joinToString("") { it.text }
                for (word in listOf("HTML", "inside", "an SVG", "✓")) assertContains(text, word)
                assertTrue(html.any { "inside" in it.text && it.fill == Color.web("#66e7d8") })
                assertTrue(html.any { "✓" in it.text && it.fill == Color.web("#ffc282") })
                for ((colour, minimum) in listOf("#66e7d8" to 10, "#ffc282" to 3, "#f4fcff" to 30)) {
                    val expected = Color.web(colour)
                    var painted = 0
                    for (y in (151 * scale).toInt() until (230 * scale).toInt())
                        for (x in (144 * scale).toInt() until (496 * scale).toInt())
                            if (close(pixels.getColor(x, y), expected, 0.08)) painted++
                    assertTrue(painted >= minimum, "HTML $colour text must actually be visible at scale=$scale; pixels=$painted")
                }
            } finally { view.source = null }
        }
    }

    @Test fun regularFilterFixtureStillRendersWithoutDiagnosticsInStrictMode() = FxTest.run {
        val view = SvgView.load(fixture("regular-filters"), SvgOptions(strict = true))
        try {
            val pixels = snapshot(view, 1.5).pixelReader
            assertTrue(view.diagnostics.isEmpty(), view.diagnostics.toString())
            assertTrue(close(pixels.getColor(255, 267), Color.web("#d5ffff")))
            assertTrue(close(pixels.getColor(675, 165), Color.web("#7456d2")))
        } finally { view.source = null }
    }

    @Test fun genuinelyUnsupportedPrimitivesStillDiagnoseAndFailStrictMode() = FxTest.run {
        val source = """<svg width="640" height="360"><defs><filter id="f"><feUnsupported/></filter></defs>
            <rect width="640" height="360" fill="red" filter="url(#f)"/></svg>"""
        val view = SvgView.parse(source)
        try {
            assertEquals(Color.RED, snapshot(view).pixelReader.getColor(100, 100))
            assertTrue(view.diagnostics.any { it.message == "Unsupported filter primitive: feUnsupported" })
            assertFailsWith<SvgException> { SvgView.parse(source, SvgOptions(strict = true)) }
        } finally { view.source = null }
    }
}
