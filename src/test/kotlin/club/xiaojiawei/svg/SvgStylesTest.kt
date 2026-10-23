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
        val doc = SvgLoader.parse("""<svg><style>path:hover { fill: blue } path { filter: blur(2px) }</style><path/></svg>""")
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
}
