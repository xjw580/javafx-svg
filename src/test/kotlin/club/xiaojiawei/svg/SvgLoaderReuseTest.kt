package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
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
}
