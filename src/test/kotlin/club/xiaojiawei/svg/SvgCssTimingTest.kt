package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgCssTimingTest {
    private fun timing(vararg values: Pair<String, String>) = SvgCssTiming.parse(SvgCssTiming.defaults +
        mapOf("animation-name" to "k", "animation-duration" to "2s", "animation-timing-function" to "linear") + values.toMap()).single()

    @Test fun delayCyclesAndFillModesHaveExactBoundaries() {
        val a = timing("animation-delay" to "1s", "animation-iteration-count" to "2")
        assertNull(a.progress(.9)); assertEquals(0.0, a.progress(1.0))
        assertEquals(.5, a.progress(2.0)); assertEquals(0.0, a.progress(3.0)); assertNull(a.progress(5.0))
        assertEquals(5.0, a.end)
        val fill = timing("animation-delay" to "1s", "animation-fill-mode" to "both")
        assertEquals(0.0, fill.progress(0.0)); assertEquals(1.0, fill.progress(3.0))
    }

    @Test fun reverseAndAlternatingDirectionsRespectIterationParity() {
        val reverse = timing("animation-direction" to "reverse")
        assertEquals(.75, reverse.progress(.5))
        val alternate = timing("animation-direction" to "alternate", "animation-iteration-count" to "3", "animation-fill-mode" to "forwards")
        assertEquals(.25, alternate.progress(.5)); assertEquals(.75, alternate.progress(2.5))
        assertEquals(1.0, alternate.progress(6.0))
        val reversed = timing("animation-direction" to "alternate-reverse", "animation-iteration-count" to "2", "animation-fill-mode" to "forwards")
        assertEquals(.75, reversed.progress(.5)); assertEquals(.25, reversed.progress(2.5))
        assertEquals(1.0, reversed.progress(4.0))
    }

    @Test fun negativeDelayAndFractionalCountUseDocumentTime() {
        val a = timing("animation-delay" to "-.5s", "animation-iteration-count" to "2.25", "animation-fill-mode" to "forwards")
        assertEquals(.25, a.progress(0.0)); assertEquals(.25, a.progress(4.0)); assertEquals(4.0, a.end)
        val b = timing("animation-iteration-count" to "1.25", "animation-direction" to "alternate", "animation-fill-mode" to "forwards")
        assertEquals(.75, b.progress(3.0))
    }

    @Test fun zeroDurationZeroCountAndPausedAnimationsDoNotRunForever() {
        val zero = timing("animation-duration" to "0s", "animation-fill-mode" to "forwards", "animation-iteration-count" to "infinite")
        assertEquals(0.0, zero.end); assertEquals(1.0, zero.progress(0.0))
        val count = timing("animation-iteration-count" to "0", "animation-fill-mode" to "forwards")
        assertEquals(0.0, count.end); assertEquals(0.0, count.progress(0.0))
        val paused = timing("animation-play-state" to "paused", "animation-delay" to "-.5s", "animation-iteration-count" to "infinite")
        assertEquals(0.0, paused.end); assertEquals(.25, paused.progress(100.0))
    }

    @Test fun cubicAndStepFunctionsSupportCommonCssEasing() {
        assertEquals(.5, SvgCssEasing.parse("linear").map(.5))
        assertTrue(SvgCssEasing.parse("ease-in").map(.5) in .3.. .33)
        assertTrue(SvgCssEasing.parse("ease-out").map(.5) in .67.. .7)
        assertEquals(.5, SvgCssEasing.parse("cubic-bezier(.42,0,.58,1)").map(.5), 1e-8)
        assertEquals(.25, SvgCssEasing.parse("steps(4)").map(.49))
        assertEquals(.5, SvgCssEasing.parse("steps(4,start)").map(.49))
        assertEquals(1.0, SvgCssEasing.parse("step-start").map(0.0))
    }

    @Test fun animationListsSplitFunctionsAndRepeatShorterLonghandLists() {
        val values = SvgCssTiming.expand("spin 2s cubic-bezier(.42,0,.58,1) infinite, pulse 1s linear 2 reverse -.5s both")
        val parsed = SvgCssTiming.parse(values)
        assertEquals(2, parsed.size); assertEquals("spin", parsed[0].name)
        assertEquals(Double.POSITIVE_INFINITY, parsed[0].end)
        assertEquals("reverse", parsed[1].direction); assertEquals(1.5, parsed[1].end)
        val repeated = SvgCssTiming.parse(SvgCssTiming.defaults + mapOf("animation-name" to "a,b", "animation-duration" to "1s"))
        assertTrue(repeated.all { it.duration == 1.0 })
    }

    @Test fun invalidSyntaxIsRejectedBeforePlayback() {
        for (value in listOf("spin -1s", "spin 1s -1s 2s", "spin 1s cubic-bezier(2,0,1,1)", "spin 1s steps(0)", "spin 1s -3")) {
            assertFailsWith<IllegalArgumentException> { SvgCssTiming.expand(value) }
        }
        assertFailsWith<IllegalArgumentException> { SvgCssTiming.seconds("2min") }
    }
}
