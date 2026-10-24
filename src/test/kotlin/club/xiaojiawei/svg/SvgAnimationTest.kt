package club.xiaojiawei.svg

import javafx.scene.paint.Color
import javafx.scene.transform.Affine
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgAnimationTest {
    private fun animation(attributes: String, transform: Boolean = false): SvgAnimation {
        val name = if (transform) "animateTransform" else "animate"
        val child = SvgLoader.parse("""<svg><$name $attributes/></svg>""").root.children.single()
        return SvgAnimation.parse(child, 0)
    }
    private fun number(a: SvgAnimation, time: Double, base: Double = 0.0, reference: Double = 100.0): Double =
        (a.Curve(null, false).sample(checkNotNull(a.progress(time)), reference, SvgAnimation.Value.Number(base)) as SvgAnimation.Value.Number).value

    @Test fun delayRepeatBoundariesAndRemoveAreExact() {
        val a = animation("""attributeName="opacity" values="0;1" begin="500ms" dur="2s" repeatCount="2" """)
        assertNull(a.progress(.49))
        assertEquals(0.0, number(a, .5))
        assertEquals(.5, number(a, 1.5))
        assertEquals(0.0, number(a, 2.5))
        assertNull(a.progress(4.5))
        assertEquals(4.5, a.end)
    }

    @Test fun fractionalRepeatsFreezeAtTheirPartialEndpoint() {
        val a = animation("""attributeName="stroke-width" values="2;10" dur="2s" repeatCount="2.25" fill="freeze" """)
        assertEquals(4.0, number(a, 4.5))
        assertEquals(4.0, number(a, 1000.0))
        val integer = animation("""attributeName="opacity" values="0;1" dur="1s" repeatCount="2" fill="freeze" """)
        assertEquals(1.0, number(integer, 2.0))
    }

    @Test fun negativeBeginAndIndefiniteRepeatsUseDocumentTime() {
        val a = animation("""attributeName="opacity" values="0;1" begin="-250ms" dur="1s" repeatCount="indefinite" """)
        assertEquals(.25, number(a, 0.0))
        assertEquals(.75, number(a, 1000.5))
        assertEquals(Double.POSITIVE_INFINITY, a.end)
    }

    @Test fun implicitBaseByAndPercentageValuesUseCurrentReference() {
        val to = animation("""attributeName="stroke-dashoffset" to="50%" dur="2s" """)
        assertEquals(35.0, number(to, 1.0, base = 20.0))
        assertEquals(60.0, number(to, 1.0, base = 20.0, reference = 200.0))
        val by = animation("""attributeName="stroke-dashoffset" from="10" by="-20" dur="2s" """)
        assertEquals(0.0, number(by, 1.0))
    }

    @Test fun keyTimesAndDiscreteValuesSelectTheCorrectIntervals() {
        val linear = animation("""attributeName="opacity" values="0;1;0" keyTimes="0;.25;1" dur="4s" """)
        assertEquals(.5, number(linear, .5))
        assertEquals(1.0, number(linear, 1.0))
        assertEquals(.5, number(linear, 2.5), 1e-9)
        val discrete = animation("""attributeName="opacity" values="0;.5;1" calcMode="discrete" dur="3s" """)
        assertEquals(0.0, number(discrete, .9))
        assertEquals(.5, number(discrete, 1.0))
        assertEquals(1.0, number(discrete, 2.0))
    }

    @Test fun cubicSplineChangesSpeedWithoutChangingEndpoints() {
        val a = animation("""attributeName="opacity" values="0;1" calcMode="spline" keySplines=".42 0 1 1" dur="1s" fill="freeze" """)
        assertEquals(0.0, number(a, 0.0), 1e-8)
        assertTrue(number(a, .5) in .3.. .33)
        assertEquals(1.0, number(a, 1.0))
    }

    @Test fun colourInterpolationSupportsSrgbAndLinearRgb() {
        val a = animation("""attributeName="fill" values="black;white" dur="2s" """)
        val srgb = (a.Curve(null, false).sample(.5, 1.0, null) as SvgAnimation.Value.Colour).value
        val linear = (a.Curve(null, true).sample(.5, 1.0, null) as SvgAnimation.Value.Colour).value
        assertEquals(.5, srgb.red, 1e-6)
        assertEquals(.73535698, linear.red, 1e-6)
    }

    @Test fun rotateScaleAndTranslateInterpolateTheirParameters() {
        fun matrix(type: String, from: String, to: String): Affine {
            val a = animation("""attributeName="transform" type="$type" from="$from" to="$to" dur="1s" """, true)
            return Affine().also { a.append(it, a.Curve(null, false).sample(.5, 1.0, null) as SvgAnimation.Value.Vector) }
        }
        assertEquals(10.0, matrix("translate", "0", "20 40").tx)
        assertEquals(20.0, matrix("translate", "0", "20 40").ty)
        assertEquals(2.0, matrix("scale", "1", "3").myy)
        val point = matrix("rotate", "0 10 10", "180 10 10").transform(20.0, 10.0)
        assertEquals(10.0, point.x, 1e-8); assertEquals(20.0, point.y, 1e-8)
    }

    @Test fun clockFormsAndInvalidSpecificationsAreChecked() {
        assertEquals(62.5, SvgAnimation.time("01:02.5"))
        assertEquals(3723.5, SvgAnimation.time("1:02:03.5"))
        assertEquals(120.0, SvgAnimation.time("2min"))
        for (attributes in listOf(
            """attributeName="opacity" to="1" dur="0s" """,
            """attributeName="opacity" to="1" dur="1s" begin="click" """,
            """attributeName="opacity" to="1" dur="1s" repeatCount="-1" """,
            """attributeName="opacity" values="0;1" dur="1s" keyTimes="0;.5" """,
            """attributeName="opacity" values="0;1" dur="1s" calcMode="paced" """,
            """attributeName="d" values="M0 0;M1 1" dur="1s" """,
            """attributeName="opacity" to="1" dur="1s" accumulate="sum" """
        )) assertFailsWith<IllegalArgumentException> { animation(attributes) }
        val badValues = animation("""attributeName="opacity" values="0;50%" dur="1s" """)
        assertFailsWith<IllegalArgumentException> { badValues.Curve(null, false) }
    }
}
