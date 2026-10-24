package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.shape.Rectangle
import javafx.util.Duration
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgGradientReuseTest {
    @Test fun reusedDefinitionKeepsBoundsViewportOpacityAndInstancesIndependent() = FxTest.run {
        val document = SvgLoader.parse("""<svg><defs color="red">
            <linearGradient id="base"><stop stop-color="currentColor" stop-opacity=".5"/><stop offset="1" stop-color="blue"/></linearGradient>
            <linearGradient id="g" href="#base" gradientTransform="translate(.2 0)"/>
            <linearGradient id="user" href="#base" gradientUnits="userSpaceOnUse"/></defs></svg>""", SvgOptions(strict = true))
        fun paints() = SvgPaints(document, SvgStyles(document) { message, _ -> throw SvgException(message) })
        val paints = paints()
        val viewport = SvgViewport(200.0, 100.0)
        val bounds = BoundingBox(10.0, 20.0, 100.0, 50.0)
        val first = paints.gradient("g", .25, viewport, bounds) as LinearGradient
        assertEquals(30.0, first.startX, 1e-8)
        assertEquals(130.0, first.endX, 1e-8)
        assertEquals(.125, first.stops.first().color.opacity, 1e-8)
        assertEquals(1.0, first.stops.first().color.red, 1e-8)
        val second = paints.gradient("g", .75, viewport, BoundingBox(30.0, 40.0, 200.0, 60.0)) as LinearGradient
        assertEquals(70.0, second.startX, 1e-8)
        assertEquals(270.0, second.endX, 1e-8)
        assertEquals(.375, second.stops.first().color.opacity, 1e-8)
        assertEquals(.125, first.stops.first().color.opacity, 1e-8)
        val again = paints.gradient("g", .25, viewport, bounds) as LinearGradient
        assertEquals(first, again)
        val other = paints().gradient("g", .8, viewport, bounds) as LinearGradient
        assertEquals(.4, other.stops.first().color.opacity, 1e-8)
        assertEquals(first, paints.gradient("g", .25, viewport, bounds))
        assertEquals(200.0, (paints.gradient("user", 1.0, viewport, bounds) as LinearGradient).endX, 1e-8)
        viewport.width = 400.0
        assertEquals(400.0, (paints.gradient("user", 1.0, viewport, bounds) as LinearGradient).endX, 1e-8)
    }

    @Test fun inheritedAnimatedOpacityCanMoveBackwardsAcrossSharedGradientUsers() = FxTest.run {
        val view = SvgView.parse("""<svg viewBox="0 0 100 100"><defs>
            <linearGradient id="g"><stop stop-color="red" stop-opacity=".5"/><stop offset="1" stop-color="blue"/></linearGradient>
            </defs><g fill="url(#g)"><animate attributeName="fill-opacity" from="0" to="1" dur="2s" fill="freeze"/>
            <rect id="a" width="30" height="30"/><rect id="b" x="40" width="30" height="30" fill-opacity=".75"/>
            </g></svg>""", SvgOptions(strict = true))
        view.animationAutoPlay = false
        Scene(Group(view)); view.resize(100.0, 100.0); view.applyCss(); view.layout()
        for (seconds in listOf(1.0, .25, 1.5, 0.0, 2.0, .5)) {
            view.seekAnimations(Duration.seconds(seconds)); view.layout()
            val a = (view.lookup("#a") as Rectangle).fill as LinearGradient
            val b = (view.lookup("#b") as Rectangle).fill as LinearGradient
            assertEquals(seconds / 4.0, a.stops.first().color.opacity, 1e-8)
            assertEquals(.375, b.stops.first().color.opacity, 1e-8)
        }
        assertTrue(view.diagnostics.isEmpty())
    }

    @Test fun emptyAndSingleStopKeepEarlyReturnAndInvalidReferencesStillFail() = FxTest.run {
        val document = SvgLoader.parse("""<svg><defs>
            <linearGradient id="empty" gradientUnits="invalid" gradientTransform="invalid"/>
            <linearGradient id="single" gradientUnits="invalid" gradientTransform="invalid"><stop stop-color="red"/></linearGradient>
            <linearGradient id="cycle" href="#cycle"/>
            </defs></svg>""", SvgOptions(strict = true))
        val paints = SvgPaints(document, SvgStyles(document) { message, _ -> throw SvgException(message) })
        val viewport = SvgViewport(100.0, 100.0)
        val bounds = BoundingBox(0.0, 0.0, 10.0, 10.0)
        repeat(3) {
            assertEquals(Color.TRANSPARENT, paints.gradient("empty", 1.0, viewport, bounds))
            assertEquals(Color.RED, paints.gradient("single", 1.0, viewport, bounds))
            assertFailsWith<IllegalArgumentException> { paints.gradient("cycle", 1.0, viewport, bounds) }
            assertFailsWith<SvgException> { paints.gradient("missing", 1.0, viewport, bounds) }
        }
    }
}
