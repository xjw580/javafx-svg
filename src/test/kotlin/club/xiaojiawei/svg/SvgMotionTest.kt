package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.shape.Shape
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgMotionTest {
    private fun view(motion: String, extra: String = "", attrs: String = "") = SvgView.parse(
        """<svg viewBox="0 0 100 100">$extra<rect id="r" width="10" height="4" $attrs>$motion</rect></svg>""",
        SvgOptions(strict = true)).apply {
        isManaged = false; Scene(Group(this)); resize(100.0,100.0); applyCss(); layout(); pauseAnimations()
    }
    private fun at(view: SvgView, time: Double): javafx.geometry.Point2D {
        view.seekAnimations(javafx.util.Duration.seconds(time)); view.layout()
        return view.lookup("#r").localToScene(0.0,0.0)
    }

    @Test fun defaultPacedMotionAndUnderlyingTransform() = FxTest.run {
        val v = view("""<animateMotion path="M0 0H10V30" dur="4s" fill="freeze"/>""", attrs = """transform="translate(5 7)"""")
        assertTrue(v.hasAnimations)
        assertEquals(15.0, at(v,1.0).x, .001)
        assertEquals(17.0, at(v,2.0).y, .001)
        assertEquals(37.0, at(v,4.0).y, .001)
        assertEquals(5.0, at(v,0.0).x, .001)
    }
    @Test fun referencedPathPrecedenceAndOrientation() = FxTest.run {
        val v = view("""<animateMotion path="M0 0H100" dur="2s" rotate="auto"><mpath href="#p"/></animateMotion>""",
            """<defs><path id="p" d="M10 10V90"/></defs>""")
        assertEquals(10.0, at(v,1.0).x,.001)
        assertEquals(50.0, at(v,1.0).y,.001)
        val r = v.lookup("#r")
        val p = r.localToScene(1.0,0.0)
        assertEquals(10.0,p.x,.001); assertEquals(51.0,p.y,.001)
    }
    @Test fun keyPointsReverseAndIndependentViews() = FxTest.run {
        val a = view("""<animateMotion path="M10 20H90" keyPoints="1;0" keyTimes="0;1" calcMode="linear" dur="2s" rotate="auto-reverse"/>""")
        assertEquals(70.0,at(a,.5).x,.001)
        val b = SvgView(a.document).apply { isManaged=false; Scene(Group(this)); resize(100.0,100.0); applyCss(); layout(); pauseAnimations() }
        assertEquals(30.0,at(b,1.5).x,.001)
        assertEquals(70.0,at(a,.5).x,.001)
        assertEquals(69.0,a.lookup("#r").localToScene(1.0,0.0).x,.001)
    }
    @Test fun delayFreezeReplaceSumAndTransformAnimation() = FxTest.run {
        val v = view("""<animateTransform attributeName="transform" type="scale" values="2;2" dur="10s"/>
            <animateMotion path="M0 0H20" dur="2s" fill="freeze"/>
            <animateMotion path="M0 0V10" begin="1s" dur="2s" additive="sum" fill="freeze"/>""")
        assertEquals(20.0, at(v,1.0).x,.001)
        assertEquals(40.0, at(v,2.0).x,.001)
        assertEquals(10.0, at(v,2.0).y,.001)
        assertEquals(20.0, at(v,3.0).y,.001)
    }
    @Test fun curveMeasurementAndLimits() = FxTest.run {
        val arc = SvgPathMeasure("M10 0A10 10 0 0 1 0 10", 1000)
        assertEquals(Math.PI*5,arc.length,.02)
        val p=arc.at(arc.length/2)
        assertEquals(7.071,p.x,.02); assertEquals(7.071,p.y,.02)
        val cubic=SvgPathMeasure("M0 0C0 10 10 10 10 0",1000)
        assertEquals(20.0,cubic.length,.05)
        val split=SvgPathMeasure("M0 0L10 0M50 0L60 0",1000)
        assertEquals(20.0,split.length,.001)
        assertEquals(55.0,split.at(15.0).x,.001)
        val transformed=SvgPathMeasure("M0 0H10",1000,"translate(4 8) scale(2)")
        assertEquals(24.0,transformed.at(20.0).x,.001)
        assertFailsWith<IllegalArgumentException> { SvgPathMeasure("M0 0C0 10 10 10 10 0",2) }
    }
    @Test fun invalidMotionFailsWithElementDiagnostic() = FxTest.run {
        for (motion in listOf(
            """<animateMotion path="M0 0H20" dur="2s" rotate="wrong"/>""",
            """<animateMotion dur="2s"><mpath href="https://example.com/p.svg#p"/></animateMotion>""",
            """<animateMotion path="M0 0H20" dur="2s" accumulate="sum"/>""")) {
            assertFailsWith<SvgException> { view(motion) }
        }
    }
}
