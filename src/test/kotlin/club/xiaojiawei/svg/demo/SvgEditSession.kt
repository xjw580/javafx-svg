package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgDocument
import club.xiaojiawei.svg.SvgLoader
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.util.Duration
import org.xml.sax.SAXParseException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor

/** FX-owned editor state; only immutable document parsing runs on the worker. */
internal class SvgEditSession(
    private val apply: (SvgDocument) -> String,
    private val report: (String, Boolean) -> Unit,
    delay: Duration = Duration.millis(300.0),
    private val parse: (String) -> SvgDocument = { SvgLoader.parse(it) }
) : AutoCloseable {
    private var revision = 0L
    private var source = ""
    private var closed = false
    private var pending: Future<*>? = null
    private val debounce = PauseTransition(delay).apply { setOnFinished { flush() } }
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "svg-live-editor-parser").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun edit(value: String) {
        check(Platform.isFxApplicationThread())
        if (closed) return
        source = value
        revision++
        pending?.cancel(true)
        pending = null
        debounce.stop()
        if (value.isBlank()) report("请输入完整 SVG；保留上一次有效预览。", false)
        else {
            report("等待输入完成…", false)
            debounce.playFromStart()
        }
    }

    fun flush() {
        check(Platform.isFxApplicationThread())
        if (closed || source.isBlank()) return
        debounce.stop()
        pending?.cancel(true)
        val ticket = ++revision
        val input = source
        report("正在解析 SVG…", false)
        pending = worker.submit {
            val parsed = runCatching { parse(input) }
            try {
                Platform.runLater {
                    if (closed || revision != ticket) return@runLater
                    val result = parsed.mapCatching(apply)
                    if (closed || revision != ticket) return@runLater
                    result.fold({ report(it, false) }, { report(problem(it), true) })
                    pending = null
                }
            } catch (_: IllegalStateException) {
                // The JavaFX toolkit can already be closed when a cancelled parse finishes.
            }
        }
    }

    override fun close() {
        check(Platform.isFxApplicationThread())
        if (closed) return
        closed = true
        revision++
        debounce.stop()
        pending?.cancel(true)
        pending = null
        worker.shutdownNow()
    }

    private fun problem(failure: Throwable): String {
        val xml = generateSequence(failure) { it.cause }.filterIsInstance<SAXParseException>().firstOrNull()
        val location = if (xml != null && xml.lineNumber > 0)
            "第 " + xml.lineNumber + " 行，第 " + xml.columnNumber + " 列："
        else ""
        val message = (xml?.message ?: failure.message ?: "无法渲染 SVG").replace(Regex("\\s+"), " ").take(280)
        return location + message + "（保留上一次有效预览）"
    }
}
