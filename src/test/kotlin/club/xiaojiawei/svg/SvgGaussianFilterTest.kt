package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgGaussianFilterTest {
    @Test fun shieldsBadgeCombinesTextLengthBlurGradientAndClipWithoutDiagnostics() = FxTest.run {
        val view = SvgView.load(javaClass.getResource("/svg/shields-release.svg")!!, SvgOptions(strict = true))
        view.isManaged = false
        Scene(Group(view))
        view.resize(122.0, 20.0); view.applyCss(); view.layout()
        val image = view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        assertEquals(122.0, image.width)
        assertEquals(20.0, image.height)
        assertEquals(6, view.lookupAll(".svg-text").size)
        assertEquals(2, view.lookupAll(".svg-filter-output").size)
        assertTrue(view.diagnostics.isEmpty())
        val background = image.pixelReader.getColor(115, 3)
        assertTrue(background.blue > background.red)
        val whitePixels = (3 until 17).sumOf { y -> (4 until 118).count { x ->
            val color = image.pixelReader.getColor(x, y)
            color.red > .8 && color.green > .8 && color.blue > .8
        } }
        assertTrue(whitePixels > 20, "Badge text must remain visible")
    }

    @Test fun blurSpreadsAlphaAndKeepsSourceColor() = FxTest.run {
        val view = view("2")
        val pixels = snapshot(view).pixelReader
        val edge = pixels.getColor(38, 50)
        assertTrue(edge.opacity in .05.. .45)
        assertEquals(1.0, edge.red, .03)
        assertEquals(0.0, pixels.getColor(10, 50).opacity)
        assertTrue(pixels.getColor(40, 50).opacity in .4.. .8)
        assertTrue(view.diagnostics.isEmpty())
    }

    @Test fun supportsDifferentAxesAndZeroOrNegativeDeviation() = FxTest.run {
        val horizontal = snapshot(view("4 0")).pixelReader
        assertTrue(horizontal.getColor(35, 50).opacity > .02)
        assertEquals(0.0, horizontal.getColor(50, 35).opacity)
        val vertical = snapshot(view("0 4")).pixelReader
        assertTrue(vertical.getColor(50, 35).opacity > .02)
        assertEquals(0.0, vertical.getColor(35, 50).opacity)
        val uneven = snapshot(view("2 4")).pixelReader
        assertTrue(uneven.getColor(50, 36).opacity > uneven.getColor(36, 50).opacity)
        for (deviation in listOf("0", "-1")) {
            val unchanged = snapshot(view(deviation)).pixelReader
            assertEquals(0.0, unchanged.getColor(39, 50).opacity)
            assertEquals(1.0, unchanged.getColor(40, 50).opacity)
        }
    }

    @Test fun filterInputRegionAndPrimitiveOutputRegionHaveDifferentClippingOrder() = FxTest.run {
        val inputClip = view("3", filterAttributes = "filterUnits=\"userSpaceOnUse\" x=\"40\" y=\"0\" width=\"60\" height=\"100\"",
            content = """<rect id="r" x="20" y="40" width="40" height="20" fill="red" filter="url(#f)"/>""")
        val inputPixels = snapshot(inputClip).pixelReader
        assertEquals(0.0, inputPixels.getColor(39, 50).opacity)
        assertTrue(inputPixels.getColor(40, 50).opacity in .35.. .75)
        val outputClip = snapshot(view("3", primitiveAttributes = "x=\"50\" y=\"0\" width=\"50\" height=\"100\"")).pixelReader
        assertEquals(0.0, outputClip.getColor(49, 50).opacity)
        assertTrue(outputClip.getColor(50, 50).opacity > .95)
    }

    @Test fun svgClipIsAppliedAfterBlurAndTransformsMoveTheWholeFilter() = FxTest.run {
        val clipped = view("3", extraDefinitions = """<clipPath id="c"><rect width="50" height="100"/></clipPath>""",
            content = """<rect id="r" x="40" y="40" width="20" height="20" fill="red"
                filter="url(#f)" clip-path="url(#c)"/>""")
        val pixels = snapshot(clipped).pixelReader
        assertTrue(pixels.getColor(48, 50).opacity > .95)
        assertEquals(0.0, pixels.getColor(50, 50).opacity)
        val transformed = view("1", content = """<rect id="r" x="10" y="10" width="10" height="10" fill="red"
            transform="translate(20 10) scale(2)" filter="url(#f)"/>""")
        val moved = snapshot(transformed).pixelReader
        assertTrue(moved.getColor(38, 40).opacity > .05)
        assertEquals(0.0, moved.getColor(18, 40).opacity)
    }

    @Test fun boundingBoxUnitsTrackPercentageGeometryAndResize() = FxTest.run {
        val view = SvgView.parse("""<svg><defs><filter id="f" primitiveUnits="objectBoundingBox"
            x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation=".1 .2"/></filter></defs>
            <rect id="r" x="40%" y="40%" width="20%" height="20%" fill="red" filter="url(#f)"/></svg>""", SvgOptions(strict = true))
        val first = snapshot(view, 100).pixelReader.getColor(38, 50).opacity
        val second = snapshot(view, 200).pixelReader.getColor(76, 100).opacity
        assertTrue(first > .05)
        assertEquals(first, second, .07)
        assertTrue(view.diagnostics.isEmpty())
    }

    @Test fun sourceAlphaAndCssPaintChangesRemainLive() = FxTest.run {
        val alpha = view("2", primitiveAttributes = "in=\"SourceAlpha\"")
        val black = snapshot(alpha).pixelReader.getColor(50, 50)
        assertTrue(black.opacity > .95)
        assertEquals(0.0, black.red)
        val colored = view("2")
        snapshot(colored)
        colored.lookup("#r").style = "-fx-fill: blue;"
        val blue = snapshot(colored).pixelReader.getColor(38, 50)
        assertTrue(blue.opacity > .05)
        assertEquals(1.0, blue.blue, .03)
        assertEquals(0.0, blue.red, .03)
    }

    @Test fun svgStylesCanAssignFilterWithoutInheritingItAndUnsupportedColorSpaceIsDiagnosed() = FxTest.run {
        val content = """<g id="r" class="soft"><rect x="40" y="40" width="10" height="20" fill="red"/>
            <rect x="50" y="40" width="10" height="20" fill="blue"/></g>"""
        val css = """<style>.soft { filter: url(#f); }</style>"""
        val srgb = view("2", content = content, extraDefinitions = css, primitiveAttributes = "color-interpolation-filters=\"sRGB\"")
        val blend = snapshot(srgb).pixelReader.getColor(49, 50)
        assertTrue(blend.red > .1 && blend.blue > .1)
        assertEquals(1, srgb.lookupAll(".svg-filter-output").size)
        val fallback = view("2", content = content, extraDefinitions = css, strict = false)
        assertTrue(fallback.diagnostics.any { it.message.contains("linearRGB") })
        assertNotNull(fallback.lookup("#r"))
        assertFailsWith<SvgException> { view("2", content = content, extraDefinitions = css) }
    }

    @Test fun largeDeviationUsesMultiplePassesInsteadOfClamping() = FxTest.run {
        val large = view("30", filterAttributes = "filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"200\" height=\"200\"",
            content = """<rect id="r" x="80" y="80" width="40" height="40" fill="red" filter="url(#f)"/>""", viewBox = "0 0 200 200")
        val opacity = snapshot(large, 200).pixelReader.getColor(100, 100).opacity
        assertTrue(opacity in .2.. .32, "Expected sigma=30 rather than a clamped sigma=21: $opacity")
    }

    @Test fun invalidAndUnsupportedFiltersPreserveSourceInLenientMode() = FxTest.run {
        val filters = listOf(
            """<filter id="f"><feOffset dx="2"/></filter>""",
            """<filter id="f"><feGaussianBlur stdDeviation="1 2 3"/></filter>""",
            """<filter id="f"><feGaussianBlur stdDeviation="1000"/></filter>""",
            """<filter id="f"><feGaussianBlur stdDeviation="2" edgeMode="wrap"/></filter>""",
            """<filter id="f"><feGaussianBlur/><feGaussianBlur/></filter>""",
            """<filter id="f" width="-1"><feGaussianBlur/></filter>"""
        )
        for (filter in filters) {
            val xml = """<svg>$filter<rect id="r" width="20" height="20" filter="url(#f)"/></svg>"""
            val fallback = SvgView.parse(xml)
            assertNotNull(fallback.lookup("#r"))
            assertTrue(fallback.diagnostics.isNotEmpty())
            assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true)) }
        }
        val absent = SvgView.parse("""<svg><rect id="r" width="20" height="20" filter="url(#missing)"/></svg>""")
        assertNotNull(absent.lookup("#r"))
        assertTrue(absent.diagnostics.any { it.message.contains("Missing filter") })
    }

    private fun view(deviation: String, primitiveAttributes: String = "", extraDefinitions: String = "",
                     content: String = """<rect id="r" x="40" y="40" width="20" height="20" fill="red" filter="url(#f)"/>""",
                     filterAttributes: String = "filterUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"100\" height=\"100\"",
                     viewBox: String = "0 0 100 100", strict: Boolean = true): SvgView = SvgView.parse("""<svg viewBox="$viewBox">
        <defs><filter id="f" $filterAttributes><feGaussianBlur stdDeviation="$deviation" $primitiveAttributes/></filter>
        $extraDefinitions</defs>$content</svg>""", SvgOptions(strict = strict))

    private fun snapshot(view: SvgView, size: Int = 100): WritableImage {
        view.isManaged = false
        if (view.scene == null) Scene(Group(view))
        view.resize(size.toDouble(), size.toDouble())
        view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
}
