package club.xiaojiawei.svg

import java.util.IdentityHashMap

/** A deliberately bounded SVG stylesheet engine. JavaFX CSS remains the host application's override layer. */
internal class SvgStyles(
    document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit
) {
    private data class Declaration(val name: String, val value: String, val important: Boolean)
    private data class Compound(val type: String?, val ids: List<String>, val classes: List<String>)
    private data class Rule(
        val selector: List<Compound>, val declarations: List<Declaration>, val specificity: Int, val order: Int
    )
    private data class Winner(val value: String, val important: Boolean, val specificity: Int, val order: Int)
    private val idRules = mutableMapOf<String, MutableList<Rule>>()
    private val classRules = mutableMapOf<String, MutableList<Rule>>()
    private val typeRules = mutableMapOf<String, MutableList<Rule>>()
    private val universalRules = mutableListOf<Rule>()
    private val elementClasses = IdentityHashMap<SvgElement, Set<String>>()
    private var ruleOrder = 0

    init {
        fun visit(element: SvgElement) {
            if (element.name == "style") {
                if (element["type"] !in listOf(null, "", "text/css") || element["media"] !in listOf(null, "", "all")) {
                    report("Only unconditional text/css stylesheets are supported", element)
                } else {
                    parseSheet(element.text, element)
                }
            }
            element.children.forEach(::visit)
        }
        visit(document.root)
    }

    private fun parseSheet(text: String, element: SvgElement) {
        val source = comments.replace(text, "")
        if ('@' in source || source.count { it == '{' } != source.count { it == '}' }) {
            report("CSS at-rules or malformed stylesheet are unsupported", element)
            return
        }
        var end = 0
        for (block in blocks.findAll(source)) {
            if (source.substring(end, block.range.first).isNotBlank()) report("Malformed CSS stylesheet", element)
            end = block.range.last + 1
            val declarations = declarations(block.groupValues[2], element)
            for (selector in block.groupValues[1].split(',')) {
                val parts = selector.trim().split(whitespace)
                if (parts.any { it.isEmpty() || !compound.matches(it) }) {
                    report("Unsupported CSS selector: ${selector.trim()}", element)
                    continue
                }
                val specificity = parts.sumOf {
                    it.count { c -> c == '#' } * 10000 + it.count { c -> c == '.' } * 100 +
                        if (it.first() !in ".#*") 1 else 0
                }
                val compiled = parts.map { part ->
                    val type = part.takeWhile { it != '.' && it != '#' }.takeUnless { it.isEmpty() || it == "*" }
                    val tokens = selectorTokens.findAll(part).toList()
                    Compound(type, tokens.filter { it.groupValues[1] == "#" }.map { it.groupValues[2] },
                        tokens.filter { it.groupValues[1] == "." }.map { it.groupValues[2] })
                }
                val rule = Rule(compiled, declarations, specificity, ruleOrder++)
                val last = compiled.last()
                // Index each rule once; still check the entire compound and its ancestors when matching.
                when {
                    last.ids.isNotEmpty() -> idRules.getOrPut(last.ids.first()) { mutableListOf() }
                    last.classes.isNotEmpty() -> classRules.getOrPut(last.classes.first()) { mutableListOf() }
                    last.type != null -> typeRules.getOrPut(last.type) { mutableListOf() }
                    else -> universalRules
                }.add(rule)
            }
        }
        if (source.substring(end).isNotBlank()) report("Malformed CSS stylesheet", element)
    }

    private fun declarations(text: String, element: SvgElement): List<Declaration> =
        comments.replace(text, "").split(';').mapNotNull { declaration ->
            if (declaration.isBlank()) return@mapNotNull null
            val colon = declaration.indexOf(':')
            if (colon < 1) {
                report("Invalid CSS declaration: $declaration", element)
                return@mapNotNull null
            }
            val name = declaration.substring(0, colon).trim().lowercase()
            if (name !in defaults) {
                report("Unsupported SVG CSS property: $name", element)
                return@mapNotNull null
            }
            val raw = declaration.substring(colon + 1).trim()
            val important = importantSuffix.containsMatchIn(raw)
            val value = raw.replace(importantSuffix, "").trim()
            if (value.isEmpty()) {
                report("Empty CSS property: $name", element)
                return@mapNotNull null
            }
            Declaration(name, value, important)
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
        val candidates = ArrayList<Rule>(universalRules)
        element.id?.let { idRules[it]?.let(candidates::addAll) }
        typeRules[element.name]?.let(candidates::addAll)
        if (classRules.isNotEmpty()) {
            classes(element).forEach { className -> classRules[className]?.let(candidates::addAll) }
        }
        // Restore stylesheet order across index buckets before applying declarations.
        candidates.sortBy { it.order }
        candidates.forEach { rule ->
            if (matches(rule.selector, element, ancestors)) {
                rule.declarations.forEach { add(it, rule.specificity) }
            }
        }
        element["style"]?.let { declarations(it, element).forEach { d -> add(d, 1_000_000) } }
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
        private val compound = Regex("(?:\\*|[a-zA-Z_][\\w-]*)?(?:[.#][a-zA-Z_][\\w-]*)*")
        private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val blocks = Regex("([^{}]+)\\{([^{}]*)}")
        private val whitespace = Regex("\\s+")
        private val selectorTokens = Regex("([.#])([\\w-]+)")
        private val importantSuffix = Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE)
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
