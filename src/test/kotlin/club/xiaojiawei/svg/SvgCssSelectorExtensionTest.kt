package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgCssSelectorExtensionTest {
    private fun color(selector: String, body: String, id: String = "t"): String? {
        val doc = SvgLoader.parse("""<svg><style>$selector { fill: red }</style>$body</svg>""")
        val styles = SvgStyles(doc) { message, _ -> error(message) }
        fun visit(e: SvgElement, parents: List<SvgElement>, inherited: Map<String,String>): String? {
            val computed = styles.compute(e, parents, inherited)
            if(e.id == id) return computed["fill"]
            e.children.forEach { visit(it, parents + e, computed)?.let { answer -> return answer } }
            return null
        }
        return visit(doc.root, emptyList(), emptyMap())
    }
    @Test fun childDistinguishesDescendant() {
        assertEquals("red", color("g > rect", "<g><rect id='t'/></g>"))
        assertEquals("black", color("g > rect", "<g><a><rect id='t'/></a></g>"))
        assertEquals("red", color("g rect", "<g><a><rect id='t'/></a></g>"))
    }
    @Test fun siblingsAndCompoundChains() {
        assertEquals("red", color("g > circle + rect", "<g><circle/><rect id='t'/></g>"))
        assertEquals("black", color("circle + rect", "<circle/><path/><rect id='t'/>"))
        assertEquals("red", color("circle ~ rect", "<circle/><path/><rect id='t'/>"))
        assertEquals("black", color("circle + rect", "<circle/><g><rect id='t'/></g>"))
    }
    @Test fun descendantBacktracksAcrossChildConstraints() {
        assertEquals("red", color(".a > .b rect", "<g class='a'><g class='b'><g><g class='b'><rect id='t'/></g></g></g></g>"))
    }
    @Test fun attributeOperatorsAndQuotedWhitespace() {
        for (selector in listOf("[data-tag]", "[data-tag='hello world']", "[data-tag~=world]", "[data-tag^=hello]", "[data-tag$=world]", "[data-tag*='lo wo']", "[lang|=en]"))
            assertEquals("red", color(selector, "<rect id='t' data-tag='hello world' lang='en-US'/>"), selector)
        assertEquals("black", color("[missing]", "<rect id='t'/>"))
        assertEquals("black", color("[data-tag^='']", "<rect id='t' data-tag='x'/>"))
        assertEquals("black", color("[data-tag~=hell]", "<rect id='t' data-tag='hello world'/>"))
    }
    @Test fun quotedCombinatorsAndCommasStayInValue() {
        assertEquals("red", color("[data-tag='a > b,+']", "<rect id='t' data-tag='a &gt; b,+'/ >".replace("/ >", "/>")))
    }
    @Test fun classAndAttributeSpecificityAreEqual() {
        val doc = SvgLoader.parse("""<svg><style>.x {fill:red} [data-x] {fill:blue}</style><rect class="x" data-x="yes"/></svg>""")
        val styles = SvgStyles(doc) {message,_->error(message)}
        assertEquals("blue", styles.compute(doc.root.children.last(), listOf(doc.root), emptyMap())["fill"])
    }
    @Test fun malformedAndUnsupportedSelectorsAreRejected() {
        for(s in listOf("-", "-1", ".-1", "> rect", "g >", "g ++ rect", "g||rect", "rect:hover", "[x i]", "[x='a' i]", "[x=]", "[x='a'", "[x='a\\b']", "g > > rect"))
            assertFailsWith<IllegalArgumentException>(s) { SvgCssSelectors.parse(s) }
        assertFailsWith<IllegalArgumentException> { SvgCssSelectors.parse(List(129){"g"}.joinToString(" ")) }
    }
    @Test fun namespaceElementInterruptsAdjacentSibling() {
        assertEquals("black", color("circle + rect", "<circle/><m:x xmlns:m='urn:metadata'/><rect id='t'/>"))
    }
    @Test fun hostileSiblingCombinationsShareAWholeRendererWorkLimit() {
        val body=List(1000) { "<rect id='n$it'/>" }.joinToString("")
        val doc=SvgLoader.parse("<svg><style>.missing ~ * ~ rect {fill:red}</style>$body</svg>")
        val issues=mutableListOf<String>()
        val styles=SvgStyles(doc){message,_->issues+=message}
        for(node in doc.root.children) styles.compute(node,listOf(doc.root),emptyMap())
        assertEquals(1,issues.count { "selector-work limit" in it })
        val strict=SvgStyles(doc){message,_->throw SvgException(message)}
        assertFailsWith<SvgException> { for(node in doc.root.children) strict.compute(node,listOf(doc.root),emptyMap()) }
    }

    @Test fun useShadowSelectorsCannotMatchUnclonedHostButInheritanceSurvives() {
        val doc=SvgLoader.parse("""<svg><style>use > rect {fill:red} .outer use rect {stroke:red} g > rect + circle {stroke:blue}</style><defs><g id="group"><rect/><circle/></g></defs><g class="outer"><use href="#group"/></g></svg>""")
        val issues=mutableListOf<String>();val styles=SvgStyles(doc){m,_->issues+=m}
        val group=doc.elementsById.getValue("group");val host=doc.root.children.last().children.single()
        val rect=group.children[0];val circle=group.children[1]
        val inherited=mapOf("fill" to "green")
        assertEquals("green",styles.compute(rect,listOf(doc.root,host),inherited)["fill"])
        assertEquals("none",styles.compute(rect,listOf(doc.root,doc.root.children.last(),host,group),inherited)["stroke"])
        assertEquals("blue",styles.compute(circle,listOf(doc.root,host,group),inherited)["stroke"])
        assertTrue(issues.isEmpty())
    }

    @Test fun specificityIsLexicographicAndNeverCarriesBetweenComponents() {
        val ids="#t".repeat(101);val attrs="[data-a]".repeat(101)
        val doc=SvgLoader.parse("""<svg><style>#t {fill:green} $attrs {fill:blue} $ids {stroke:red}</style><rect id="t" data-a="x" style="stroke:purple"/></svg>""")
        val engine=SvgStyles(doc){m,_->error(m)}
        val values=engine.compute(doc.root.children.last(),listOf(doc.root),emptyMap())
        assertEquals("green",values["fill"]);assertEquals("purple",values["stroke"])
    }

    @Test fun repeatedLargeAttributeWorkCannotOverflowBudgetArithmetic() {
        val selector="[data-x]".repeat(2200)
        val value="a".repeat(1_000_000)
        val doc=SvgLoader.parse("<svg><style>$selector {fill:red}</style><rect data-x='$value'/></svg>")
        val issues=mutableListOf<String>();val styles=SvgStyles(doc){m,_->issues+=m}
        styles.compute(doc.root.children.last(),listOf(doc.root),emptyMap())
        assertTrue(issues.any { "selector-work limit" in it })
    }

    @Test fun repetitiveSubstringSearchIsChargedBeforeMatching() {
        val value="a".repeat(2000)
        val needle="a".repeat(1499)+"b"
        val doc=SvgLoader.parse("<svg><style>[data-x*='$needle'] {fill:red}</style><rect data-x='$value' style='fill:blue'/></svg>")
        val node=doc.root.children.last()
        val issues=mutableListOf<String>();val loose=SvgStyles(doc){m,_->issues+=m}
        repeat(2) {assertEquals("blue",loose.compute(node,listOf(doc.root),emptyMap())["fill"])}
        assertEquals(1,issues.count {"selector-work limit" in it})
        val strict=SvgStyles(doc){m,_->throw SvgException(m)}
        assertFailsWith<SvgException>{strict.compute(node,listOf(doc.root),emptyMap())}
    }
    @Test fun syntheticClassIdentitiesShareTokenizationBudgetAndDiagnoseOnce() {
        val value = "unused ".repeat(20_000)
        val doc = SvgLoader.parse("<svg><style>.hit {fill:red}</style><rect class='$value' style='fill:blue'/></svg>")
        val original = doc.root.children.last()
        fun clone() = SvgElement(original.name, original.attributes, original.children, original.text, original.content)
        val issues = mutableListOf<String>()
        val loose = SvgStyles(doc) { message, _ -> issues += message }
        // Same element is cached, but synthetic use identities must not evade the budget.
        repeat(20) { assertEquals("blue", loose.compute(original, listOf(doc.root), emptyMap())["fill"]) }
        assertTrue(issues.isEmpty())
        repeat(20) { assertEquals("blue", loose.compute(clone(), listOf(doc.root), emptyMap())["fill"]) }
        assertEquals(1, issues.count { "selector-work limit" in it })
        val strict = SvgStyles(doc) { message, _ -> throw SvgException(message) }
        assertFailsWith<SvgException> {
            repeat(20) { strict.compute(clone(), listOf(doc.root), emptyMap()) }
        }
    }

}
