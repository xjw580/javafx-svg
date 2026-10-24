package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.FillRule
import javafx.scene.shape.SVGPath
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SvgGeometryCacheTest {
    @Test fun distinguishesOpenClosedAndPathDataAndPreservesEmptyGeometry() {
        val geometry = SvgLoader.parse("<svg/>").geometry
        val open = geometry.points("0,0 10,0 10,10", false)
        val closed = geometry.points("0,0 10,0 10,10", true)
        assertEquals("M0.0 0.0 L10.0 0.0 L10.0 10.0", open)
        assertEquals("$open Z", closed)
        assertSame(open, geometry.points("0,0 10,0 10,10", false))
        assertSame(closed, geometry.points("0,0 10,0 10,10", true))
        assertFailsWith<SvgException> { geometry.path("0,0 10,0 10,10") }
        assertEquals("", geometry.path(""))
        assertEquals("", geometry.points("", false))
        assertEquals("", geometry.points("", true))
        val arc = "M0 0a10 10 0 0110 20z"
        assertEquals(arc, geometry.path(arc))
        assertEquals(arc, geometry.path(arc))
    }

    @Test fun preparesGeometryOnceDuringConcurrentFirstUse() {
        val doc = SvgLoader.parse("<svg/>")
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map {
                pool.submit<String> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    doc.geometry.points("0,0 10,0 10,10", true)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = tasks.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals("M0.0 0.0 L10.0 0.0 L10.0 10.0 Z", results.first())
            results.forEach { assertSame(results.first(), it) }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun replaysFailuresForCurrentElementAndEveryStrictView() = FxTest.run {
        val cases = listOf(
            "path" to "d=\"M0\"",
            "path" to "d=\"M0 0 A-1 2 0 0 1 3 4\"",
            "path" to "d=\"M0 0 A1 2 0 2 1 3 4\"",
            "path" to "d=\"M0 0 L1e999 2\"",
            "polyline" to "points=\"0 0 1\"",
            "polygon" to "points=\"0 0 invalid\""
        )
        for ((tag, attribute) in cases) {
            val xml = """<svg><$tag id="a" $attribute/><$tag id="b" $attribute/></svg>"""
            val doc = SvgLoader.parse(xml)
            val first = SvgView(doc)
            val second = SvgView(doc)
            assertEquals(first.diagnostics, second.diagnostics)
            assertEquals<List<String?>>(listOf("a", "b"), first.diagnostics.map { it.elementId })
            assertEquals(first.diagnostics[0].message, first.diagnostics[1].message)
            val strictDoc = SvgLoader.parse(xml, SvgOptions(strict = true))
            val messages = (1..2).map { assertFailsWith<SvgException> { SvgView(strictDoc) }.message }
            assertEquals(messages[0], messages[1])
            assertTrue(messages[0]!!.contains("#a"))
        }
    }

    @Test fun unusedAndHiddenInvalidGeometryIsNotValidatedEarly() = FxTest.run {
        val doc = SvgLoader.parse("""<svg><defs><path id="unused" d="M0"/>
            <polygon points="0 0 1"/></defs><g display="none"><path d="M0"/></g>
            <polyline display="none" points="invalid"/></svg>""", SvgOptions(strict = true))
        repeat(2) { assertTrue(SvgView(doc).diagnostics.isEmpty()) }
    }

    @Test fun reusedGeometryKeepsNodesStylesAndTransformsIndependent() = FxTest.run {
        val doc = SvgLoader.parse("""<svg viewBox="0 0 40 20"><defs>
            <polygon id="shape" points="0 0 10 0 10 10"/>
            </defs><use id="a" href="#shape" fill="red" fill-rule="evenodd"/>
            <use id="b" href="#shape" x="20" fill="blue" fill-rule="nonzero"/></svg>""",
            SvgOptions(strict = true))
        fun shape(view: SvgView, use: String) = view.lookup("#$use").lookup("#shape") as SVGPath
        val first = SvgView(doc)
        val second = SvgView(doc)
        first.isManaged = false
        second.isManaged = false
        val root = Group(first, second)
        Scene(root)
        first.resize(40.0, 20.0)
        second.resize(80.0, 40.0)
        first.layout(); second.layout()
        val red = shape(first, "a")
        val blue = shape(first, "b")
        val other = shape(second, "a")
        assertNotSame(red, blue)
        assertNotSame(red, other)
        assertSame(red.content, blue.content)
        assertSame(red.content, other.content)
        assertEquals(Color.RED, red.fill)
        assertEquals(Color.BLUE, blue.fill)
        assertEquals(FillRule.EVEN_ODD, red.fillRule)
        assertEquals(FillRule.NON_ZERO, blue.fillRule)
        assertEquals(20.0, blue.localToScene(0.0, 0.0).x)
        assertEquals(20.0, other.localToScene(10.0, 0.0).x)
        red.style = "-fx-fill: lime; -fx-stroke-width: 5;"
        root.applyCss()
        assertEquals(Color.LIME, red.fill)
        assertEquals(Color.RED, other.fill)
        val original = other.content
        red.content = "M0 0L1 1"
        assertEquals(original, blue.content)
        assertEquals(original, other.content)
        assertEquals(original, shape(SvgView(doc), "a").content)
    }

    @Test fun differentDocumentsAndFailedReplacementsRemainIsolated() = FxTest.run {
        val good = SvgLoader.parse("""<svg><path id="p" d="M0 0L10 10"/></svg>""")
        val different = SvgLoader.parse("""<svg><path id="p" d="M0 0L20 20"/></svg>""")
        val bad = SvgLoader.parse("""<svg><path id="p" d="M0"/></svg>""", SvgOptions(strict = true))
        val view = SvgView(good)
        val original = view.lookup("#p") as SVGPath
        repeat(2) {
            assertFailsWith<SvgException> { view.document = bad }
            assertSame(good, view.document)
            assertSame(original, view.lookup("#p"))
        }
        view.document = different
        assertEquals("M0 0L20 20", (view.lookup("#p") as SVGPath).content)
        view.document = good
        assertEquals(original.content, (view.lookup("#p") as SVGPath).content)
        assertNotSame(original, view.lookup("#p"))
    }
}
