package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgFilterChainTest {
    private fun view(primitives: String, filter: String = "", content: String = """<rect id="r" x="20" y="20" width="20" height="20" fill="red" filter="url(#f)"/>""",
                     options: SvgOptions = SvgOptions(strict = true)): SvgView = SvgView.parse("""<svg viewBox="0 0 100 100"><defs>
        <filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100" $filter>$primitives</filter>
        </defs>$content</svg>""", options)

    private fun pixels(view: SvgView, size: Double = 100.0): WritableImage {
        view.isManaged = false
        if (view.scene == null) Scene(Group(view))
        view.resize(size, size); view.applyCss(); view.layout()
        return view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }

    @Test fun floodUsesPrimitiveStyleCurrentColorAndOpacityAndReplacesTheSource() = FxTest.run {
        val v = view("""<feFlood style="color: blue; flood-color: currentColor; flood-opacity: 50%"/>""")
        val c = pixels(v).pixelReader.getColor(80, 80)
        assertEquals(1.0, c.blue, .01); assertEquals(.5, c.opacity, .01)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun namedInputsCompositeAndMergeRenderEditorShadowChain() = FxTest.run {
        val v = view("""<feFlood flood-color="blue" result="color"/>
            <feComposite in="color" in2="SourceAlpha" operator="in" result="silhouette"/>
            <feOffset in="silhouette" dx="15" dy="20" result="shadow"/>
            <feMerge><feMergeNode in="shadow"/><feMergeNode in="SourceGraphic"/></feMerge>""")
        val p = pixels(v).pixelReader
        assertEquals(Color.RED, p.getColor(25, 25))
        assertEquals(Color.BLUE, p.getColor(45, 50))
        assertEquals(0.0, p.getColor(80, 80).opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun eachCompositeOperatorHasCorrectCoverageAndColour() = FxTest.run {
        val definitions = """<feFlood flood-color="blue" x="30" y="20" width="30" height="20" result="back"/>"""
        val expected = mapOf("over" to Color.RED, "in" to Color.RED, "out" to Color.TRANSPARENT,
            "atop" to Color.RED, "xor" to Color.TRANSPARENT, "lighter" to Color.MAGENTA)
        for ((operator, overlap) in expected) {
            val v = view(definitions + """<feComposite in="SourceGraphic" in2="back" operator="$operator"/>""", """color-interpolation-filters="sRGB"""")
            assertEquals(overlap, pixels(v).pixelReader.getColor(35, 25), operator)
        }
    }

    @Test fun arithmeticIncludesTransparentSpaceAndClampsPremultipliedChannels() = FxTest.run {
        val v = view("""<feComposite in="SourceGraphic" in2="SourceAlpha" operator="arithmetic" k4=".5"/>""", """color-interpolation-filters="sRGB"""")
        val c = pixels(v).pixelReader.getColor(80, 80)
        assertEquals(1.0, c.red, .01); assertEquals(.5, c.opacity, .01)
    }

    @Test fun linearRgbDefaultDiffersFromSrgbAndPerPrimitiveConversionWorks() = FxTest.run {
        val chain = """<feFlood flood-color="blue" result="back"/>
            <feFlood flood-color="red" flood-opacity=".5" result="front"/>
            <feComposite in="front" in2="back"/>"""
        val linear = pixels(view(chain)).pixelReader.getColor(50, 50)
        val srgb = pixels(view(chain, """color-interpolation-filters="sRGB"""")).pixelReader.getColor(50, 50)
        assertEquals(.735, linear.red, .02); assertEquals(.735, linear.blue, .02)
        assertEquals(.5, srgb.red, .02); assertEquals(.5, srgb.blue, .02)
        val mixed = pixels(view(chain.replace("<feComposite", "<feComposite color-interpolation-filters='sRGB'"))).pixelReader.getColor(50, 50)
        assertEquals(srgb, mixed)
    }

    @Test fun omittedInputsDuplicateNamesAndForwardReferencesFollowDocumentOrder() = FxTest.run {
        val v = view("""<feFlood flood-color="blue" result="a"/>
            <feFlood flood-color="red" result="a"/>
            <feComposite in="future" in2="a" operator="in"/>
            <feMerge><feMergeNode/><feMergeNode in="a"/></feMerge>""")
        assertEquals(Color.RED, pixels(v).pixelReader.getColor(50, 50))
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun emptyFiltersAndEmptyMergesAreTransparent() = FxTest.run {
        assertEquals(0.0, pixels(view("")).pixelReader.getColor(25, 25).opacity)
        assertEquals(0.0, pixels(view("<feMerge/>")).pixelReader.getColor(25, 25).opacity)
    }

    @Test fun primitiveRegionsClipAndPropagateToImplicitSubregions() = FxTest.run {
        val v = view("""<feFlood flood-color="blue" x="30" y="30" width="10" height="10"/>
            <feComposite in2="SourceAlpha" operator="arithmetic" k4="1" x="32" width="3"/>""")
        val p = pixels(v).pixelReader
        assertEquals(Color.WHITE, p.getColor(33, 50))
        assertEquals(0.0, p.getColor(31, 50).opacity)
        assertEquals(0.0, p.getColor(36, 50).opacity)
        val zero = view("""<feFlood width="0"/><feOffset/>""")
        assertEquals(0.0, pixels(zero).pixelReader.getColor(25, 25).opacity)
        val negative = view("""<feFlood width="-1"/>""")
        assertEquals(0.0, pixels(negative).pixelReader.getColor(25, 25).opacity)
    }

    @Test fun implicitRegionUsesTheDefinedInputRegionNotItsClippedOffscreen() = FxTest.run {
        val v = view("""<feFlood x="-50" y="0" width="100" height="100"/>
            <feComposite operator="arithmetic" k4="1" x="0"/>""")
        assertEquals(Color.WHITE, pixels(v).pixelReader.getColor(75, 50))
        val standard = view("""<feFlood x="-50" y="0" width="200" height="100"/>
            <feComposite in2="SourceGraphic" operator="arithmetic" k4="1" x="-50"/>""")
        assertEquals(Color.WHITE, pixels(standard).pixelReader.getColor(25, 50))
        assertEquals(0.0, pixels(standard).pixelReader.getColor(75, 50).opacity)
    }

    @Test fun dashedStrokePaintChangesInvalidateTheFilteredRaster() = FxTest.run {
        val v = view("""<feOffset/><feMerge><feMergeNode/></feMerge>""", content = """<path id="p" d="M10 50H90"
            fill="none" stroke="red" stroke-width="8" stroke-dasharray="10 10" filter="url(#f)"/>""")
        val before = pixels(v).pixelReader.getColor(15, 50)
        val path = v.lookup("#p") as javafx.scene.shape.SVGPath
        path.strokeDashOffset = 10.0
        val after = pixels(v).pixelReader.getColor(15, 50)
        assertTrue(before.opacity > .9); assertEquals(0.0, after.opacity)
        path.strokeDashArray.clear()
        assertTrue(pixels(v).pixelReader.getColor(15, 50).opacity > .9)
    }

    @Test fun objectBoundingBoxPrimitiveUnitsTrackOwnerGeometryAndViewScale() = FxTest.run {
        val v = view("""<feFlood flood-color="blue" x="25%" y="25%" width="50%" height="50%"/>
            <feComposite in2="SourceGraphic" operator="in"/>""", """primitiveUnits="objectBoundingBox"""")
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(30, 30))
        assertEquals(0.0, pixels(v).pixelReader.getColor(21, 21).opacity)
        assertEquals(Color.BLUE, pixels(v, 200.0).pixelReader.getColor(60, 60))
    }

    @Test fun cssAndThemeRefreshTheHiddenSourceAndStableFramesReuseRaster() = FxTest.run {
        val v = view("""<feOffset dx="5"/><feMerge><feMergeNode/></feMerge>""")
        assertEquals(Color.RED, pixels(v).pixelReader.getColor(30, 30))
        val chain = findChain(v)
        val source = v.lookup("#r")
        assertFalse(source.parent.isMouseTransparent)
        assertNull(source.parent.clip)
        assertTrue(source.contains(30.0, 30.0))
        val count = chain.rasterizations
        pixels(v)
        assertEquals(count, chain.rasterizations)
        (v.lookup("#r") as Rectangle).style = "-fx-fill: blue;"
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(30, 30))
        assertTrue(chain.rasterizations > count)
    }

    @Test fun hidingAndShowingTheOriginalCssTargetClearsAndRestoresTheFilteredImage() = FxTest.run {
        val v = view("<feFlood flood-color='blue'/>")
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(30, 30))
        val source = v.lookup("#r") as Rectangle
        source.isVisible = false
        assertEquals(0.0, pixels(v).pixelReader.getColor(30, 30).opacity)
        source.isVisible = true
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(30, 30))
    }

    @Test fun externalTranslationReusesTheLocalRaster() = FxTest.run {
        val v = view("<feFlood flood-color='blue'/>")
        pixels(v)
        val chain = findChain(v)
        val count = chain.rasterizations
        v.translateX = 10.0
        pixels(v)
        assertEquals(count, chain.rasterizations)
    }

    @Test fun ownerOpacityAppliesAfterFloodButChildOpacityStaysInSourceAlpha() = FxTest.run {
        val v = view("""<feFlood flood-color="blue"/>""", content = """<rect id="r" width="40" height="40" opacity=".5" filter="url(#f)"/>""")
        assertEquals(.5, pixels(v).pixelReader.getColor(50, 50).opacity, .02)
        (v.lookup("#r") as Rectangle).style = "-fx-opacity: 0.25;"
        assertEquals(.25, pixels(v).pixelReader.getColor(50, 50).opacity, .02)
        val group = view("""<feComposite in="SourceAlpha" in2="SourceAlpha" operator="in"/>""", content = """<g filter="url(#f)"><rect width="40" height="40" opacity=".5"/></g>""")
        assertEquals(.25, pixels(group).pixelReader.getColor(20, 20).opacity, .02)
    }

    @Test fun multicolourGaussianAndDropShadowWorkInsideChains() = FxTest.run {
        val blur = view("""<feGaussianBlur stdDeviation="2"/><feMerge><feMergeNode/></feMerge>""", content = """<g filter="url(#f)"><rect x="20" y="20" width="10" height="20" fill="red"/><rect x="30" y="20" width="10" height="20" fill="blue"/></g>""")
        val c = pixels(blur).pixelReader.getColor(30, 30)
        assertTrue(c.red > .3 && c.blue > .3)
        assertTrue(blur.diagnostics.isEmpty())
        val shadow = view("""<feDropShadow dx="20" dy="20" stdDeviation="0" flood-color="blue"/><feMerge><feMergeNode/></feMerge>""")
        val p = pixels(shadow).pixelReader
        assertEquals(Color.RED, p.getColor(25, 25)); assertEquals(Color.BLUE, p.getColor(45, 45))
    }

    @Test fun unsupportedFeaturesAndBudgetsNeverProduceStaleSuccess() = FxTest.run {
        for (p in listOf("<feUnsupported/>", "<feComposite in='BackgroundImage'/>", "<feComposite operator='multiply'/>",
            "<feMerge><feFlood/></feMerge>", "<feFlood><animate attributeName='flood-color'/></feFlood>")) {
            assertFailsWith<SvgException>(p) { view(p) }
        }
        assertFailsWith<SvgException> { view("<feFlood/><feMerge/>", options = SvgOptions(strict = true, maxFilterPrimitives = 1)) }
        assertFailsWith<SvgException> { view("<feFlood/>", options = SvgOptions(strict = true, maxFilterPixels = 100)) }
        val loose = view("<feFlood flood-color='blue'/>", options = SvgOptions(maxFilterPixels = 150_000))
        assertEquals(Color.BLUE, pixels(loose).pixelReader.getColor(25, 25))
        assertEquals(0.0, pixels(loose, 200.0).pixelReader.getColor(50, 50).opacity)
        assertTrue(loose.diagnostics.any { it.message.contains("maxFilterPixels") })
        assertEquals(Color.BLUE, pixels(loose).pixelReader.getColor(25, 25))
    }

    @Test fun filtersShareBothPixelAndWorkBudgetsWithReplacementAndRelease() = FxTest.run {
        val first = findChain(view("<feFlood/>"))
        val second = findChain(view("<feFlood/>"))
        val budget = SvgFilterBudget(100)
        budget.reserve(first, 40); budget.reserve(second, 50)
        assertFailsWith<IllegalArgumentException> { budget.reserve(first, 60) }
        budget.reserveWork(first, 150_000_000.0)
        assertFailsWith<IllegalArgumentException> { budget.reserveWork(second, 60_000_000.0) }
        budget.reserveWork(first, 140_000_000.0)
        budget.reserveWork(second, 60_000_000.0)
        budget.release(first)
        budget.reserve(second, 100)
        budget.reserveWork(second, 200_000_000.0)
        budget.release(second)
        budget.reserve(first, 100)
        budget.reserveWork(first, 200_000_000.0)
    }

    @Test fun filterTransformAndClipApplyAfterCompositing() = FxTest.run {
        val v = SvgView.parse("""<svg viewBox="0 0 100 100"><defs><filter id="f"><feFlood flood-color="blue"/>
            <feComposite in2="SourceAlpha" operator="in"/></filter><clipPath id="c"><rect width="30" height="100"/></clipPath></defs>
            <rect x="20" y="20" width="20" height="20" transform="translate(10 10)" filter="url(#f)" clip-path="url(#c)"/></svg>""", SvgOptions(strict = true))
        val p = pixels(v).pixelReader
        assertEquals(Color.BLUE, p.getColor(35, 35)); assertEquals(0.0, p.getColor(45, 35).opacity)
    }

    @Test fun deadResultsDoNotAccumulateButLiveNamedDagInputsAreStillBudgeted() = FxTest.run {
        val linear = "color-interpolation-filters='sRGB'"
        val repeated = (0 until 60).joinToString("") { "<feOffset result='unused$it'/>" }
        val compact = view(repeated, linear, options = SvgOptions(strict = true, maxFilterPixels = 70_000))
        assertEquals(Color.RED, pixels(compact).pixelReader.getColor(25, 25))
        val retained = (0 until 12).joinToString("") { "<feFlood result='layer$it'/>" } +
            "<feMerge>" + (0 until 12).joinToString("") { "<feMergeNode in='layer$it'/>" } + "</feMerge>"
        assertFailsWith<SvgException> {
            view(retained, linear, options = SvgOptions(strict = true, maxFilterPixels = 70_000))
        }
    }

    @Test fun completedChainsReleaseScratchButKeepTheirVisibleOutputsBudgeted() = FxTest.run {
        val content = "<rect x='20' y='20' width='20' height='20' fill='red' filter='url(#f)'/>"
        val v = view("<feFlood flood-color='blue'/>", content = content.repeat(5),
            options = SvgOptions(strict = true, maxFilterPixels = 100_000))
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(25, 25))
        assertFailsWith<SvgException> {
            view("<feFlood/>", content = content.repeat(15),
                options = SvgOptions(strict = true, maxFilterPixels = 100_000))
        }
    }

    @Test fun budgetValidationRejectsNegativeOverflowAndNonfiniteReservations() = FxTest.run {
        val owner = findChain(view("<feFlood/>"))
        val budget = SvgFilterBudget(100)
        assertFailsWith<IllegalArgumentException> { budget.reserve(owner, -1) }
        assertFailsWith<IllegalArgumentException> { budget.reserve(owner, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { budget.reserveWork(owner, -1.0) }
        assertFailsWith<IllegalArgumentException> { budget.reserveWork(owner, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { budget.reserveWork(owner, Double.POSITIVE_INFINITY) }
        budget.reserve(owner, 100)
        budget.reserveWork(owner, 200_000_000.0)
    }

    @Test fun identityPrimitiveCroppingNeverMutatesSharedNamedInput() = FxTest.run {
        for (identity in listOf("feOffset", "feGaussianBlur")) {
            val v = view("""<feFlood flood-color="blue" result="saved"/>
                <$identity in="saved" x="30" y="30" width="10" height="10" result="tiny"/>
                <feMerge><feMergeNode in="saved"/><feMergeNode in="tiny"/></feMerge>""",
                "color-interpolation-filters='sRGB'")
            assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(80, 80), identity)
            assertTrue(v.diagnostics.isEmpty())
        }
    }

    @Test fun releasingPixelScratchDoesNotReleaseAggregateWorkProtection() = FxTest.run {
        val content = "<rect width='20' height='20' filter='url(#f)'/>"
        val failure = assertFailsWith<SvgException> {
            view("<feFlood flood-color='white'/><feGaussianBlur stdDeviation='10'/>",
                content = content.repeat(40), options = SvgOptions(strict = true))
        }
        assertTrue(failure.message!!.contains("per-view 200000000"), failure.message)
    }

    @Test fun alphaConversionAliasDoesNotLetSubregionCroppingMutateSavedInput() = FxTest.run {
        val v = view("""<feGaussianBlur in="SourceAlpha" stdDeviation="0" color-interpolation-filters="sRGB" result="saved"/>
            <feGaussianBlur in="saved" stdDeviation="0" x="30" y="30" width="5" height="5"/>
            <feFlood flood-color="blue" result="ink"/>
            <feComposite in="ink" in2="saved" operator="in"/>""")
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(25, 25))
        assertEquals(Color.BLUE, pixels(v).pixelReader.getColor(35, 35))
    }

    @Test fun mergeFanInWorkIsRejectedBeforeScanningRepeatedAlphaConversions() = FxTest.run {
        val failure = assertFailsWith<SvgException> {
            view("<feMerge>" + "<feMergeNode in='SourceAlpha'/>".repeat(5000) + "</feMerge>")
        }
        assertTrue(failure.message!!.contains("work limit"), failure.message)
    }

    private fun findChain(node: Node): SvgFilterChain = if (node is SvgFilterChain) node else
        (node as javafx.scene.Parent).childrenUnmodifiable.firstNotNullOfOrNull { child ->
            runCatching { findChain(child) }.getOrNull()
        } ?: error("Missing chain")
}
