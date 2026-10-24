package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgMaskTest {
    @Test fun alphaUsesOpacityRegardlessOfBlackOrWhiteAndIgnoresMaskElementDisplay() = FxTest.run {
        val view = view("""<svg viewBox="0 0 40 20"><defs>
            <mask id="m" mask-type="alpha" display="none" opacity="0">
                <rect width="20" height="20" fill="black" opacity=".5"/>
                <rect x="20" width="20" height="20" fill="white" opacity=".5"/>
            </mask></defs><rect width="40" height="20" fill="red" mask="url(#m)"/></svg>""", 40.0, 20.0)
        val image = snapshot(view)
        assertEquals(.5, image.pixelReader.getColor(10, 10).opacity, .03)
        assertEquals(.5, image.pixelReader.getColor(30, 10).opacity, .03)
        assertEquals(1.0, image.pixelReader.getColor(10, 10).red, .03)
    }

    @Test fun boundingBoxContentUsesGeometryWithoutStrokeAndTracksResize() = FxTest.run {
        val view = view("""<svg><defs><mask id="m" mask-type="alpha" maskContentUnits="objectBoundingBox">
            <rect x=".25" width=".25" height="1"/></mask></defs>
            <rect id="r" x="10" y="10" width="50%" height="20" fill="blue" stroke="blue" stroke-width="10" mask="url(#m)"/>
            </svg>""", 40.0, 40.0)
        var image = snapshot(view)
        assertEquals(0.0, image.pixelReader.getColor(14, 20).opacity)
        assertEquals(1.0, image.pixelReader.getColor(17, 20).opacity, .02)
        assertEquals(0.0, image.pixelReader.getColor(23, 20).opacity)
        view.resize(80.0, 40.0); view.layout()
        image = snapshot(view)
        assertEquals(0.0, image.pixelReader.getColor(17, 20).opacity)
        assertEquals(1.0, image.pixelReader.getColor(25, 20).opacity, .02)
        assertEquals(0.0, image.pixelReader.getColor(35, 20).opacity)
    }

    @Test fun maskRegionAndOwnerTransformShareTheLocalUserSpace() = FxTest.run {
        val view = view("""<svg viewBox="0 0 60 40"><defs>
            <mask id="m" mask-type="alpha" maskUnits="userSpaceOnUse" x="5" y="0" width="10" height="20">
                <rect width="20" height="20"/></mask></defs>
            <g transform="translate(10 5)"><rect width="20" height="20" fill="red" transform="translate(10 5)" mask="url(#m)"/></g>
            </svg>""", 60.0, 40.0)
        val image = snapshot(view)
        assertEquals(0.0, image.pixelReader.getColor(23, 15).opacity)
        assertEquals(1.0, image.pixelReader.getColor(30, 15).opacity, .02)
        assertEquals(0.0, image.pixelReader.getColor(37, 15).opacity)
    }

    @Test fun boundingBoxRegionAndZeroOrNegativeRegionsClipAsSpecified() = FxTest.run {
        for (width in listOf("50%", "0", "-1")) {
            val view = view("""<svg viewBox="0 0 40 20"><defs>
                <mask id="m" mask-type="alpha" x="0" y="0" width="$width" height="100%">
                    <rect width="40" height="20"/></mask></defs>
                <rect width="40" height="20" fill="blue" mask="url(#m)"/></svg>""", 40.0, 20.0)
            val image = snapshot(view)
            assertEquals(if (width == "50%") 1.0 else 0.0, image.pixelReader.getColor(10, 10).opacity, .02)
            assertEquals(0.0, image.pixelReader.getColor(30, 10).opacity)
        }
    }

    @Test fun cssCanUpdateMaskOpacityWhileUnifiedThemeOnlyChangesTheSource() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs><g fill="black"><mask id="m" mask-type="alpha">
            <rect id="maskRect" width="20" height="20" fill-opacity=".5"/></mask></g></defs>
            <rect width="20" height="20" fill="blue" mask="url(#m)"/></svg>""", 20.0, 20.0)
        val mask = view.lookup("#maskRect") as Rectangle
        assertEquals(.5, snapshot(view).pixelReader.getColor(10, 10).opacity, .03)
        view.svgFill = Color.RED
        assertEquals(.5, (mask.fill as Color).opacity, .03)
        mask.style = "-fx-fill: rgba(0,0,0,.25);"
        view.applyCss(); view.requestLayout(); view.layout()
        val pixel = snapshot(view).pixelReader.getColor(10, 10)
        assertEquals(.25, pixel.opacity, .03)
        assertEquals(1.0, pixel.red, .03)
        mask.style = "-fx-fill: rgba(0,0,0,.75);"
        view.applyCss(); view.requestLayout(); view.layout()
        assertEquals(.75, snapshot(view).pixelReader.getColor(10, 10).opacity, .03)
    }

    @Test fun nestedMasksUseReferencesAndOwnerClipMultiplyOpacity() = FxTest.run {
        val view = view("""<svg viewBox="0 0 40 20"><defs>
            <rect id="tile" width="20" height="20" fill="black" opacity=".5"/>
            <mask id="inner" mask-type="alpha"><rect width="40" height="20" opacity=".5"/></mask>
            <mask id="outer" mask-type="alpha"><use href="#tile" mask="url(#inner)"/></mask>
            <clipPath id="clip"><rect width="10" height="20"/></clipPath>
            </defs><rect width="20" height="20" fill="red" mask="url(#outer)" clip-path="url(#clip)"/></svg>""", 40.0, 20.0)
        val image = snapshot(view)
        assertEquals(.25, image.pixelReader.getColor(5, 10).opacity, .03)
        assertEquals(0.0, image.pixelReader.getColor(15, 10).opacity)
    }

    @Test fun maskOnUseFollowsItsPositionAndViewBoxScaling() = FxTest.run {
        val view = view("""<svg viewBox="0 0 40 20"><defs>
            <rect id="tile" width="20" height="20" fill="red"/>
            <mask id="m" mask-type="alpha"><rect width="10" height="20"/></mask></defs>
            <use href="#tile" x="10" mask="url(#m)"/></svg>""", 80.0, 40.0)
        val image = snapshot(view)
        assertEquals(0.0, image.pixelReader.getColor(10, 20).opacity)
        assertEquals(1.0, image.pixelReader.getColor(30, 20).opacity, .02)
        assertEquals(0.0, image.pixelReader.getColor(50, 20).opacity)
    }

    @Test fun maskCanBeAssignedThroughSvgStylesheetAndInlineStyle() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><style>.masked { mask: url(#m); } mask { mask-type: alpha; }</style>
            <mask id="m"><rect width="10" height="20"/></mask>
            <rect class="masked" width="20" height="20" fill="red"/></svg>""", 20.0, 20.0)
        assertEquals(1.0, snapshot(view).pixelReader.getColor(5, 10).opacity, .02)
        assertEquals(0.0, snapshot(view).pixelReader.getColor(15, 10).opacity)
    }

    @Test fun invalidAndCircularMaskReferencesAreDiagnosedWithoutExposingSource() = FxTest.run {
        val cases = listOf(
            """<svg><rect width="20" height="20" fill="red" mask="url(#missing)"/></svg>""",
            """<svg><defs><mask id="m" mask-type="alpha"><rect width="20" height="20" mask="url(#m)"/></mask></defs>
                <rect width="20" height="20" mask="url(#m)"/></svg>""",
            """<svg><rect width="20" height="20" mask="url(https://example.invalid/m.svg#m)"/></svg>"""
        )
        for (xml in cases) {
            assertFailsWith<SvgException> { SvgView.parse(xml, SvgOptions(strict = true)) }
            val loose = view(xml, 20.0, 20.0, SvgOptions())
            assertTrue(loose.diagnostics.isNotEmpty())
            assertEquals(0.0, snapshot(loose).pixelReader.getColor(5, 5).opacity)
        }
    }

    @Test fun rasterBudgetRejectsOversizedMasksAndCanRecoverAtSmallerSize() = FxTest.run {
        val xml = """<svg><defs><mask id="m" mask-type="alpha"><rect width="100%" height="100%"/></mask></defs>
            <rect width="100%" height="100%" fill="red" mask="url(#m)"/></svg>"""
        val loose = view(xml, 20.0, 20.0, SvgOptions(maxMaskPixels = 1000))
        assertTrue(loose.diagnostics.isEmpty())
        assertEquals(1.0, snapshot(loose).pixelReader.getColor(5, 5).opacity, .02)
        loose.resize(40.0, 40.0); loose.layout()
        assertTrue(loose.diagnostics.any { it.message.contains("maxMaskPixels") })
        assertEquals(0.0, snapshot(loose).pixelReader.getColor(5, 5).opacity)
        loose.resize(10.0, 10.0); loose.layout()
        assertEquals(1.0, snapshot(loose).pixelReader.getColor(5, 5).opacity, .02)
    }

    @Test fun ancestorScalingRefreshesResolutionAndRespectsThePixelBudget() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs><mask id="m" mask-type="alpha">
            <rect width="20" height="20"/></mask></defs><rect width="20" height="20" fill="red" mask="url(#m)"/></svg>""",
            20.0, 20.0, SvgOptions(maxMaskPixels = 700))
        assertEquals(1.0, snapshot(view).pixelReader.getColor(5, 5).opacity, .02)
        view.scene.root.scaleX = 2.0
        view.scene.root.scaleY = 2.0
        view.layout()
        assertEquals(0.0, snapshot(view).pixelReader.getColor(5, 5).opacity)
        view.scene.root.scaleX = 1.0
        view.scene.root.scaleY = 1.0
        view.layout()
        assertEquals(1.0, snapshot(view).pixelReader.getColor(5, 5).opacity, .02)
    }

    @Test fun invalidMaskConstructionDoesNotLeaveOrphanRasters() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 20"><defs>
            <mask id="inner" mask-type="alpha"><rect width="1000" height="1000"/></mask>
            <mask id="bad" mask-type="alpha" maskUnits="invalid">
                <rect width="1000" height="1000" mask="url(#inner)"/></mask></defs>
            <rect width="20" height="20" mask="url(#bad)"/></svg>""", 20.0, 20.0, SvgOptions(maxMaskPixels = 1000))
        assertTrue(view.diagnostics.any { it.message.contains("coordinate units") })
        assertFalse(view.diagnostics.any { it.message.contains("maxMaskPixels") })
        assertEquals(0.0, snapshot(view).pixelReader.getColor(5, 5).opacity)
    }

    @Test fun allMaskInstancesShareTheViewBudgetAndCanRecoverAfterScalingDown() = FxTest.run {
        val view = view("""<svg viewBox="0 0 20 10"><defs>
            <mask id="m" mask-type="alpha" maskContentUnits="objectBoundingBox"><rect width="1" height="1"/></mask></defs>
            <rect width="10" height="10" fill="red" mask="url(#m)"/>
            <rect x="10" width="10" height="10" fill="blue" mask="url(#m)"/></svg>""",
            20.0, 10.0, SvgOptions(maxMaskPixels = 150))
        assertEquals(1.0, snapshot(view).pixelReader.getColor(5, 5).opacity, .02)
        assertEquals(0.0, snapshot(view).pixelReader.getColor(15, 5).opacity)
        view.resize(10.0, 5.0); view.layout()
        val pixel = snapshot(view).pixelReader.getColor(7, 2)
        assertEquals(1.0, pixel.opacity, .02)
        assertEquals(1.0, pixel.blue, .02)
    }

    @Test fun maskExpansionHonorsElementLimits() = FxTest.run {
        val instances = (0 until 12).joinToString("") { """<use href="#tile" mask="url(#m)"/>""" }
        assertFailsWith<SvgException> {
            SvgView.parse("""<svg><defs><rect id="tile" width="10" height="10"/>
                <mask id="m" mask-type="alpha"><rect width="10" height="10"/></mask></defs>$instances</svg>""",
                SvgOptions(strict = true, maxElements = 25))
        }
    }

    private fun view(xml: String, width: Double, height: Double, options: SvgOptions = SvgOptions(strict = true)): SvgView =
        SvgView.parse(xml, options).also {
            it.isManaged = false
            Scene(Group(it))
            it.resize(width, height)
            it.applyCss(); it.layout()
        }
    private fun snapshot(view: SvgView): WritableImage =
        view.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
}
