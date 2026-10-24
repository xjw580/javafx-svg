package club.xiaojiawei.svg

import club.xiaojiawei.svg.SvgStyleSheet.Compound
import club.xiaojiawei.svg.SvgStyleSheet.Declaration
import java.util.IdentityHashMap

/** A deliberately bounded SVG stylesheet engine. JavaFX CSS remains the host application's override layer. */
internal class SvgStyles(
    document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit
) {
    private val sheet = document.styleSheet
    private data class Winner(val value: String, val important: Boolean, val specificity: Int, val order: Int)
    private val elementClasses = IdentityHashMap<SvgElement, Set<String>>()

    init {
        sheet.reportDiagnostics(report)
    }

    fun compute(element: SvgElement, ancestors: List<SvgElement>, parent: Map<String, String>): Map<String, String> {
        val winners = mutableMapOf<String, Winner>()
        var order = 0
        fun add(d: Declaration, specificity: Int) {
            val next = Winner(d.value, d.important, specificity, order++)
            val old = winners[d.name]
            if (old == null || compareValuesBy(next, old, { it.important }, { it.specificity }, { it.order }) >= 0) {
                winners[d.name] = next
            }
        }
        element.attributes.filterKeys { it in defaults }.forEach { (k, v) -> add(Declaration(k, v, false), -1) }
        sheet.candidates(element) { classes(element) }.forEach { rule ->
            if (matches(rule.selector, element, ancestors)) {
                rule.declarations.forEach { add(it, rule.specificity) }
            }
        }
        sheet.inline(element, report).forEach { d -> add(d, 1_000_000) }
        val result = defaults.mapValues { (key, initial) ->
            val value = winners[key]?.value ?: if (key in inherited) parent[key] ?: initial else initial
            when (value) {
                "inherit" -> parent[key] ?: initial
                "initial" -> initial
                "unset" -> if (key in inherited) parent[key] ?: initial else initial
                else -> value
            }
        }.toMutableMap()
        if (result["color"] == "currentColor") result["color"] = parent["color"] ?: "black"
        val fontSize = result.getValue("font-size")
        val parentFontSize = parent["font-size"]?.toDoubleOrNull() ?: 16.0
        result["font-size"] = (if (fontSize.endsWith("em")) SvgNumbers.scalar(fontSize.dropLast(2)) * parentFontSize
            else SvgNumbers.length(fontSize, parentFontSize)).also { require(it >= 0) { "Negative font size" } }.toString()
        return result
    }

    private fun matches(selector: List<Compound>, element: SvgElement, ancestors: List<SvgElement>): Boolean {
        if (!matchesCompound(selector.last(), element)) return false
        var ancestor = ancestors.lastIndex
        for (index in selector.lastIndex - 1 downTo 0) {
            val part = selector[index]
            while (ancestor >= 0 && !matchesCompound(part, ancestors[ancestor])) ancestor--
            if (ancestor < 0) return false
            ancestor--
        }
        return true
    }

    private fun matchesCompound(selector: Compound, element: SvgElement): Boolean =
        (selector.type == null || selector.type == element.name) &&
            selector.ids.all { it == element.id } &&
            (selector.classes.isEmpty() || classes(element).containsAll(selector.classes))

    private fun classes(element: SvgElement): Set<String> = elementClasses.getOrPut(element) {
        element["class"]?.split(whitespace)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    companion object {
        private val whitespace = Regex("\\s+")
        val defaults = mapOf(
            "fill" to "black", "stroke" to "none", "color" to "black",
            "stroke-width" to "1", "fill-opacity" to "1", "stroke-opacity" to "1", "opacity" to "1",
            "fill-rule" to "nonzero", "clip-rule" to "nonzero",
            "stroke-linecap" to "butt", "stroke-linejoin" to "miter", "stroke-miterlimit" to "4",
            "stroke-dasharray" to "none", "stroke-dashoffset" to "0",
            "display" to "inline", "visibility" to "visible",
            "clip-path" to "none", "filter" to "none", "color-interpolation-filters" to "linearRGB",
            "stop-color" to "black", "stop-opacity" to "1",
            "font-family" to "sans-serif", "font-size" to "16", "font-weight" to "normal",
            "font-style" to "normal", "text-anchor" to "start"
        )
        private val inherited = defaults.keys - setOf("opacity", "display", "clip-path", "filter", "stop-color", "stop-opacity")
    }
}
