package club.xiaojiawei.svg

import javafx.animation.Animation
import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgAnimationsTest {
    private fun view(content: String, strict: Boolean = true): SvgView = SvgView(SvgLoader.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">$content</svg>""",
        SvgOptions(strict = strict)
    )).apply {
        animationAutoPlay = false
        fitWidth = 100.0; fitHeight = 100.0
        setMinSize(100.0, 100.0); setMaxSize(100.0, 100.0)
        Scene(Group(this))
        resize(100.0, 100.0)
    }
    private fun shape(v: SvgView, id: String = "p") = v.lookup("#$id") as Shape
    private fun frame(v: SvgView, seconds: Double) {
        v.seekAnimations(Duration.seconds(seconds))
        v.applyCss(); v.layout()
    }
    private fun snapshot(v: SvgView) = v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    private fun assertColour(expected: Color, actual: javafx.scene.paint.Paint?) {
        assertIs<Color>(actual)
        assertEquals(expected.red, actual.red, 1e-6)
        assertEquals(expected.green, actual.green, 1e-6)
        assertEquals(expected.blue, actual.blue, 1e-6)
        assertEquals(expected.opacity, actual.opacity, 1e-6)
    }

    @Test fun colourAnimationUpdatesNativeShapeAndPixelsWithoutReplacingNodes() = FxTest.run {
        val v = view("""<rect id="p" x="10" y="10" width="80" height="80" fill="red">
            <animate attributeName="fill" to="blue" dur="2s" fill="freeze"/>
            </rect>""")
        val p = shape(v)
        frame(v, 1.0)
        assertSame(p, shape(v))
        val expected = Color.RED.interpolate(Color.BLUE, .5)
        assertColour(expected, p.fill)
        val pixel = snapshot(v).pixelReader.getColor(50, 50)
        assertEquals(expected.red, pixel.red, .005); assertEquals(expected.blue, pixel.blue, .005)
        frame(v, 2.0); assertColour(Color.BLUE, p.fill)
        frame(v, .5); assertColour(Color.RED.interpolate(Color.BLUE, .25), p.fill)
        assertTrue(v.hasAnimations); assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun delayedOpacityAndRemoveRestoreSourceAfterCompletion() = FxTest.run {
        val v = view("""<rect id="p" width="100" height="100" opacity=".7">
            <animate attributeName="opacity" values=".2;1" begin="1s" dur="2s"/>
            </rect>""")
        frame(v, .5); assertEquals(.7, shape(v).opacity)
        frame(v, 2.0); assertEquals(.6, shape(v).opacity, 1e-8)
        frame(v, 3.0); assertEquals(.7, shape(v).opacity)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
    }

    @Test fun groupAnimationsInheritOnlyThroughTheSvgCascade() = FxTest.run {
        val v = view("""<style>#fixed { fill: green; }</style>
            <g fill="red"><animate attributeName="fill" values="red;blue" dur="2s"/>
                <rect id="p" width="20" height="20"/>
                <rect id="fixed" x="25" width="20" height="20"/>
                <g fill="yellow"><rect id="nested" x="50" width="20" height="20"/></g>
                <rect id="inherit" style="fill: inherit" y="30" width="20" height="20"/>
            </g>""")
        frame(v, 1.0)
        val expected = Color.RED.interpolate(Color.BLUE, .5)
        assertColour(expected, shape(v).fill)
        assertColour(expected, shape(v, "inherit").fill)
        assertColour(Color.GREEN, shape(v, "fixed").fill)
        assertColour(Color.YELLOW, shape(v, "nested").fill)
    }

    @Test fun currentColorAndOpacityAnimationsPropagateIntoTextSpans() = FxTest.run {
        val v = view("""<text x="5" y="50" fill="currentColor" color="red">
            <animate attributeName="color" values="red;blue" dur="2s"/>
            A<tspan id="span" opacity=".3">B<animate attributeName="opacity" values="0;1" dur="2s"/></tspan>
            <tspan fill="green">C</tspan></text>""")
        frame(v, 1.0)
        val runs = v.lookupAll(".svg-text-run").filterIsInstance<javafx.scene.text.Text>().filter { it.text.isNotBlank() }
        assertEquals(3, runs.size)
        assertEquals(2, runs.count { it.fill == Color.RED.interpolate(Color.BLUE, .5) })
        assertEquals(.5, v.lookup("#span").opacity, 1e-8)
        assertTrue(runs.any { it.fill == Color.GREEN })
    }

    @Test fun applicationCssAndBindingsWinOverAnimatedSvgValues() = FxTest.run {
        val v = view("""<path id="p" d="M10 50H90" stroke="red" stroke-width="2">
            <animate attributeName="stroke" values="red;blue" dur="2s"/>
            <animate attributeName="stroke-width" values="2;10" dur="2s"/>
            <animate attributeName="stroke-dashoffset" values="0;20" dur="2s"/>
            </path>""")
        shape(v).style = "-fx-stroke: green; -fx-stroke-width: 3; -fx-stroke-dash-offset: 4;"
        frame(v, 1.0)
        assertColour(Color.GREEN, shape(v).stroke); assertEquals(3.0, shape(v).strokeWidth)
        assertEquals(4.0, shape(v).strokeDashOffset)
        val width = SimpleDoubleProperty(7.0)
        shape(v).strokeWidthProperty().bind(width)
        frame(v, 1.5); assertEquals(7.0, shape(v).strokeWidth)
        width.set(9.0); frame(v, 1.7); assertEquals(9.0, shape(v).strokeWidth)
        shape(v).strokeWidthProperty().unbind()
        shape(v).style = ""
        frame(v, .5)
        assertEquals(4.0, shape(v).strokeWidth, 1e-8)
    }

    @Test fun rootThemesCanBeRemovedToRevealTheCurrentAnimationFrame() = FxTest.run {
        val v = view("""<rect id="p" width="100" height="100" fill="red" stroke="black">
            <animate attributeName="fill" values="red;blue" dur="2s"/>
            <animate attributeName="stroke-width" values="2;10" dur="2s"/></rect>""")
        v.svgFill = Color.ORANGE; v.svgStrokeWidth = 3.0
        frame(v, 1.0); assertColour(Color.ORANGE, shape(v).fill); assertEquals(3.0, shape(v).strokeWidth)
        v.svgFill = null; v.svgStrokeWidth = -1.0
        frame(v, 1.5)
        assertColour(Color.RED.interpolate(Color.BLUE, .75), shape(v).fill)
        assertEquals(8.0, shape(v).strokeWidth, 1e-8)
    }

    @Test fun transformsKeepTheirAffineAndRestoreStaticTransforms() = FxTest.run {
        val v = view("""<g id="group" transform="translate(5 7)">
            <rect id="p" x="50" y="40" width="10" height="20"/>
            <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="180 50 50" dur="2s"/>
            </g>""")
        val group = v.lookup("#group")
        val transform = group.transforms.single() as Affine
        frame(v, 1.0)
        assertSame(transform, group.transforms.single())
        val point = group.localToParent(60.0, 50.0)
        assertEquals(50.0, point.x, 1e-6); assertEquals(60.0, point.y, 1e-6)
        frame(v, 2.0)
        assertEquals(5.0, transform.tx); assertEquals(7.0, transform.ty)
    }

    @Test fun additiveTransformAnimationsComposeWithTheSourceAndEachOther() = FxTest.run {
        val v = view("""<rect id="p" width="20" height="20" transform="translate(5 7)">
            <animateTransform attributeName="transform" type="translate" from="0" to="20 40" additive="sum" dur="2s"/>
            <animateTransform attributeName="transform" type="scale" from="1" to="3" additive="sum" dur="2s"/>
            </rect>""")
        frame(v, 1.0)
        val transform = shape(v).transforms.single()
        assertEquals(15.0, transform.tx); assertEquals(27.0, transform.ty)
        assertEquals(2.0, transform.mxx); assertEquals(2.0, transform.myy)
    }

    @Test fun laterAnimationsTemporarilyOverrideEarlierActiveOnes() = FxTest.run {
        val v = view("""<path id="p" d="M0 0H100" stroke-width="1">
            <animate attributeName="stroke-width" values="2;10" dur="4s"/>
            <animate attributeName="stroke-width" values="20;30" begin="1s" dur="1s"/>
            </path>""")
        frame(v, .5); assertEquals(3.0, shape(v).strokeWidth, 1e-8)
        frame(v, 1.5); assertEquals(25.0, shape(v).strokeWidth, 1e-8)
        frame(v, 2.5); assertEquals(7.0, shape(v).strokeWidth, 1e-8)
    }

    @Test fun useInstancesAndSharedDocumentsKeepIndependentClocks() = FxTest.run {
        val first = view("""<defs><g id="item"><rect id="p" width="20" height="20">
            <animate attributeName="fill" values="red;blue" dur="2s"/></rect></g></defs>
            <use href="#item"/><use href="#item" x="30"/>""")
        val second = SvgView(first.document).apply { animationAutoPlay = false; resize(100.0, 100.0); Scene(Group(this)) }
        frame(first, 1.0); frame(second, .5)
        assertEquals(2, first.lookupAll("#p").size)
        first.lookupAll("#p").forEach { assertColour(Color.RED.interpolate(Color.BLUE, .5), (it as Shape).fill) }
        second.lookupAll("#p").forEach { assertColour(Color.RED.interpolate(Color.BLUE, .25), (it as Shape).fill) }
    }

    @Test fun localHrefTargetsAreResolvedEvenWhenAnimationIsElsewhere() = FxTest.run {
        val v = view("""<rect id="p" width="100" height="100" fill="red"/>
            <animate href="#p" attributeName="fill" values="red;blue" dur="2s"/>""")
        frame(v, 1.0); assertColour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
    }

    @Test fun percentageWidthsRecomputeAfterViewportResizeAndFramesDoNotGrowLengthCache() = FxTest.run {
        val v = view("""<path id="p" d="M0 50H100" stroke="red">
            <animate attributeName="stroke-width" values="1%;5%" dur="2s"/></path>""")
        frame(v, 1.0); assertEquals(3.0, shape(v).strokeWidth, 1e-8)
        val cacheField = SvgLengthCache::class.java.getDeclaredField("values").apply { isAccessible = true }
        val cache = cacheField.get(checkNotNull(v.document).lengths) as Map<*, *>
        val count = cache.size
        repeat(500) { frame(v, it / 500.0) }
        assertEquals(count, cache.size)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun animatedFixedStrokesAndMarkersKeepTheirSharedContextPaint() = FxTest.run {
        val v = view("""<defs><marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5"
            markerWidth="4" markerHeight="4" orient="auto"><path d="M0 0L10 5L0 10Z" fill="context-stroke"/></marker></defs>
            <path id="p" d="M10 50H80" fill="none" stroke="red" stroke-width="2"
                vector-effect="non-scaling-stroke" marker-end="url(#arrow)">
                <animate attributeName="stroke" values="red;blue" dur="2s"/>
                <animate attributeName="stroke-width" values="2;6" dur="2s"/>
                <animate attributeName="stroke-dashoffset" values="0;10" dur="2s"/>
                <animateTransform attributeName="transform" type="translate" from="0" to="10" dur="2s"/>
            </path>""")
        frame(v, 1.0)
        val expected = Color.RED.interpolate(Color.BLUE, .5)
        assertColour(expected, shape(v).stroke); assertEquals(4.0, shape(v).strokeWidth)
        assertEquals(5.0, shape(v).strokeDashOffset)
        assertColour(expected, (v.lookup(".svg-marker .svg-shape") as Shape).fill)
        assertTrue(snapshot(v).pixelReader.getColor(80, 50).opacity > .5)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun textLengthAndMasksFollowAnimatedOpacityAndFill() = FxTest.run {
        val v = view("""<defs><mask id="mask" mask-type="alpha"><rect width="100" height="100" fill="white">
            <animate attributeName="opacity" values="0;1" dur="2s"/></rect></mask></defs>
            <rect id="p" width="100" height="100" fill="red" mask="url(#mask)"/>
            <text x="10" y="60" textLength="70" fill="blue">ABC
            <animate attributeName="fill" values="blue;green" dur="2s"/></text>""")
        frame(v, 1.0)
        assertEquals(.5, snapshot(v).pixelReader.getColor(5, 5).opacity, .015)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun invalidAnimationsReportDiagnosticsOrRejectTheReplacement() = FxTest.run {
        for (bad in listOf(
            """<animate attributeName="d" values="M0 0;M1 1" dur="1s"/>""",
            """<animate attributeName="opacity" values="0;1" dur="1s" begin="click"/>""",
            """<animate attributeName="fill" values="red;none" dur="1s"/>""",
            """<animate href="https://example.com/a.svg#p" attributeName="opacity" to="1" dur="1s"/>"""
        )) {
            val loose = view("""<rect id="p" width="100" height="100">$bad</rect>""", false)
            assertFalse(loose.hasAnimations)
            assertTrue(loose.diagnostics.isNotEmpty())
            assertFailsWith<SvgException> { view("""<rect width="10" height="10">$bad</rect>""") }
        }
        val v = view("""<rect id="p" width="100" height="100"><animate attributeName="opacity" values="0;1" dur="2s"/></rect>""")
        frame(v, 1.0)
        val original = v.document; val node = shape(v)
        assertFailsWith<SvgException> { v.document = SvgLoader.parse("""<svg><path><animate attributeName="d" to="M0 0" dur="1s"/></path></svg>""", SvgOptions(strict = true)) }
        assertSame(original, v.document); assertSame(node, shape(v)); assertEquals(1.0, v.animationTime.toSeconds())
    }

    @Test fun replacingOrClearingDocumentsResetsPlaybackAndControlsValidateTime() = FxTest.run {
        val v = view("""<rect id="p" width="100" height="100"><animate attributeName="opacity" values="0;1" dur="2s" repeatCount="indefinite"/></rect>""")
        assertTrue(v.animationDuration.isIndefinite)
        frame(v, .5); v.playAnimations()
        assertEquals(Animation.Status.PAUSED, v.animationStatus)
        v.pauseAnimations(); frame(v, 1.0)
        assertEquals(.5, shape(v).opacity)
        v.stopAnimations()
        assertEquals(Duration.ZERO, v.animationTime); assertEquals(Animation.Status.STOPPED, v.animationStatus)
        for (invalid in listOf(Duration.UNKNOWN, Duration.INDEFINITE, Duration.millis(-1.0))) assertFailsWith<IllegalArgumentException> { v.seekAnimations(invalid) }
        v.document = SvgLoader.parse("""<svg><rect width="10" height="10"/></svg>""")
        assertFalse(v.hasAnimations); assertEquals(Duration.ZERO, v.animationTime)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
        v.source = null; v.playAnimations(); assertEquals(Animation.Status.STOPPED, v.animationStatus)
    }

    @Test fun visibleWindowsDriveTheClockAndHidingOrDetachingPausesIt() {
        lateinit var v: SvgView
        lateinit var stage: Stage
        val advanced = CountDownLatch(1)
        FxTest.run {
            v = view("""<rect id="p" width="100" height="100"><animate attributeName="opacity" values="0;1" dur="1s" repeatCount="indefinite"/></rect>""")
            v.animationAutoPlay = true
            v.animationTimeProperty().addListener { _, _, time -> if (time.toMillis() >= 50) advanced.countDown() }
            stage = Stage().apply { opacity = 0.0; scene = v.scene; show() }
            assertEquals(Animation.Status.RUNNING, v.animationStatus)
        }
        try {
            assertTrue(advanced.await(5, TimeUnit.SECONDS), "Animation pulse clock did not advance")
            FxTest.run {
                stage.hide()
                assertEquals(Animation.Status.PAUSED, v.animationStatus)
                val time = v.animationTime
                stage.show(); assertEquals(Animation.Status.RUNNING, v.animationStatus)
                assertEquals(time, v.animationTime)
                (v.parent as Group).isVisible = false
                assertEquals(Animation.Status.PAUSED, v.animationStatus)
                (v.parent as Group).isVisible = true
                assertEquals(Animation.Status.RUNNING, v.animationStatus)
                (v.parent as Group).children.clear()
                assertEquals(Animation.Status.PAUSED, v.animationStatus)
                v.stopAnimations()
            }
        } finally { FxTest.run { stage.close() } }
    }

    @Test fun opacityChannelsCombineAndSvgCurrentColorValuesResolve() = FxTest.run {
        val v = view("""<rect id="p" width="100" height="100" fill="red" stroke="blue" color="green">
            <animate attributeName="fill" values="currentColor;white" dur="2s"/>
            <animate attributeName="fill-opacity" values="0;1" dur="2s"/>
            <animate attributeName="stroke-opacity" values="1;0" dur="2s"/></rect>""")
        frame(v, 1.0)
        val expected = Color.GREEN.interpolate(Color.WHITE, .5)
        assertColour(Color(expected.red, expected.green, expected.blue, .5), shape(v).fill)
        assertColour(Color(0.0, 0.0, 1.0, .5), shape(v).stroke)
        val resized = SvgView(SvgLoader.parse("""<svg width="100" height="100"><path id="p" d="M0 50H100" stroke="red">
            <animate attributeName="stroke-width" values="1%;5%" dur="2s"/></path></svg>""")).apply {
            animationAutoPlay = false
            Scene(Group(this)); resize(100.0, 100.0)
        }
        frame(resized, 1.0); assertEquals(3.0, shape(resized).strokeWidth, 1e-8)
        resized.resize(200.0, 200.0)
        frame(resized, 1.0); assertEquals(6.0, shape(resized).strokeWidth, 1e-8)
    }

    @Test fun fxmlCanDisableAutomaticPlayback() = FxTest.run {
        val xml = """<?import club.xiaojiawei.svg.SvgView?>
            <SvgView xmlns:fx="http://javafx.com/fxml/1" animationAutoPlay="false" fitWidth="24" fitHeight="24"/>"""
        val v = javafx.fxml.FXMLLoader().load<SvgView>(java.io.ByteArrayInputStream(xml.toByteArray()))
        assertFalse(v.animationAutoPlay)
        v.document = SvgLoader.parse("""<svg><circle r="10"><animate attributeName="opacity" values="0;1" dur="1s"/></circle></svg>""")
        assertTrue(v.hasAnimations)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
    }

    @Test fun animationObserversCanReplaceTheDocumentWithoutRestoringStaleState() = FxTest.run {
        val v = view("""<rect width="100" height="100"><animate attributeName="opacity" values="0;1" dur="2s"/></rect>""")
        val replacement = SvgLoader.parse("""<svg><circle id="next" r="10"/></svg>""")
        v.animationTimeProperty().addListener { _, _, time -> if (time.toSeconds() >= 1.0) v.document = replacement }
        frame(v, 1.0)
        assertSame(replacement, v.document)
        assertFalse(v.hasAnimations)
        assertNotNull(v.lookup("#next"))
        assertEquals(Duration.ZERO, v.animationTime)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
    }
}
