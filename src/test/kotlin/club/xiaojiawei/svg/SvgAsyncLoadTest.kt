package club.xiaojiawei.svg

import com.sun.net.httpserver.HttpServer
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgAsyncLoadTest {
    @Test fun remoteFxmlReturnsImmediatelyAndPublishesOnFxThread() = TestServer().use { server ->
        val response = server.respond("/icon", svg("remote"), delayed = true)
        lateinit var view: SvgView
        val callbackThreads = ConcurrentLinkedQueue<Boolean>()
        try {
            FxTest.run {
                val xml = """<?import club.xiaojiawei.svg.SvgView?>
                    <SvgView source="${server.url("/icon")}" fitWidth="24"/>
                """.trimIndent()
                view = FXMLLoader().load<SvgView>(ByteArrayInputStream(xml.toByteArray()))
                view.documentProperty().addListener { _, _, _ -> callbackThreads += Platform.isFxApplicationThread() }
                view.loadingProperty().addListener { _, _, _ -> callbackThreads += Platform.isFxApplicationThread() }
                assertTrue(view.isLoading)
                assertNull(view.document)
                assertEquals(server.url("/icon"), view.source)
            }
            await(response.entered)
            FxTest.run { assertTrue(view.isLoading) }
            response.release.countDown()
            awaitLoad(view)
            FxTest.run {
                assertNotNull(view.lookup("#remote"))
                assertNull(view.loadException)
                assertFalse(view.isLoading)
                assertTrue(callbackThreads.size >= 2)
                assertTrue(callbackThreads.all { it })
            }
        } finally { response.release.countDown() }
    }

    @Test fun newerSourceWinsOverDelayedSuccessOrFailure() = TestServer().use { server ->
        server.respond("/new", svg("new"))
        for (status in listOf(200, 500)) {
            val old = server.respond("/old$status", svg("old"), status, delayed = true)
            lateinit var view: SvgView
            try {
                FxTest.run { view = SvgView().apply { source = server.url("/old$status") } }
                await(old.entered)
                FxTest.run { view.source = server.url("/new") }
                awaitLoad(view)
                old.release.countDown()
                await(old.finished)
                FxTest.run {
                    assertNotNull(view.lookup("#new"))
                    assertNull(view.lookup("#old"))
                    assertEquals(server.url("/new"), view.source)
                    assertNull(view.loadException)
                    assertFalse(view.isLoading)
                }
            } finally { old.release.countDown() }
        }
    }

    @Test fun clearDirectDocumentAndLocalSourceCancelPendingRemoteLoad() = TestServer().use { server ->
        for (replacement in listOf("clear", "document", "local")) {
            val old = server.respond("/$replacement", svg("old"), delayed = true)
            lateinit var view: SvgView
            try {
                FxTest.run { view = SvgView().apply { source = server.url("/$replacement") } }
                await(old.entered)
                FxTest.run {
                    when (replacement) {
                        "clear" -> view.source = null
                        "document" -> view.document = SvgLoader.parse(svg("direct"))
                        "local" -> view.source = javaClass.getResource("/demo/sample.svg")!!.toExternalForm()
                    }
                    assertFalse(view.isLoading)
                }
                old.release.countDown()
                await(old.finished)
                FxTest.run {
                    assertNull(view.lookup("#old"))
                    assertNull(view.loadException)
                    when (replacement) {
                        "clear" -> { assertNull(view.document); assertNull(view.source) }
                        "document" -> { assertNotNull(view.lookup("#direct")); assertNull(view.source) }
                        "local" -> assertNotNull(view.lookup("#outline"))
                    }
                }
            } finally { old.release.countDown() }
        }
    }

    @Test fun downloadParseAndRenderFailuresPreserveOldContentAndAllowRetry() = TestServer().use { server ->
        server.respond("/missing", "not found", status = 404)
        server.respond("/malformed", "<svg>")
        server.respond("/unrenderable", """<svg viewBox="0 0 -1 1"/>""")
        server.respond("/valid", svg("valid"))
        lateinit var view: SvgView
        val callbackThreads = ConcurrentLinkedQueue<Boolean>()
        FxTest.run {
            view = SvgView.parse(svg("valid"))
            view.loadExceptionProperty().addListener { _, _, _ -> callbackThreads += Platform.isFxApplicationThread() }
        }
        for (path in listOf("/missing", "/malformed", "/unrenderable")) {
            lateinit var previous: SvgDocument
            lateinit var previousNode: javafx.scene.Node
            FxTest.run {
                previous = view.document!!
                previousNode = view.lookup("#valid")
                view.source = server.url(path)
            }
            awaitLoad(view)
            FxTest.run {
                assertNotNull(view.loadException)
                assertSame(previous, view.document)
                assertSame(previousNode, view.lookup("#valid"))
                assertEquals(server.url(path), view.source)
                view.source = server.url("/valid")
                assertNull(view.loadException)
                assertTrue(view.isLoading)
            }
            awaitLoad(view)
            FxTest.run { assertNotNull(view.lookup("#valid")); assertNull(view.loadException) }
        }
        assertTrue(callbackThreads.isNotEmpty() && callbackThreads.all { it })
    }

    @Test fun stalledNetworkReadTimesOutWithoutBlockingFxThread() = TestServer().use { server ->
        val response = server.respond("/stalled", svg("late"), delayed = true)
        lateinit var view: SvgView
        try {
            FxTest.run { view = SvgView().apply { source = server.url("/stalled") } }
            await(response.entered)
            FxTest.run { assertTrue(view.isLoading) }
            awaitLoad(view, 15)
            FxTest.run {
                assertNull(view.document)
                assertNotNull(view.loadException)
                assertTrue(generateSequence<Throwable>(view.loadException) { it.cause }.any { it is SocketTimeoutException })
                assertFalse(view.isLoading)
            }
        } finally { response.release.countDown() }
    }

    @Test fun completionListenerCanStartAnotherLoadWithoutLosingLoadingState() = TestServer().use { server ->
        server.respond("/first", svg("first"))
        val second = server.respond("/second", svg("second"), delayed = true)
        lateinit var view: SvgView
        try {
            FxTest.run {
                view = SvgView()
                view.documentProperty().addListener { _, _, document ->
                    if (document?.elementsById?.containsKey("first") == true) view.source = server.url("/second")
                }
                view.source = server.url("/first")
            }
            await(second.entered)
            FxTest.run {
                assertTrue(view.isLoading)
                assertEquals(server.url("/second"), view.source)
            }
            second.release.countDown()
            awaitLoad(view)
            FxTest.run { assertNotNull(view.lookup("#second")); assertNull(view.loadException) }
        } finally { second.release.countDown() }
    }

    private fun svg(id: String) = """<svg viewBox="0 0 24 24"><path id="$id" d="M0 0L12 12"/></svg>"""

    private fun await(latch: CountDownLatch, seconds: Long = 5) {
        assertTrue(latch.await(seconds, TimeUnit.SECONDS), "Timed out waiting for HTTP fixture")
    }

    private fun awaitLoad(view: SvgView, seconds: Long = 5) {
        val finished = CountDownLatch(1)
        val listener = javafx.beans.value.ChangeListener<Boolean> { _, _, loading -> if (!loading) finished.countDown() }
        FxTest.run {
            view.loadingProperty().addListener(listener)
            if (!view.isLoading) finished.countDown()
        }
        try { assertTrue(finished.await(seconds, TimeUnit.SECONDS), "SVG load did not finish") }
        finally { FxTest.run { view.loadingProperty().removeListener(listener) } }
    }

    private class Response(val delayed: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        val finished = CountDownLatch(1)
    }

    private class TestServer : AutoCloseable {
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@TestServer.executor
            start()
        }
        fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
        fun respond(path: String, body: String, status: Int = 200, delayed: Boolean = false): Response {
            val response = Response(delayed)
            server.createContext(path) { exchange ->
                try {
                    response.entered.countDown()
                    check(response.release.await(25, TimeUnit.SECONDS))
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "image/svg+xml")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: java.io.IOException) {
                    // Superseded requests may close their sockets before the server responds.
                } finally {
                    exchange.close()
                    response.finished.countDown()
                }
            }
            return response
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
