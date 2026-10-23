package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*

class SvgParserTest {
    @Test fun parsesNamespacesAndIndexesIds() {
        val doc = SvgLoader.parse("""<svg xmlns="http://www.w3.org/2000/svg"><g id="a"><path id="b" d="M0 0L1 1"/></g></svg>""")
        assertEquals("path", doc.elementsById["b"]?.name)
        assertSame(doc.root.children[0], doc.elementsById["a"])
    }

    @Test fun rejectsDoctypeAndMalformedInput() {
        assertFailsWith<SvgException> { SvgLoader.parse("""<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///does-not-exist">]><svg>&x;</svg>""") }
        assertFailsWith<SvgException> { SvgLoader.parse("<html/>") }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><path></svg>") }
        assertFailsWith<SvgException> { SvgLoader.parse("""<svg><g id="a"/><path id="a"/></svg>""") }
    }

    @Test fun enforcesLimitsAndLeavesCallerStreamOpen() {
        assertFailsWith<SvgException> { SvgLoader.parse("<svg/>", SvgOptions(maxBytes = 5)) }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><g><path/></g></svg>", SvgOptions(maxDepth = 2)) }
        assertFailsWith<SvgException> { SvgLoader.parse("<svg><g/></svg>", SvgOptions(maxElements = 1)) }
        var closed = false
        val input = object : ByteArrayInputStream("<svg/>".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        SvgLoader.load(input)
        assertFalse(closed)
    }

    @Test fun numbersUnitsAndTransformOrder() {
        assertEquals(listOf(1.0, -2.0, 0.3, 400.0), SvgNumbers.list("1-2 .3 4e2"))
        assertEquals(96.0, SvgNumbers.length("1in", 1.0))
        assertEquals(50.0, SvgNumbers.length("25%", 200.0))
        assertFails { SvgNumbers.scalar("NaN") }
        assertFails { SvgTransforms.parse("translate(2) garbage") }
        val point = SvgTransforms.parse("translate(10,20) scale(2) rotate(90)").transform(1.0, 0.0)
        assertEquals(10.0, point.x, 1e-8)
        assertEquals(22.0, point.y, 1e-8)
    }

    @Test fun viewBoxHonorsOriginAndAspectRatio() {
        val box = SvgBox(10.0, 20.0, 100.0, 50.0)
        val meet = box.transform(200.0, 200.0, null)
        assertEquals(0.0, meet.transform(10.0, 20.0).x)
        assertEquals(50.0, meet.transform(10.0, 20.0).y)
        val stretch = box.transform(200.0, 200.0, "none")
        assertEquals(200.0, stretch.transform(110.0, 70.0).y)
        val slice = box.transform(200.0, 200.0, "xMidYMid slice")
        assertEquals(-100.0, slice.transform(10.0, 20.0).x)
    }
}
