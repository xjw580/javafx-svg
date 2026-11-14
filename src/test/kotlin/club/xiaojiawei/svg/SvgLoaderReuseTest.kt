package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.*

class SvgLoaderReuseTest {
    @Test fun reusedParsersKeepDepthAndEntityRestrictionsAcrossFailures() {
        repeat(80) { SvgLoader.parse("<svg><g><rect width=\"1\"/></g></svg>") }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><g><rect/></g></svg>", SvgOptions(maxDepth = 2)) }
        assertEquals("svg", SvgLoader.parse("<svg><g><rect/></g></svg>", SvgOptions(maxDepth = 4)).root.name)
        assertFailsWith<SvgException> { SvgLoader.parse("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///never-read'>]><svg>&x;</svg>") }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><g></svg>") }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg id=\"x\"><rect id=\"x\"/></svg>") }
        assertEquals("ok", SvgLoader.parse("<svg id=\"ok\"/>").root.id)
    }

    @Test fun parserThreadsAndDocumentsNeverShareMutableDomState() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 120).map { index -> pool.submit<SvgDocument> {
                SvgLoader.parse("<svg id=\"root$index\"><rect id=\"r$index\" width=\"$index\"/></svg>")
            } }
            futures.forEachIndexed { index, future ->
                val document = future.get()
                assertEquals("root$index", document.root.id)
                assertEquals(index.toString(), document.elementsById.getValue("r$index")["width"])
                assertEquals(2, document.elementsById.size)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun largeAndLimitedInputsDoNotPoisonTheNextLoad() {
        val before = SvgLoader.parse("<svg id=\"before\"/>")
        SvgLoader.parse("<svg><!--" + "x".repeat(150_000) + "--><rect id=\"large\"/></svg>")
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><rect/></svg>", SvgOptions(maxBytes = 8)) }
        assertEquals("after", SvgLoader.parse("<svg id=\"after\"/>").root.id)
        assertEquals("before", before.root.id)
    }

    @Test fun resourceFreeLoadsKeepInlineStylesNamespacesAndMixedContent() {
        val options = SvgOptions(strict = true, resourceResolver = SvgResourceResolver {
            fail("A resource-free document must not call the resolver")
        })
        for (namespace in listOf("", " xmlns=\"http://www.w3.org/2000/svg\"")) {
            val document = SvgLoader.parse("""<svg$namespace><g><rect id="r" style="fill:red"/></g>
                <text>A<tspan>B</tspan>C</text></svg>""", options)
            assertTrue(document.diagnostics.isEmpty())
            assertTrue(document.resources.styles.isEmpty())
            assertTrue(document.resources.images.isEmpty())
            assertTrue(document.resources.styleIssues.isEmpty())
            assertEquals("fill:red", document.elementsById.getValue("r")["style"])
            val text = document.root.children[1]
            assertEquals("AC", text.text)
            assertEquals(3, text.content.size)
            assertEquals("B", text.children.single().text)
        }
    }

    @Test fun resourceFeaturesStillPrepareAfterResourceFreeLoads() {
        repeat(3) { SvgLoader.parse("<svg><rect/></svg>") }
        val document = SvgLoader.parse("""<svg><g><g><style>rect { fill: red }</style>
            <image id="missing"/></g></g></svg>""")
        assertEquals("rect{ fill: red }", document.resources.styles.single().text)
        assertTrue(document.diagnostics.single().message.contains("Missing image href"))
        assertEquals("missing", document.diagnostics.single().elementId)

        val requests = mutableListOf<SvgResourceRequest>()
        val base = URI("https://assets.example.test/icons/icon.svg")
        val instruction = SvgLoader.parse("""<?xml-stylesheet href="theme.css"?><svg><rect/></svg>""", base,
            SvgOptions(strict = true, resourceResolver = SvgResourceResolver {
                requests += it
                "rect { fill: blue }".byteInputStream()
            }))
        assertEquals(base.resolve("theme.css"), requests.single().uri)
        assertEquals(SvgResourceKind.STYLESHEET, requests.single().kind)
        assertEquals("rect{ fill: blue }", instruction.resources.styles.single().text)
        assertTrue(instruction.diagnostics.isEmpty())
    }

    @Test fun xmlBaseValidationIsNotSkippedWhenThereAreNoResources() {
        for (xml in listOf("""<svg xml:base="not a URI"/>""",
            """<svg><g><rect xml:base="not a URI"/></g></svg>""")) {
            assertTrue(SvgLoader.parse(xml).diagnostics.single().message.contains("Invalid xml:base"))
            assertFailsWith<SvgException> { SvgLoader.parse(xml, SvgOptions(strict = true)) }
        }
        val foreign = SvgLoader.parse("""<svg xmlns:e="urn:editor"><e:metadata xml:base="not a URI">
            <style media="print">rect { fill: red }</style><image/></e:metadata></svg>""", SvgOptions(strict = true))
        assertTrue(foreign.diagnostics.isEmpty())
        assertTrue(foreign.resources.styles.isEmpty())
        assertTrue(foreign.resources.images.isEmpty())
    }
}
