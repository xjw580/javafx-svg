package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.FxTest
import club.xiaojiawei.svg.SvgLoader
import javafx.application.Platform
import javafx.util.Duration
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SvgEditSessionTest {
    private fun svg(colour: String) = """<svg><rect id="p" width="10" height="10" fill="$colour"/></svg>"""
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(8, TimeUnit.SECONDS), "Editor callback timed out")

    @Test fun inputIsDebouncedAndParsingRunsOffFx() {
        val calls = AtomicInteger()
        val onFx = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val applied = mutableListOf<String>()
        var created=false
        lateinit var session: SvgEditSession
        try {
            FxTest.run {
                session = SvgEditSession(
                    apply = { applied += it.elementsById.getValue("p")["fill"]!!; "done" },
                    report = { message, _ -> if (message == "done") ready.countDown() },
                    delay = Duration.millis(80.0),
                    parse = { text -> calls.incrementAndGet(); onFx.set(Platform.isFxApplicationThread()); SvgLoader.parse(text) })
                created=true
                session.edit(svg("red")); session.edit(svg("blue")); session.edit(svg("green"))
            }
            await(ready)
            FxTest.run { assertEquals(listOf("green"), applied) }
            assertEquals(1, calls.get())
            assertFalse(onFx.get())
        } finally { if (created) FxTest.run { session.close() } }
    }

    @Test fun malformedXmlReportsLocationAndDoesNotApply() {
        val good = CountDownLatch(1)
        val error = CountDownLatch(1)
        val applied = AtomicInteger()
        var message = ""
        var created=false
        lateinit var session: SvgEditSession
        try {
            FxTest.run {
                session = SvgEditSession(
                    apply = { applied.incrementAndGet(); "done" },
                    report = { text, failed -> if (failed) { message=text; error.countDown() } else if(text=="done") good.countDown() })
                created=true
                session.edit(svg("red")); session.flush()
            }
            await(good)
            FxTest.run { session.edit("<svg>\n<rect></svg>"); session.flush() }
            await(error)
            assertEquals(1, applied.get())
            assertContains(message, "第 2 行")
            assertContains(message, "保留上一次有效预览")
        } finally { if (created) FxTest.run { session.close() } }
    }

    @Test fun cancelledSlowParseCannotOverwriteNewInput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val applied = mutableListOf<String>()
        var created=false
        lateinit var session: SvgEditSession
        try {
            FxTest.run {
                session = SvgEditSession(
                    apply = { applied += it.elementsById.getValue("p")["fill"]!!; "done" },
                    report = { text, _ -> if(text=="done") ready.countDown() },
                    parse = { text ->
                        if(text=="slow") {
                            entered.countDown()
                            var released=false
                            while(!released) try { released=release.await(1,TimeUnit.SECONDS) } catch(_: InterruptedException) { }
                        }
                        SvgLoader.parse(svg(if(text=="slow") "red" else "blue"))
                    })
                created=true
                session.edit("slow"); session.flush()
            }
            await(entered)
            FxTest.run { session.edit("latest"); session.flush() }
            release.countDown()
            await(ready)
            FxTest.run { assertEquals(listOf("blue"),applied) }
        } finally {
            release.countDown()
            if (created) FxTest.run { session.close() }
        }
    }
}
