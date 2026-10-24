package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgCssParserTest {
    private fun style(css: String, inline: String = ""): Pair<Map<String, String>, List<String>> {
        val doc = SvgLoader.parse("""<svg><style><![CDATA[$css]]></style><path id="p" class="a" style="$inline"/></svg>""")
        val issues = mutableListOf<String>()
        val engine = SvgStyles(doc) { message, _ -> issues += message }
        return engine.compute(doc.elementsById.getValue("p"), listOf(doc.root), emptyMap()) to issues
    }

    @Test fun unknownAtRulesKeepTheOrdinaryRulesOnBothSides() {
        val (values, issues) = style("""path { fill: red; }
            @media (max-width: 500px) { path { fill: blue; } }
            @import url("https://example.invalid/a;b@c.css");
            .a { stroke: green; }""")
        assertEquals("red", values["fill"]); assertEquals("green", values["stroke"])
        assertEquals(2, issues.size)
    }

    @Test fun scannerKeepsQuotedDelimitersAndFunctionListsIntact() {
        assertEquals(listOf("a 1s cubic-bezier(.1, 0, .9, 1)", "b 2s"), SvgCssSyntax.split("a 1s cubic-bezier(.1, 0, .9, 1), b 2s"))
        val (values, issues) = style("""path { font-family: "A;B@/*C*/"; fill: red; }""")
        assertEquals("\"A;B@/*C*/\"", values["font-family"])
        assertEquals("red", values["fill"]); assertTrue(issues.isEmpty())
    }

    @Test fun malformedTrailingRuleDoesNotEraseEarlierStyles() {
        val (values, issues) = style("""path { fill: red; } @keyframes spin { from { opacity: 0; }""")
        assertEquals("red", values["fill"]); assertTrue(issues.isNotEmpty())
    }

    @Test fun keyframesParseSelectorsAndLastDefinitionWins() {
        val doc = SvgLoader.parse("""<svg><style>
            @keyframes spin { from, 25% { opacity: .2; } to { opacity: 1; } }
            path { fill: green; }
            </style><style>@KEYFRAMES spin { 100% { opacity: .7 } 0% { opacity: .3 } }</style></svg>""")
        val issues = mutableListOf<String>()
        SvgStyles(doc) { message, _ -> issues += message }
        val frames = doc.styleSheet.keyframes.getValue("spin").frames
        assertEquals(2, frames.size)
        assertEquals(listOf(".7", ".3"), frames.map { it.declarations.single().value })
        assertTrue(issues.isEmpty())
    }

    @Test fun invalidKeyframeSelectorsAndImportantDeclarationsStayLocal() {
        val doc = SvgLoader.parse("""<svg><style>
            @keyframes k { -2% { opacity: 0 } 50% { fill: red !important; opacity: .5 } to { opacity: 1 } }
            path { fill: green }
            </style><path id="p"/></svg>""")
        val issues = mutableListOf<String>()
        val engine = SvgStyles(doc) { message, _ -> issues += message }
        val result = engine.compute(doc.elementsById.getValue("p"), listOf(doc.root), emptyMap())
        assertEquals("green", result["fill"]); assertEquals(1, issues.size)
        assertTrue(doc.styleSheet.keyframes.getValue("k").frames.none { f -> f.declarations.any { it.name == "fill" } })
    }

    @Test fun shorthandResetsLonghandsAndParticipatesInTheCascade() {
        val (values, issues) = style("""path { animation-delay: 9s; animation: spin 2s linear infinite alternate 300ms both; }
            #p { animation-duration: 4s !important; }""", "animation-duration: 8s")
        assertEquals("spin", values["animation-name"])
        assertEquals("4s", values["animation-duration"])
        assertEquals("300ms", values["animation-delay"])
        assertEquals("alternate", values["animation-direction"])
        assertEquals("infinite", values["animation-iteration-count"])
        assertTrue("animation-duration" in (values as SvgStyleInfo).important)
        assertTrue(issues.isEmpty())
    }

    @Test fun invalidAnimationDeclarationPreservesTheEarlierValue() {
        val (values, issues) = style("path { animation-duration: 2s; animation-duration: -1s; animation-delay: 3s; animation: bad -2s; fill: green; }")
        assertEquals("2s", values["animation-duration"]); assertEquals("3s", values["animation-delay"])
        assertEquals("green", values["fill"]); assertEquals(2, issues.size)
    }

    @Test fun animationAndTransformPropertiesDoNotImplicitlyInherit() {
        val doc = SvgLoader.parse("""<svg style="animation: spin 2s; transform: scale(2); transform-origin: center">
            <path id="a"/><path id="b" style="animation: inherit"/></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val parent = engine.compute(doc.root, emptyList(), emptyMap())
        val a = engine.compute(doc.elementsById.getValue("a"), listOf(doc.root), parent)
        val b = engine.compute(doc.elementsById.getValue("b"), listOf(doc.root), parent)
        assertEquals("none", a["animation-name"]); assertEquals("none", a["transform"])
        assertEquals("0 0", a["transform-origin"]); assertEquals("spin", b["animation-name"])
    }

    @Test fun anUnclosedTrailingCommentKeepsPreviouslyCompletedRules() {
        val (values, issues) = style("path { fill: red; } /* unfinished")
        assertEquals("red", values["fill"]); assertTrue(issues.any { it.contains("comment") })
    }
}
