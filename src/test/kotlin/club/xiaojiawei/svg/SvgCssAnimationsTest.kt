package club.xiaojiawei.svg

import javafx.animation.Animation
import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgCssAnimationsTest {
    private fun view(body: String, strict: Boolean = true, extra: String = "", box: String = "0 0 100 100"): SvgView =
        SvgView(SvgLoader.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="$box" $extra>$body</svg>""", SvgOptions(strict = strict))).apply {
            animationAutoPlay = false; isManaged = false
            Scene(Group(this)); resize(100.0, 100.0)
        }
    private fun frame(v: SvgView, seconds: Double) { v.seekAnimations(Duration.seconds(seconds)); v.applyCss(); v.layout() }
    private fun shape(v: SvgView, id: String = "p") = v.lookup("#$id") as Shape
    private fun image(v: SvgView) = v.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    private fun affine(v: SvgView, id: String = "p") = v.lookup("#$id").transforms.first() as Affine
    private fun colour(expected: Color, actual: javafx.scene.paint.Paint?) {
        assertIs<Color>(actual)
        assertEquals(expected.red, actual.red, 1e-6); assertEquals(expected.green, actual.green, 1e-6)
        assertEquals(expected.blue, actual.blue, 1e-6); assertEquals(expected.opacity, actual.opacity, 1e-6)
    }

    @Test fun cssRotationUsesTheExistingAffineAndPublicPlaybackClock() = FxTest.run {
        val v = view("""<style>@keyframes spin { from { transform: rotate(0deg) } to { transform: rotate(360deg) } }
            #p { animation: spin 2s linear infinite; transform-origin: 50px 50px; }</style>
            <rect id="p" x="45" y="20" width="10" height="20" fill="red"/>""")
        val transform = affine(v)
        frame(v, .5)
        val point = transform.transform(50.0, 30.0)
        assertEquals(70.0, point.x, 1e-6); assertEquals(50.0, point.y, 1e-6)
        assertSame(transform, affine(v)); assertTrue(v.animationDuration.isIndefinite)
        frame(v, 1.0); assertEquals(-1.0, transform.mxx, 1e-6)
        v.stopAnimations(); v.layout(); assertEquals(1.0, transform.mxx, 1e-6)
        assertEquals(Animation.Status.STOPPED, v.animationStatus)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun cssPaintAndNumericPropertiesAnimateTogether() = FxTest.run {
        val v = view("""<style>@keyframes change {
            from { fill: red; stroke: blue; opacity: .2; stroke-width: 2; stroke-dashoffset: 0; }
            to { fill: blue; stroke: red; opacity: .8; stroke-width: 10; stroke-dashoffset: 20; }
            } #p { animation: change 2s linear forwards; }</style>
            <rect id="p" x="10" y="10" width="80" height="80"/></svg>""".removeSuffix("</svg>"))
        frame(v, 1.0)
        colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
        colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).stroke)
        assertEquals(.5, shape(v).opacity, 1e-6)
        assertEquals(6.0, shape(v).strokeWidth); assertEquals(10.0, shape(v).strokeDashOffset)
        assertEquals(.5, image(v).pixelReader.getColor(50, 50).opacity, .01)
        frame(v, 2.0); colour(Color.BLUE, shape(v).fill)
    }

    @Test fun missingEndpointsAndSparsePropertiesUseUnderlyingStyles() = FxTest.run {
        val v = view("""<style>@keyframes k { 50% { fill: red; } to { fill: blue; opacity: 1; } }
            #p { animation: k 2s linear forwards; fill: black; opacity: .3; }</style>
            <rect id="p" width="100" height="100"/>""")
        frame(v, .5); colour(Color.BLACK.interpolate(Color.RED, .5), shape(v).fill)
        assertEquals(.475, shape(v).opacity, 1e-6)
        frame(v, 1.0); colour(Color.RED, shape(v).fill); assertEquals(.65, shape(v).opacity, 1e-6)
        frame(v, 1.5); colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
    }

    @Test fun duplicateOffsetsMergeAndLaterDefinitionsReplaceTheWholeAnimation() = FxTest.run {
        val v = view("""<style>@keyframes k { from { fill: black } to { fill: white } }
            @keyframes k { from { fill: red; opacity: .2 } 50% { fill: green; } 50% { opacity: .6 } to { fill: blue; opacity: 1 } }
            #p { animation: k 2s linear; }</style><rect id="p" width="100" height="100"/>""")
        frame(v, 1.0); colour(Color.GREEN, shape(v).fill); assertEquals(.6, shape(v).opacity, 1e-6)
    }

    @Test fun cssImportantAndApplicationOverridesKeepTheirPriority() = FxTest.run {
        val v = view("""<style>@keyframes k { from { fill: red; stroke-width: 2 } to { fill: blue; stroke-width: 10 } }
            #p { fill: green !important; animation: k 2s linear; }</style>
            <rect id="p" width="100" height="100" stroke="black"/>""")
        frame(v, 1.0); colour(Color.GREEN, shape(v).fill); assertEquals(6.0, shape(v).strokeWidth)
        v.svgFill = Color.ORANGE; frame(v, 1.5); colour(Color.ORANGE, shape(v).fill)
        shape(v).style = "-fx-fill: purple; -fx-stroke-width: 3"
        frame(v, 1.6); colour(Color.PURPLE, shape(v).fill); assertEquals(3.0, shape(v).strokeWidth)
        val width = SimpleDoubleProperty(7.0); shape(v).strokeWidthProperty().bind(width)
        frame(v, 1.7); assertEquals(7.0, shape(v).strokeWidth)
        shape(v).strokeWidthProperty().unbind(); shape(v).style = ""; v.svgFill = null
        frame(v, 1.0); colour(Color.GREEN, shape(v).fill); assertEquals(6.0, shape(v).strokeWidth)
    }

    @Test fun importantTransformAndStaticCssTransformOverrideTheSvgAttribute() = FxTest.run {
        val v = view("""<style>@keyframes k { to { transform: rotate(360deg) } }
            #p { transform: translateX(10px) !important; animation: k 2s linear; }</style>
            <rect id="p" width="10" height="10" transform="translate(30 40)"/>""")
        frame(v, 1.0); assertEquals(10.0, affine(v).tx); assertEquals(0.0, affine(v).ty)
        assertFalse(v.hasAnimations)
        val none = view("""<style>#p { transform: none }</style><rect id="p" width="10" height="10" transform="translate(30 40)"/>""")
        frame(none, 0.0); assertEquals(0.0, affine(none).tx)
        assertFalse(none.hasAnimations)
    }

    @Test fun laterAnimationNamesOverrideOnlyTheirContributingProperties() = FxTest.run {
        val v = view("""<style>@keyframes a { from { opacity: 0 } to { opacity: 1 } }
            @keyframes b { from { opacity: .2; fill: red } to { opacity: .6; fill: blue } }
            #p { animation: a 4s linear, b 1s linear 1s; }</style><rect id="p" width="100" height="100"/>""")
        frame(v, .5); assertEquals(.125, shape(v).opacity, 1e-6)
        frame(v, 1.5); assertEquals(.4, shape(v).opacity, 1e-6); colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
        frame(v, 2.5); assertEquals(.625, shape(v).opacity, 1e-6); colour(Color.BLACK, shape(v).fill)
    }

    @Test fun delaysReverseAlternateAndFractionalFreezeMatchTheTimeline() = FxTest.run {
        val v = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s linear 1s 2.25 alternate both; }</style><rect id="p" width="100" height="100"/>""")
        frame(v, 0.0); assertEquals(0.0, shape(v).opacity)
        frame(v, 2.0); assertEquals(.5, shape(v).opacity, 1e-6)
        frame(v, 3.5); assertEquals(.75, shape(v).opacity, 1e-6)
        frame(v, 5.5); assertEquals(.25, shape(v).opacity, 1e-6)
        val negative = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s linear -.5s infinite reverse; }</style><rect id="p" width="100" height="100"/>""")
        frame(negative, 0.0); assertEquals(.75, shape(negative).opacity, 1e-6)
    }

    @Test fun keyframeEasingAndStepsApplyPerPropertySegment() = FxTest.run {
        val v = view("""<style>@keyframes k { from { opacity: 0; animation-timing-function: ease-in } to { opacity: 1 } }
            #p { animation: k 2s linear; }</style><rect id="p" width="100" height="100"/>""")
        frame(v, 1.0); assertTrue(shape(v).opacity in .3.. .33)
        val steps = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s steps(4); }</style><rect id="p" width="100" height="100"/>""")
        frame(steps, .9); assertEquals(.25, shape(steps).opacity, 1e-6)
    }

    @Test fun groupPaintAnimationsInheritWithoutDuplicatingTheirAnimation() = FxTest.run {
        val v = view("""<style>@keyframes k { from { fill: red } to { fill: blue } }
            #group { animation: k 2s linear; }</style>
            <g id="group"><rect id="p" width="30" height="30"/><rect id="fixed" x="50" width="30" height="30" fill="green"/></g>""")
        frame(v, 1.0); colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
        colour(Color.GREEN, shape(v, "fixed").fill)
        assertEquals(2.0, v.animationDuration.toSeconds())
    }

    @Test fun fillBoxOriginsTrackAnimatedDescendantsBeforeFixedStrokeRefresh() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: scale(1) } to { transform: scale(3) } }
            #group { animation: k 2s linear; transform-origin: center; transform-box: fill-box; }</style>
            <g id="group"><rect id="p" x="20" y="40" width="20" height="20" fill="none" stroke="red" stroke-width="2" vector-effect="non-scaling-stroke">
            <animate attributeName="width" values="20;60" dur="2s"/></rect></g>""")
        frame(v, 1.0)
        val fixed = affine(v, "group").transform(40.0, 50.0)
        assertEquals(40.0, fixed.x, 1e-6); assertEquals(50.0, fixed.y, 1e-6)
        assertTrue(image(v).pixelReader.getColor(80, 50).opacity > .4)
        assertEquals(0.0, image(v).pixelReader.getColor(82, 50).opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun viewBoxOriginsIncludeNonzeroViewBoxCoordinates() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: rotate(0deg) } to { transform: rotate(180deg) } }
            #g { animation: k 2s linear; transform-origin: center; }</style>
            <g id="g"><circle id="p" cx="70" cy="70" r="5"/></g>""", box = "10 20 100 100")
        frame(v, 1.0)
        val point = shape(v).localToScene(70.0, 70.0)
        assertEquals(50.0, point.x, 1e-6); assertEquals(60.0, point.y, 1e-6)
    }

    @Test fun rootCssOriginsUseTheActualLayoutBoxWhenResized() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: rotate(0deg) } to { transform: rotate(180deg) } }</style>
            <circle id="p" cx="60" cy="70" r="5"/>""",
            extra = """style="animation: k 2s linear; transform-origin: center" """, box = "10 20 100 100")
        v.resize(200.0, 200.0); frame(v, 1.0)
        val point = shape(v).localToScene(60.0, 70.0)
        assertEquals(100.0, point.x, 1e-6); assertEquals(100.0, point.y, 1e-6)
    }

    @Test fun translatePercentagesAndNoneInterpolateWithCompatiblePrimitives() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: none } to { transform: translate(50%, 10px) scale(2) } }
            #p { animation: k 2s linear; transform-box: fill-box; transform-origin: 0 0; }</style>
            <rect id="p" x="20" y="30" width="40" height="20"/>""")
        frame(v, 1.0); val a = affine(v)
        assertEquals(1.5, a.mxx, 1e-6); assertEquals(0.0, a.tx, 1e-6); assertEquals(-10.0, a.ty, 1e-6)
    }

    @Test fun cssUseInstancesAndSharedDocumentsKeepIndependentContextsAndClocks() = FxTest.run {
        val first = view("""<style>@keyframes k { from { fill: red } to { fill: blue } }
            .a { animation: k 2s linear } .b { animation: k 4s linear }</style>
            <defs><circle id="dot" class="part" r="10"/></defs>
            <use class="a" href="#dot" x="25" y="50"/><use class="b" href="#dot" x="75" y="50"/>""")
        val second = SvgView(first.document).apply { animationAutoPlay = false; isManaged = false; Scene(Group(this)); resize(100.0, 100.0) }
        frame(first, 1.0); frame(second, .5)
        val fills = first.lookupAll("#dot").filterIsInstance<Shape>().map { it.fill }.toSet()
        assertTrue(Color.RED.interpolate(Color.BLUE, .5) in fills)
        assertTrue(Color.RED.interpolate(Color.BLUE, .25) in fills)
        assertTrue(second.lookupAll("#dot").filterIsInstance<Shape>().any { it.fill == Color.RED.interpolate(Color.BLUE, .125) })
    }

    @Test fun unsupportedAtRulesAndKeyframePropertiesKeepUsableStylesAndTracks() = FxTest.run {
        val xml = """<style>rect { stroke: green } @media screen { rect { stroke: red } }
            @keyframes k { from { fill: red; transform: rotate3d(0,0,1,0deg); font-size: 10px } to { fill: blue; transform: rotate3d(0,0,1,180deg); font-size: 20px } }
            #p { animation: k 2s linear; }</style><rect id="p" width="100" height="100"/>"""
        val v = view(xml, false); frame(v, 1.0)
        colour(Color.GREEN, shape(v).stroke); colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
        assertTrue(v.diagnostics.size >= 3)
        assertFailsWith<SvgException> { view(xml) }
    }

    @Test fun pausedAndZeroDurationCssAnimationsExposeTheirStaticResult() = FxTest.run {
        val paused = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s linear -.5s infinite paused; }</style><rect id="p" width="100" height="100"/>""")
        frame(paused, 10.0); assertEquals(.25, shape(paused).opacity, 1e-6)
        assertEquals(Duration.ZERO, paused.animationDuration)
        val instant = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 0s forwards; opacity: .3; }</style><rect id="p" width="100" height="100"/>""")
        frame(instant, 0.0); assertEquals(1.0, shape(instant).opacity, 1e-6)
    }

    @Test fun differentCssAndSmilPropertiesCombineButSamePropertyIsDiagnosed() = FxTest.run {
        val v = view("""<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s linear; }</style><circle id="p" cx="50" cy="50" r="5">
            <animate attributeName="r" values="5;25" dur="2s"/></circle>""")
        frame(v, 1.0); assertEquals(15.0, (shape(v) as Circle).radius); assertEquals(.5, shape(v).opacity, 1e-6)
        val overlap = """<style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
            #p { animation: k 2s linear; }</style><circle id="p" r="5">
            <animate attributeName="opacity" values=".4;.8" dur="2s"/></circle>"""
        val loose = view(overlap, false); frame(loose, 1.0); assertEquals(.6, shape(loose).opacity, 1e-6)
        assertTrue(loose.diagnostics.isNotEmpty()); assertFailsWith<SvgException> { view(overlap) }
    }

    @Test fun cssTransformsAndPaintsComposeWithMasksAndMarkers() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: rotate(0deg); stroke: red } to { transform: rotate(180deg); stroke: blue } }
            #p { animation: k 2s linear; transform-origin: 50px 50px; }</style>
            <defs><marker id="m" markerUnits="userSpaceOnUse" markerWidth="8" markerHeight="8" refX="0" refY="0" overflow="visible">
            <circle r="3" fill="context-stroke"/></marker><mask id="m2" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"><rect width="100" height="100" fill="white"/></mask></defs>
            <path id="p" d="M30 50H70" fill="none" stroke="red" stroke-width="2" marker-end="url(#m)" mask="url(#m2)"/>""")
        frame(v, 1.0); colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).stroke)
        assertTrue(image(v).pixelReader.getColor(50, 70).opacity > .5)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun cssFramesDoNotGrowNodeOrDocumentLengthCaches() = FxTest.run {
        val v = view("""<style>@keyframes k { from { transform: translateX(0%); stroke-dashoffset: 0; fill: red; }
            to { transform: translateX(10%); stroke-dashoffset: 10; fill: blue; } }
            #p { animation: k 2s linear infinite; transform-box: fill-box; }</style><rect id="p" x="10" y="10" width="30" height="30"/>""")
        frame(v, .5)
        val field = SvgLengthCache::class.java.getDeclaredField("values").apply { isAccessible = true }
        val cache = field.get(v.document!!.lengths) as Map<*, *>
        val size = cache.size; val nodes = v.lookupAll("*").toSet()
        repeat(500) { frame(v, it / 500.0) }
        assertEquals(size, cache.size); assertEquals(nodes, v.lookupAll("*").toSet())
    }

    @Test fun cssAnimationAutoPlaysByDefaultInAShowingWindow() {
        lateinit var v: SvgView
        lateinit var stage: Stage
        val advanced = CountDownLatch(1)
        FxTest.run {
            v = SvgView.parse("""<svg><style>@keyframes k { from { opacity: 0 } to { opacity: 1 } }
                rect { animation: k 1s linear infinite }</style><rect width="100" height="100"/></svg>""", SvgOptions(strict = true))
            assertTrue(v.animationAutoPlay)
            v.animationTimeProperty().addListener { _, _, time -> if (time.toMillis() >= 50) advanced.countDown() }
            stage = Stage().apply { opacity = 0.0; scene = Scene(Group(v), 100.0, 100.0); show() }
            assertEquals(Animation.Status.RUNNING, v.animationStatus)
        }
        try {
            assertTrue(advanced.await(5, TimeUnit.SECONDS))
            FxTest.run { stage.hide(); assertEquals(Animation.Status.PAUSED, v.animationStatus); v.source = null; assertFalse(v.hasAnimations) }
        } finally { FxTest.run { stage.close() } }
    }

    @Test fun backwardsStepFillAndUppercaseKeywordsUseTheCorrectFirstFrame() = FxTest.run {
        val v = view("""<style>@KEYFRAMES k { FROM { opacity: 0; transform: rotate(0DEG); } TO { opacity: 1; transform: rotate(180DEG); } }
            #p { animation: k 2s steps(4,start) 1s backwards; transform-origin: CENTER; }</style>
            <rect id="p" x="40" y="40" width="20" height="20"/>""")
        frame(v, 0.0); assertEquals(0.0, shape(v).opacity)
        frame(v, 1.0); assertEquals(.25, shape(v).opacity)
        assertTrue(v.diagnostics.isEmpty())
    }

    @Test fun staticFillBoxTransformsFollowApplicationGeometryBindings() = FxTest.run {
        val v = view("""<style>#p { transform: scale(2); transform-origin: center; transform-box: fill-box; }</style>
            <rect id="p" x="20" y="40" width="20" height="20"/>""")
        frame(v, 0.0)
        val width = SimpleDoubleProperty(20.0)
        (shape(v) as Rectangle).widthProperty().bind(width)
        v.layout()
        width.set(40.0)
        assertTrue(v.isNeedsLayout)
        v.layout()
        val pivot = affine(v).transform(40.0, 50.0)
        assertEquals(40.0, pivot.x, 1e-6); assertEquals(50.0, pivot.y, 1e-6)
        assertFalse(v.hasAnimations)
    }

    @Test fun cssTextSpanPaintAndMarkerViewBoxTransformsFollowTheSameClock() = FxTest.run {
        val v = view("""<style>@keyframes ink { from { fill: red; opacity: .2 } to { fill: blue; opacity: .8 } }
            @keyframes turn { from { transform: rotate(0deg) } to { transform: rotate(180deg) } }
            #span { animation: ink 2s linear; } #head { animation: turn 2s linear; transform-origin: center; }</style>
            <defs><marker id="m" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse"
                viewBox="10 20 10 10" refX="15" refY="25"><circle id="head" cx="18" cy="25" r="2" fill="red"/></marker></defs>
            <text x="10" y="25">A<tspan id="span">B</tspan></text>
            <path d="M20 50H80" stroke="red" marker-end="url(#m)"/>""")
        frame(v, 1.0)
        val run = v.lookup("#span").lookup(".svg-text-run") as Shape
        colour(Color.RED.interpolate(Color.BLUE, .5), run.fill)
        assertEquals(.5, v.lookup("#span").opacity, 1e-6)
        assertTrue(image(v).pixelReader.getColor(80, 53).opacity > .5)
        assertTrue(v.diagnostics.isEmpty(), v.diagnostics.toString())
    }

    @Test fun expandedCssPointsAreBoundedForEveryView() = FxTest.run {
        val xml = """<svg><style>@keyframes k { from { fill: red; opacity: 0 } to { fill: blue; opacity: 1 } }
            rect { animation: k 2s linear }</style><rect id="p" width="100" height="100"/></svg>"""
        val document = SvgLoader.parse(xml, SvgOptions(maxElements = 3))
        repeat(2) {
            val v = SvgView(document).apply { animationAutoPlay = false; isManaged = false; Scene(Group(this)); resize(100.0, 100.0) }
            frame(v, 1.0)
            assertTrue(v.diagnostics.any { it.message.contains("keyframe limit") })
            colour(Color.RED.interpolate(Color.BLUE, .5), shape(v).fill)
            assertEquals(1.0, shape(v).opacity)
        }
        assertFailsWith<SvgException> { SvgView(SvgLoader.parse(xml, SvgOptions(strict = true, maxElements = 3))) }
    }
}
