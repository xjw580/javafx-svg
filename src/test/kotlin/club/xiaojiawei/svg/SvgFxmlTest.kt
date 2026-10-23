package club.xiaojiawei.svg

import javafx.fxml.FXMLLoader
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SvgFxmlTest {
    @TempDir lateinit var directory: Path

    @Test fun fxmlResolvesRelativeSvgAndCssWithBeanProperties() = FxTest.run {
        val loader = FXMLLoader(javaClass.getResource("/demo/icon.fxml"))
        val view = loader.load<SvgView>()
        val root = Group(view)
        Scene(root)
        root.applyCss(); root.layout()
        assertSame(view, loader.namespace["icon"])
        assertEquals(javaClass.getResource("/demo/sample.svg")!!.toExternalForm(), view.source)
        assertEquals(32.0, view.fitWidth)
        assertEquals(32.0, view.fitHeight)
        assertEquals(32.0, view.width)
        assertTrue(view.preserveRatio)
        assertTrue("svg-view" in view.styleClass)
        val path = view.lookup("#outline") as SVGPath
        assertEquals(Color.web("#e6f0ff"), path.fill)
        assertEquals(Color.web("#409eff"), path.stroke)
        assertEquals(6.0, path.strokeWidth)
    }

    @Test fun fxmlResolvesClasspathAbsoluteSource() = FxTest.run {
        val xml = """<?import club.xiaojiawei.svg.SvgView?>
            <SvgView source="@/demo/sample.svg" fitWidth="24" preserveRatio="false"/>
        """.trimIndent()
        val view = FXMLLoader().load<SvgView>(ByteArrayInputStream(xml.toByteArray()))
        assertNotNull(view.lookup("#outline"))
        assertFalse(view.preserveRatio)
        assertEquals(24.0, view.prefWidth(-1.0))
    }

    @Test fun invalidSourcePreservesExistingSourceDocumentAndNodes() = FxTest.run {
        val view = FXMLLoader(javaClass.getResource("/demo/icon.fxml")).load<SvgView>()
        val source = view.source
        val document = view.document
        val path = view.lookup("#outline")
        val malformed = Files.writeString(directory.resolve("malformed.svg"), "<svg>")
        val unrenderable = Files.writeString(directory.resolve("invalid.svg"), """<svg viewBox="0 0 -1 1"/>""")
        val invalidSources = listOf("sample.svg", "not a URL", directory.resolve("missing.svg").toUri().toString(),
            malformed.toUri().toString(), unrenderable.toUri().toString())
        for (invalid in invalidSources) {
            assertFailsWith<SvgException> { view.source = invalid }
            assertEquals(source, view.source)
            assertSame(document, view.document)
            assertSame(path, view.lookup("#outline"))
        }
    }

    @Test fun sourceCanReloadClearAndYieldToDirectDocumentAssignment() = FxTest.run {
        val view = SvgView()
        val observed = mutableListOf<String?>()
        view.sourceProperty().addListener { _, _, value -> observed += value }
        val source = javaClass.getResource("/demo/sample.svg")!!.toExternalForm()
        view.source = source
        val first = view.lookup("#outline")
        view.source = source
        assertNotSame(first, view.lookup("#outline"))
        val direct = SvgLoader.parse("""<svg><path id="direct" d="M0 0L10 10"/></svg>""")
        view.document = direct
        assertNull(view.source)
        assertSame(direct, view.document)
        assertNotNull(view.lookup("#direct"))
        assertEquals(listOf(source, null), observed)
        view.source = source
        view.source = " "
        assertNull(view.source)
        assertNull(view.document)
        assertTrue(view.childrenUnmodifiable.isEmpty())
        view.source = source
        view.source = null
        assertNull(view.document)
        assertTrue(view.childrenUnmodifiable.isEmpty())
    }
}
