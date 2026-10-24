package club.xiaojiawei.svg

import javafx.animation.Animation
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class SvgTransformFastPathTest {
    private val smil = """<g id="spin"><rect id="p" x="45" y="20" width="10" height="20" fill="red"/>
        <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="2s" repeatCount="indefinite"/></g>"""
    private val css = """<style>@keyframes k {from {transform:rotate(0deg)} to {transform:rotate(360deg)}}
        #spin {animation:k 2s linear infinite; transform-origin:50px 50px}</style>
        <g id="spin"><rect id="p" x="45" y="20" width="10" height="20" fill="red"/></g>"""
    private fun document(body: String, extra: String = "") = SvgLoader.parse(
        """<svg viewBox="0 0 100 100" $extra>$body</svg>""", SvgOptions(strict = true))
    private fun view(doc: SvgDocument) = SvgView(doc).apply {
        animationAutoPlay = false; isManaged = false
        Scene(Group(this)); resize(100.0, 100.0); applyCss(); layout()
        assertFalse(isNeedsLayout)
    }
    private fun affine(v: SvgView) = v.lookup("#spin").transforms.first() as Affine
    private fun pixels(v: SvgView): IntArray {
        val image = v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
        return IntArray(image.width.toInt() * image.height.toInt()).also {
            image.pixelReader.getPixels(0, 0, image.width.toInt(), image.height.toInt(),
                PixelFormat.getIntArgbInstance(), it, 0, image.width.toInt())
        }
    }

    @Test fun smilAndCssSeekBackwardsAndAcrossLoopsWithoutRequestingViewLayout() = FxTest.run {
        for (body in listOf(smil, css)) {
            val v = view(document(body))
            val matrix = affine(v)
            for (time in listOf(.5, 1.75, 0.0, 2.5, .25, 100.5)) {
                v.seekAnimations(Duration.seconds(time))
                assertFalse(v.isNeedsLayout, "A pure transform requested SvgView layout at $time")
                assertSame(matrix, affine(v))
                val angle = Math.toRadians(180 * (time % 2))
                val point = matrix.transform(50.0, 30.0)
                assertEquals(50 + 20 * sin(angle), point.x, 1e-8)
                assertEquals(50 - 20 * cos(angle), point.y, 1e-8)
            }
            assertTrue(v.diagnostics.isEmpty())
        }
    }

    @Test fun fastFramesMatchFullLayoutPixelsAndSharedDocumentsKeepIndependentMatrices() = FxTest.run {
        for (body in listOf(smil, css)) {
            val document = document(body)
            val fast = view(document)
            val full = view(document)
            for (time in listOf(.25, .5, 1.75, 2.25, 0.0)) {
                fast.seekAnimations(Duration.seconds(time))
                assertFalse(fast.isNeedsLayout)
                full.requestLayout()
                full.seekAnimations(Duration.seconds(time))
                full.layout()
                assertTrue(pixels(full).contentEquals(pixels(fast)), "Frame differs at $time")
            }
            fast.seekAnimations(Duration.seconds(.5))
            full.seekAnimations(Duration.seconds(.25))
            assertEquals(0.0, affine(fast).mxx, 1e-8)
            assertEquals(kotlin.math.sqrt(.5), affine(full).mxx, 1e-8)
            assertNotSame(affine(fast), affine(full))
        }
    }

    @Test fun cssDelayAndRemoveRestoreTheSourceMatrixAfterScratchReuse() = FxTest.run {
        val v = view(document("""<style>@keyframes k {from {transform:translate(0px,0px) scale(1)}
            to {transform:translate(40px,0px) scale(3)}} #spin {animation:k 2s linear 1s;transform-origin:0 0}</style>
            <g id="spin" transform="translate(7 9)"><rect width="10" height="10"/></g>"""))
        for (time in listOf(2.0, .5, 3.0, 1.5, 4.0)) {
            v.seekAnimations(Duration.seconds(time))
            assertFalse(v.isNeedsLayout)
            val active = time >= 1 && time < 3
            assertEquals(if (active) 1 + (time - 1) else 1.0, affine(v).mxx, 1e-8)
            assertEquals(if (active) (time - 1) * 20 else 7.0, affine(v).tx, 1e-8)
            assertEquals(if (active) 0.0 else 9.0, affine(v).ty, 1e-8)
        }
    }

    @Test fun resizeAndRatioChangesUseFullLayoutBeforeResumingFastFrames() = FxTest.run {
        val v = view(document("""<style>@keyframes k {from {transform:rotate(0deg)} to {transform:rotate(360deg)}}</style>
            <rect x="45" y="20" width="10" height="20"/>""",
            """id="spin" style="animation:k 2s linear infinite;transform-origin:center" """))
        v.seekAnimations(Duration.seconds(.25))
        assertFalse(v.isNeedsLayout)
        v.resize(200.0, 200.0)
        assertTrue(v.isNeedsLayout)
        v.seekAnimations(Duration.seconds(.5))
        assertTrue(v.isNeedsLayout)
        v.layout()
        var point = affine(v).transform(100.0, 100.0)
        assertEquals(100.0, point.x, 1e-8); assertEquals(100.0, point.y, 1e-8)
        v.seekAnimations(Duration.seconds(.75))
        assertFalse(v.isNeedsLayout)
        v.preserveRatio = false
        v.seekAnimations(Duration.seconds(1.0))
        assertTrue(v.isNeedsLayout)
        v.layout()
        point = affine(v).transform(100.0, 100.0)
        assertEquals(100.0, point.x, 1e-8); assertEquals(100.0, point.y, 1e-8)
    }

    @Test fun themeCssAndBindingsStillOverridePaintDuringFastPlayback() = FxTest.run {
        val v = view(document(smil))
        val shape = v.lookup("#p") as Shape
        v.seekAnimations(Duration.seconds(.5))
        v.svgFill = Color.BLUE
        v.seekAnimations(Duration.seconds(.75)); v.applyCss(); v.layout()
        assertEquals(Color.BLUE, shape.fill)
        shape.style = "-fx-fill: green;"
        v.seekAnimations(Duration.seconds(1.0)); v.applyCss(); v.layout()
        assertEquals(Color.GREEN, shape.fill)
        val bound = SimpleObjectProperty<Paint>(Color.YELLOW)
        shape.fillProperty().bind(bound)
        v.seekAnimations(Duration.seconds(1.25))
        assertEquals(Color.YELLOW, shape.fill)
        shape.fillProperty().unbind()
        shape.style = ""; v.svgFill = null
        v.seekAnimations(Duration.seconds(1.5)); v.applyCss(); v.layout()
        assertEquals(Color.RED, shape.fill)
        assertEquals(1.5, v.animationTime.toSeconds())
    }

    @Test fun effectsTextMixedTracksAndFillBoxKeepTheFullFramePipeline() = FxTest.run {
        val effects = listOf(
            """<rect width="20" height="20" stroke="red" vector-effect="non-scaling-stroke"/>""",
            """<defs><marker id="m" markerWidth="4" markerHeight="4" refY="2"><path d="M0 0L4 2L0 4Z"/></marker></defs>
                <path d="M10 50H70" stroke="red" marker-end="url(#m)"/>""",
            """<defs><filter id="f" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="1"/></filter></defs>
                <rect width="20" height="20" fill="red" filter="url(#f)"/>""",
            """<defs><mask id="m" mask-type="alpha"><rect width="100" height="100" fill="white"/></mask></defs>
                <rect width="20" height="20" mask="url(#m)"/>""",
            """<defs><clipPath id="c" clipPathUnits="objectBoundingBox"><rect width="1" height="1"/></clipPath></defs>
                <rect width="20" height="20" clip-path="url(#c)"/>""",
            """<text x="10" y="50" text-anchor="middle">ABC</text>""",
            """<circle r="10"><animate attributeName="r" from="10" to="20" dur="2s"/></circle>""",
            """<style>@keyframes f {from {opacity:.2} to {opacity:1}} #spin {animation:f 2s linear}</style><rect width="20" height="20"/>""",
            """<style>#spin {transform-origin:center;transform-box:fill-box}</style><rect width="20" height="20"/>"""
        )
        for (body in effects) {
            val renderer = FxSvgRenderer(document("""<g id="spin">$body
                <animateTransform attributeName="transform" type="rotate" from="0 50 50" to="360 50 50" dur="2s" repeatCount="indefinite"/></g>"""))
            renderer.layout(100.0, 100.0, true)
            assertFalse(renderer.seekAnimations(.5, updateTransforms = true), body)
            renderer.layout(100.0, 100.0, true)
            val transform = renderer.root.lookup("#spin").transforms.first() as Affine
            assertEquals(0.0, transform.mxx, 1e-8)
            assertTrue(renderer.diagnostics.isEmpty())
        }
    }

    @Test fun firstFrameStillRequiresLayoutAndStaticUserSpaceClipCanUseFastFrames() = FxTest.run {
        val renderer = FxSvgRenderer(document("""<defs><clipPath id="c"><rect width="100" height="100"/></clipPath></defs>
            <g id="spin" clip-path="url(#c)"><rect width="10" height="10"/>
            <animateTransform attributeName="transform" type="translate" from="0 0" to="20 0" dur="2s"/></g>"""))
        assertFalse(renderer.seekAnimations(.5, updateTransforms = true))
        renderer.layout(100.0, 100.0, true)
        assertTrue(renderer.seekAnimations(1.0, updateTransforms = true))
        assertEquals(10.0, (renderer.root.lookup("#spin").transforms.first() as Affine).tx, 1e-8)
    }

    @Test fun timeObserversCanResizeOrReplaceTheDocumentDuringFastSeek() = FxTest.run {
        val v = view(document(smil))
        val next = document("""<rect id="replacement" width="20" height="30"/>""")
        v.animationTimeProperty().addListener { _, _, time ->
            if (time.toSeconds() == .5) v.resize(200.0, 200.0)
            if (time.toSeconds() == 1.0) v.document = next
        }
        v.seekAnimations(Duration.seconds(.5))
        assertTrue(v.isNeedsLayout)
        v.layout()
        v.seekAnimations(Duration.seconds(1.0))
        assertSame(next, v.document)
        assertEquals(Duration.ZERO, v.animationTime)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
        assertNotNull(v.lookup("#replacement"))
    }

    @Test fun realPulseClockAdvancesPureTransformsWithoutInvalidatingTheView() {
        lateinit var v: SvgView
        lateinit var stage: Stage
        val advanced = CountDownLatch(1)
        val layouts = AtomicInteger()
        FxTest.run {
            v = view(document(smil))
            var tracking = false
            v.needsLayoutProperty().addListener { _, _, needed -> if (tracking && needed) layouts.incrementAndGet() }
            v.animationTimeProperty().addListener { _, _, time ->
                if (time.toMillis() >= 100) tracking = true
                if (time.toMillis() >= 300) advanced.countDown()
            }
            v.animationAutoPlay = true
            stage = Stage().apply { opacity = 0.0; scene = v.scene; show() }
        }
        try {
            assertTrue(advanced.await(5, TimeUnit.SECONDS))
            FxTest.run {
                assertEquals(0, layouts.get(), "Pure transform pulses requested SvgView layout")
                assertEquals(Animation.Status.RUNNING, v.animationStatus)
            }
        } finally { FxTest.run { v.stopAnimations(); stage.close() } }
    }

    @Test fun matrixObserversCannotRestoreStaleTimeAfterReplacingTheDocumentOrSeeking() = FxTest.run {
        val v = view(document(smil))
        val next = document("""<rect id="replacement" width="20" height="30"/>""")
        var replaced = false
        affine(v).mxxProperty().addListener { _, _, _ ->
            if (!replaced) { replaced = true; v.document = next }
        }
        v.seekAnimations(Duration.seconds(.5))
        assertSame(next, v.document)
        assertEquals(Duration.ZERO, v.animationTime)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)

        val other = view(document(smil))
        var sought = false
        affine(other).mxxProperty().addListener { _, _, _ ->
            if (!sought) { sought = true; other.seekAnimations(Duration.seconds(.25)) }
        }
        other.seekAnimations(Duration.seconds(.5))
        assertEquals(.25, other.animationTime.toSeconds())
        assertEquals(kotlin.math.sqrt(.5), affine(other).mxx, 1e-8)
    }

    @Test fun failedFullLayoutDisablesFastFramesUntilLayoutCompletes() = FxTest.run {
        val renderer = FxSvgRenderer(SvgLoader.parse("""<svg><g id="spin"><rect id="p" width="100%" height="20"/>
            <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="2s" repeatCount="indefinite"/></g></svg>""", SvgOptions(strict = true)))
        renderer.layout(100.0, 100.0, true)
        val rect = renderer.root.lookup("#p") as Rectangle
        rect.widthProperty().bind(javafx.beans.property.SimpleDoubleProperty(80.0))
        assertFails { renderer.layout(200.0, 100.0, true) }
        assertFalse(renderer.seekAnimations(.5, updateTransforms = true))
        rect.widthProperty().unbind()
        renderer.layout(200.0, 100.0, true)
        assertEquals(200.0, rect.width)
        assertTrue(renderer.seekAnimations(.75, updateTransforms = true))
    }

    @Test fun cachedKeyframePositionsStillResolveCurrentPercentagesAndUnderlyingValues() {
        fun curve(attributes: String): SvgAnimation.Curve {
            val child = SvgLoader.parse("""<svg><animate $attributes dur="2s"/></svg>""").root.children.single()
            return SvgAnimation.parse(child, 0).Curve(null, false)
        }
        val percentages = curve("""attributeName="stroke-width" values="10%;50%" """)
        assertEquals(30.0, (percentages.sample(.5, 100.0, null) as SvgAnimation.Value.Number).value)
        assertEquals(60.0, (percentages.sample(.5, 200.0, null) as SvgAnimation.Value.Number).value)
        val to = curve("""attributeName="stroke-width" to="50%" """)
        assertEquals(35.0, (to.sample(.5, 100.0, SvgAnimation.Value.Number(20.0)) as SvgAnimation.Value.Number).value)
        assertEquals(70.0, (to.sample(.5, 200.0, SvgAnimation.Value.Number(40.0)) as SvgAnimation.Value.Number).value)
        val by = curve("""attributeName="stroke-width" by="20%" """)
        assertEquals(30.0, (by.sample(.5, 100.0, SvgAnimation.Value.Number(20.0)) as SvgAnimation.Value.Number).value)
        assertEquals(60.0, (by.sample(.5, 200.0, SvgAnimation.Value.Number(40.0)) as SvgAnimation.Value.Number).value)
    }

    @Test fun pauseFromTimeOrMatrixObserversKeepsTheClockAndFrameConsistent() = FxTest.run {
        for (observeMatrix in listOf(false, true)) {
            val v = view(document(smil))
            if (observeMatrix) affine(v).mxxProperty().addListener { _, _, _ -> v.pauseAnimations() }
            else v.animationTimeProperty().addListener { _, _, _ -> v.pauseAnimations() }
            v.seekAnimations(Duration.seconds(.5))
            v.layout()
            assertEquals(.5, v.animationTime.toSeconds())
            assertEquals(0.0, affine(v).mxx, 1e-8)
            assertEquals(1.0, affine(v).myx, 1e-8)
        }
    }

    @Test fun matrixObserverCanReplayAtThePublishedFiniteEndpoint() = FxTest.run {
        val v = view(document(smil.replace("repeatCount=\"indefinite\"", "repeatCount=\"1\"")))
        v.seekAnimations(Duration.seconds(1.0))
        var replayed = false
        affine(v).mxxProperty().addListener { _, _, _ ->
            if (!replayed && v.animationTime == v.animationDuration) { replayed = true; v.playAnimations() }
        }
        v.seekAnimations(v.animationDuration)
        assertTrue(replayed)
        assertEquals(Duration.ZERO, v.animationTime)
        assertEquals(1.0, affine(v).mxx, 1e-8)
        assertEquals(Animation.Status.PAUSED, v.animationStatus)
    }
}
