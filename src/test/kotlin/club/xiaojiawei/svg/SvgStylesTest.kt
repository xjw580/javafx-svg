package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.test.*

class SvgStylesTest {
    @Test fun combinesSelectorsInheritanceAndImportant() {
        val doc = SvgLoader.parse("""<svg fill="green" opacity=".5">
            <style>path { fill: red } .icon path.main { fill: blue; stroke-width: 3 }
            #target { fill: yellow !important }</style>
            <g class="icon" color="purple"><path id="target" class="main" style="fill: orange"/>
            <path id="other" class="main" fill="black"/></g></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val root = engine.compute(doc.root, emptyList(), emptyMap())
        val group = doc.root.children.last()
        val inherited = engine.compute(group, listOf(doc.root), root)
        val first = engine.compute(group.children[0], listOf(doc.root, group), inherited)
        val second = engine.compute(group.children[1], listOf(doc.root, group), inherited)
        assertEquals("yellow", first["fill"])
        assertEquals("blue", second["fill"])
        assertEquals("3", second["stroke-width"])
        assertEquals("purple", second["color"])
        assertEquals("1", inherited["opacity"])
        assertEquals(".5", root["opacity"])
    }

    @Test fun diagnosesUnsupportedSelectorsAndProperties() {
        val doc = SvgLoader.parse("""<svg><style>path:hover { fill: blue } path { mask: url(#mask) }</style><path/></svg>""")
        val issues = mutableListOf<String>()
        SvgStyles(doc) { message, _ -> issues += message }
        assertEquals(2, issues.size)
    }

    @Test fun explicitInheritanceIncludesNonInheritedProperties() {
        val doc = SvgLoader.parse("""<svg opacity=".4"><path style="opacity: inherit; fill: initial"/></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val parent = engine.compute(doc.root, emptyList(), emptyMap())
        val child = engine.compute(doc.root.children.single(), listOf(doc.root), parent)
        assertEquals(".4", child["opacity"])
        assertEquals("black", child["fill"])
    }

    @Test fun preservesSourceOrderAcrossSelectorKinds() {
        val equallySpecific = listOf(
            ".a" to ".b",
            "path.b" to ".scope path",
            "path#target" to "#container path",
            "#target" to "#container *",
            "*" to "*"
        )
        for ((left, right) in equallySpecific) {
            for ((first, last) in listOf(left to right, right to left)) {
                val doc = SvgLoader.parse("""<svg id="container" class="scope">
                    <style>$first { fill: red } $last { fill: blue; fill: green }</style>
                    <path id="target" class="b a b"/></svg>""")
                val engine = SvgStyles(doc) { message, _ -> error(message) }
                val result = engine.compute(doc.elementsById.getValue("target"), listOf(doc.root), emptyMap())
                assertEquals("green", result["fill"], "$first followed by $last")
            }
        }
    }

    @Test fun checksEveryPartOfCompoundAndGroupedSelectors() {
        val doc = SvgLoader.parse("""<svg><style>
            *, circle { fill: orange }
            path.a.b#target#target { fill: green }
            circle#target, path.a.missing, #target#other { fill: red !important }
            path.a.b { stroke: purple !IMPORTANT; stroke: blue }
            </style><path id="target" class=" a  b a " style="stroke: red"/></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val result = engine.compute(doc.elementsById.getValue("target"), listOf(doc.root), emptyMap())
        assertEquals("green", result["fill"])
        assertEquals("purple", result["stroke"])
    }

    @Test fun matchesDescendantsInOrderWithoutReusingAnAncestor() {
        val doc = SvgLoader.parse("""<svg class="outer"><style>
            .outer .inner path { fill: green }
            .inner .outer path, .inner .inner path { fill: red !important }
            </style><g class="inner"><g><path id="target"/></g></g></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val inner = doc.root.children.last()
        val gap = inner.children.single()
        val result = engine.compute(doc.elementsById.getValue("target"), listOf(doc.root, inner, gap), emptyMap())
        assertEquals("green", result["fill"])
    }

    @Test fun recomputesSharedElementsForEachInstanceContext() {
        val doc = SvgLoader.parse("""<svg><style>
            .red .part { fill: red } .blue .part { fill: blue }
            </style><defs><path id="target" class="part" style="font-size: 2em; opacity: inherit"/></defs>
            <g id="first" class="red"/><g id="second" class="blue"/></svg>""")
        val engine = SvgStyles(doc) { message, _ -> error(message) }
        val target = doc.elementsById.getValue("target")
        val red = engine.compute(target, listOf(doc.root, doc.elementsById.getValue("first")),
            mapOf("font-size" to "10", "opacity" to ".2"))
        val blue = engine.compute(target, listOf(doc.root, doc.elementsById.getValue("second")),
            mapOf("font-size" to "20", "opacity" to ".8"))
        assertEquals("red", red["fill"])
        assertEquals("blue", blue["fill"])
        assertEquals("20.0", red["font-size"])
        assertEquals("40.0", blue["font-size"])
        assertEquals(".2", red["opacity"])
        assertEquals(".8", blue["opacity"])
    }
}
