package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.lang.reflect.Modifier
import kotlin.test.*

class SvgPublicApiTest {
    private val xml = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect id="box" width="10" height="10"/></svg>"""

    @Test fun javaConvenienceConstructorsPreserveNullDefaults() {
        val exception = SvgException::class.java.getConstructor(String::class.java).newInstance("failure")
        assertEquals("failure", exception.message)
        assertNull(exception.cause)
        val diagnostic = SvgDiagnostic::class.java.getConstructor(String::class.java).newInstance("warning")
        assertEquals(SvgDiagnostic("warning", null), diagnostic)
        val cause = IllegalStateException("cause")
        assertSame(cause, SvgException("failure", cause).cause)
    }

    @Test fun documentedLoaderFactoriesAreJavaStaticOverloads() {
        val plain = SvgLoader::class.java.getMethod("parse", String::class.java)
        val origin = SvgLoader::class.java.getMethod("parse", String::class.java, URI::class.java)
        assertTrue(Modifier.isStatic(plain.modifiers))
        assertTrue(Modifier.isStatic(origin.modifiers))
        val document = origin.invoke(null, xml, URI("file:/icons/example.svg")) as SvgDocument
        assertEquals(URI("file:/icons/example.svg"), document.baseUri)
        assertEquals("rect", document.elementsById.getValue("box").name)
    }

    @Test fun parsedCollectionsAreRuntimeReadOnlyAndStreamRemainsCallerOwned() {
        var closed = false
        val stream = object : ByteArrayInputStream(xml.toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val document = SvgLoader.load(stream)
        assertFalse(closed)
        assertFailsWith<UnsupportedOperationException> { (document.root.attributes as MutableMap)["fake"] = "value" }
        assertFailsWith<UnsupportedOperationException> { (document.root.children as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (document.elementsById as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (document.diagnostics as MutableList).add(SvgDiagnostic("fake")) }
        assertEquals("box", document.root.children.single().id)
        stream.close()
        assertTrue(closed)
    }

    @Test fun optionCopyPreservesOriginalDefaultsAndLimits() {
        val original = SvgOptions()
        val strict = original.copy(strict = true, maxBytes = 100)
        assertFalse(original.strict)
        assertTrue(strict.strict)
        assertEquals(100, strict.maxBytes)
        assertNull(strict.resourceResolver)
        assertEquals(original.maxFilterPixels, strict.maxFilterPixels)
        assertEquals(original.maxImagePixels, strict.maxImagePixels)
        assertFailsWith<IllegalArgumentException> { original.copy(maxBytes = 0) }
    }

    @Test fun viewClearingAndDiagnosticSnapshotUsePublicContract() = FxTest.run {
        val document = SvgLoader.parse("""<svg viewBox="0 0 10 10"><unsupported/></svg>""")
        val view = SvgView(document)
        val first = view.diagnostics
        assertTrue(first.isNotEmpty())
        assertFailsWith<UnsupportedOperationException> { (first as MutableList).clear() }
        view.document = null
        assertNull(view.document)
        assertNull(view.source)
        assertFalse(view.isLoading)
        assertTrue(view.diagnostics.isEmpty())
        assertTrue(first.isNotEmpty())
    }
}
