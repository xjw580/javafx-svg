package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgStyleCacheTest {
    @Test fun concurrentFirstUseSharesCompilationButKeepsInheritanceIndependent() {
        val doc = SvgLoader.parse("""<svg><style>.part { stroke: green }</style>
            <path id="p" class="part" style="fill: inherit; font-size: 2em"/></svg>""")
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map { size ->
                pool.submit<Pair<SvgStyleSheet, Map<String, String>>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    val engine = SvgStyles(doc) { message, _ -> error(message) }
                    val result = engine.compute(doc.elementsById.getValue("p"), listOf(doc.root),
                        mapOf("fill" to "blue", "font-size" to size.toString()))
                    doc.styleSheet to result
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEachIndexed { index, task ->
                val (sheet, result) = task.get(10, TimeUnit.SECONDS)
                assertSame(doc.styleSheet, sheet)
                assertEquals("green", result["stroke"])
                assertEquals("blue", result["fill"])
                assertEquals(((index + 1) * 2.0).toString(), result["font-size"])
            }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun replaysDiagnosticsForEveryViewAndEveryStrictAttempt() = FxTest.run {
        val xml = """<svg><style id="sheet">path:hover { fill: red }</style>
            <path id="p" d="M0 0L1 1" style="paint-order: normal; fill: green"/>
            <defs><path id="unused" style="unsupported: value"/></defs></svg>"""
        val doc = SvgLoader.parse(xml)
        val first = SvgView(doc)
        val second = SvgView(doc)
        assertEquals(first.diagnostics, second.diagnostics)
        assertEquals<List<String?>>(listOf("sheet", "p"), first.diagnostics.map { it.elementId })
        for (strictXml in listOf(xml, """<svg><path d="M0 0L1 1" style="paint-order: normal"/></svg>""")) {
            val strictDoc = SvgLoader.parse(strictXml, SvgOptions(strict = true))
            repeat(2) { assertFailsWith<SvgException> { SvgView(strictDoc) } }
        }
        val unused = SvgLoader.parse("""<svg><defs><path style="unsupported: value"/></defs></svg>""",
            SvgOptions(strict = true))
        repeat(2) { assertTrue(SvgView(unused).diagnostics.isEmpty()) }
    }

    @Test fun reportsSharedInlineErrorsAgainstTheCurrentElement() {
        val doc = SvgLoader.parse("""<svg><path id="a" style="paint-order: normal; fill: green"/>
            <path id="b" style="paint-order: normal; fill: green"/></svg>""")
        repeat(2) {
            val ids = mutableListOf<String?>()
            val engine = SvgStyles(doc) { _, element -> ids += element?.id }
            for (id in listOf("a", "b")) {
                val style = engine.compute(doc.elementsById.getValue(id), listOf(doc.root), emptyMap())
                assertEquals("green", style["fill"])
            }
            assertEquals<List<String?>>(listOf("a", "b"), ids)
        }
    }

    @Test fun sharedDocumentKeepsUseInstancesAndJavaFxCssIndependent() = FxTest.run {
        val doc = SvgLoader.parse("""<svg viewBox="0 0 40 20"><style>
            .red { fill: red } .blue { fill: blue }
            </style><defs><symbol id="symbol" viewBox="0 0 10 10" style="stroke: inherit">
            <path id="p" class="part" d="M0 0L10 10" style="stroke-width: 2"/></symbol></defs>
            <use id="a" class="red" href="#symbol" width="10" height="10" stroke="green"/>
            <use id="b" class="blue" href="#symbol" width="20" height="20" stroke="purple"/></svg>""",
            SvgOptions(strict = true))
        val first = SvgView(doc)
        val second = SvgView(doc)
        fun path(view: SvgView, use: String) = view.lookup("#$use").lookup("#p") as SVGPath
        val red = path(first, "a")
        val blue = path(first, "b")
        assertEquals(Color.RED, red.fill)
        assertEquals(Color.BLUE, blue.fill)
        assertEquals(Color.GREEN, red.stroke)
        assertEquals(Color.PURPLE, blue.stroke)
        assertNotSame(red, path(second, "a"))
        val root = Group(first, second)
        Scene(root)
        red.style = "-fx-fill: lime; -fx-stroke-width: 5;"
        root.applyCss()
        assertEquals(Color.LIME, red.fill)
        assertEquals(5.0, red.strokeWidth)
        assertEquals(Color.RED, path(second, "a").fill)
        assertEquals(2.0, path(second, "a").strokeWidth)
        assertEquals(Color.BLUE, blue.fill)
    }

    @Test fun keepsStylesOfDifferentDocumentsSeparate() {
        val documents = listOf("red", "blue").map { color ->
            SvgLoader.parse("""<svg><style>#p { fill: $color }</style><path id="p"/></svg>""")
        }
        val colors = documents.map { doc ->
            SvgStyles(doc) { message, _ -> error(message) }
                .compute(doc.elementsById.getValue("p"), listOf(doc.root), emptyMap()).getValue("fill")
        }
        assertEquals(listOf("red", "blue"), colors)
        assertNotSame(documents[0].styleSheet, documents[1].styleSheet)
    }
}
