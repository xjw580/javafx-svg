package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.LinearGradient
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgLengthCacheTest {
    @Test fun overflowDoesNotPoisonParsedValueAndAbsoluteLengthsIgnoreReference() {
        val cache = SvgLoader.parse("<svg/>").lengths
        repeat(2) {
            assertEquals(0.0, cache.resolve("1e308%", 0.0))
            assertFailsWith<IllegalArgumentException> { cache.resolve("1e308%", 100.0) }
            assertFailsWith<IllegalArgumentException> { cache.resolve("1e308in", 0.0) }
            assertFailsWith<IllegalArgumentException> { cache.resolve("1e309%", 0.0) }
            assertEquals("Unsupported length: 1em", assertFailsWith<SvgException> { cache.resolve("1em", 10.0) }.message)
            assertEquals(96.0, cache.resolve("1IN", Double.NaN))
        }
        assertEquals(0.0, cache.resolve("1e308%", 0.0))
    }

    @Test fun concurrentFirstUseDoesNotShareResolvedPercentages() {
        val doc = SvgLoader.parse("<svg/>")
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map { index ->
                pool.submit<Double> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    doc.lengths.resolve("50%", index * 10.0)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEachIndexed { index, task -> assertEquals((index + 1) * 5.0, task.get(10, TimeUnit.SECONDS)) }
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun sharedLengthsResolveForEachViewNestedViewportGradientAndFont() = FxTest.run {
        val doc = SvgLoader.parse("""<svg font-size="20"><defs><linearGradient id="g"
            gradientUnits="userSpaceOnUse" x2="50%"><stop stop-color="red"/>
            <stop offset="1" stop-color="blue"/></linearGradient></defs>
            <svg width="50%" height="50%"><rect id="r" width="50%" height="50%" fill="url(#g)"
            stroke="black" stroke-width="10%"/></svg><text id="t" font-size="50%">A</text></svg>""", SvgOptions(strict = true))
        val first = SvgView(doc).apply { isManaged = false }
        val second = SvgView(doc).apply { isManaged = false }
        Scene(Group(first, second))
        first.resize(200.0, 100.0); first.layout()
        second.resize(400.0, 200.0); second.layout()
        val a = first.lookup("#r") as Rectangle
        val b = second.lookup("#r") as Rectangle
        assertEquals(50.0, a.width); assertEquals(25.0, a.height)
        assertEquals(100.0, b.width); assertEquals(50.0, b.height)
        assertEquals(50.0, (a.fill as LinearGradient).endX, .001)
        assertEquals(100.0, (b.fill as LinearGradient).endX, .001)
        assertEquals(a.strokeWidth * 2, b.strokeWidth, .001)
        assertEquals(10.0, (first.lookup("#t") as Text).font.size)
        assertEquals(10.0, (second.lookup("#t") as Text).font.size)
        first.resize(100.0, 200.0); first.layout()
        assertEquals(25.0, a.width); assertEquals(50.0, a.height)
        assertEquals(25.0, (a.fill as LinearGradient).endX, .001)
        assertEquals(100.0, b.width)
    }

    @Test fun invalidLengthsStillReportEachElementAndStrictAttempt() = FxTest.run {
        for (value in listOf("1em", "1e309", "1e308in", "-10%")) {
            val xml = """<svg><rect id="a" x="$value" width="$value" height="10"/>
                <rect id="b" width="$value" height="10"/></svg>"""
            val doc = SvgLoader.parse(xml)
            val first = SvgView(doc)
            val second = SvgView(doc)
            assertEquals(first.diagnostics, second.diagnostics)
            assertEquals<List<String?>>(listOf("a", "b"), first.diagnostics.map { it.elementId })
            val strictDoc = SvgLoader.parse(xml, SvgOptions(strict = true))
            repeat(2) { assertFailsWith<SvgException> { SvgView(strictDoc) } }
        }
        val hidden = SvgLoader.parse("""<svg><defs><rect width="invalid"/></defs>
            <rect display="none" width="invalid"/></svg>""", SvgOptions(strict = true))
        repeat(2) { assertTrue(SvgView(hidden).diagnostics.isEmpty()) }
    }
}
