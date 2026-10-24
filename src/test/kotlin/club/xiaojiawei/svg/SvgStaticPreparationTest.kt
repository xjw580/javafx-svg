package club.xiaojiawei.svg

import javafx.scene.effect.DropShadow
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Scale
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.*

class SvgStaticPreparationTest {
    @Test fun stringAndStreamUseTheSameUtf8ByteLimitAndKeepCallerStreamOpen() {
        val xml = """<svg><text id="中文">路径颜色</text></svg>"""
        val bytes = xml.toByteArray(Charsets.UTF_8)
        var closed = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val options = SvgOptions(maxBytes = bytes.size)
        val fromString = SvgLoader.parse(xml, options)
        val fromStream = SvgLoader.load(stream, options)
        assertEquals(bytes.size, fromString.sourceBytes)
        assertEquals(fromString.sourceBytes, fromStream.sourceBytes)
        assertEquals("路径颜色", fromString.elementsById.getValue("中文").text)
        assertEquals(fromString.elementsById.getValue("中文").text, fromStream.elementsById.getValue("中文").text)
        assertFalse(closed)
        for (limit in listOf(bytes.size - 1, xml.length)) {
            assertFailsWith<SvgException> { SvgLoader.parse(xml, SvgOptions(maxBytes = limit)) }
            assertFailsWith<SvgException> { SvgLoader.load(ByteArrayInputStream(bytes), SvgOptions(maxBytes = limit)) }
        }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><g></svg>") }
        val failure = assertFailsWith<SvgException> {
            SvgLoader.load(object : InputStream() { override fun read(): Int = throw IOException("read failure") })
        }
        assertIs<IOException>(failure.cause)
        assertEquals("svg", SvgLoader.parse("<svg/>").root.name)
    }

    @Test fun pathGeometryExcludesClipEffectTransformsAndStrokeAndUpdatesWithContent() = FxTest.run {
        val path = SVGPath().apply {
            content = "M10 20H70V50H10Z"
            clip = Rectangle(1.0, 1.0)
            effect = DropShadow(20.0, Color.BLACK)
            transforms += Scale(3.0, 4.0)
        }
        fun check(x: Double, y: Double, w: Double, h: Double) {
            val bounds = svgGeometryBounds(path)
            assertEquals(x, bounds.minX, 1e-8); assertEquals(y, bounds.minY, 1e-8)
            assertEquals(w, bounds.width, 1e-8); assertEquals(h, bounds.height, 1e-8)
        }
        check(10.0, 20.0, 60.0, 30.0)
        path.stroke = Color.BLUE; path.strokeWidth = 40.0
        check(10.0, 20.0, 60.0, 30.0)
        path.fill = null
        check(10.0, 20.0, 60.0, 30.0)
        path.stroke = null
        check(10.0, 20.0, 60.0, 30.0)
        path.fill = Color.RED
        path.content = "M5 6H25V36H5Z"
        check(5.0, 6.0, 20.0, 30.0)
    }

    @Test fun staticTransformValidationAndExplicitIdentityRemainAvailable() = FxTest.run {
        val identity = SvgView.parse("""<svg><g id="g" transform="scale(1)"><rect width="10" height="10"/></g></svg>""", SvgOptions(strict = true))
        assertTrue(identity.lookup("#g").transforms.first().isIdentity)
        for (attribute in listOf("""style="transform-box:stroke-box" """, """style="transform-origin:invalid" """)) {
            assertFailsWith<SvgException> { SvgView.parse("<svg><rect width=\"10\" height=\"10\" $attribute/></svg>", SvgOptions(strict = true)) }
            val loose = SvgView.parse("<svg><rect width=\"10\" height=\"10\" $attribute/></svg>")
            assertTrue(loose.diagnostics.isNotEmpty())
        }
    }
}
