package club.xiaojiawei.svg

/** A deliberately bounded SVG stylesheet engine. JavaFX CSS remains the host application's override layer. */
internal class SvgStyles(
    document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit
) {
    private data class Declaration(val name: String, val value: String, val important: Boolean)
    private data class Rule(val selector: List<String>, val declarations: List<Declaration>, val specificity: Int)
    private data class Winner(val value: String, val important: Boolean, val specificity: Int, val order: Int)
    private val rules = mutableListOf<Rule>()
    private val compound = Regex("(?:\\*|[a-zA-Z_][\\w-]*)?(?:[.#][a-zA-Z_][\\w-]*)*")
    private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

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
        for (block in Regex("([^{}]+)\\{([^{}]*)}").findAll(source)) {
            if (source.substring(end, block.range.first).isNotBlank()) report("Malformed CSS stylesheet", element)
            end = block.range.last + 1
            val declarations = declarations(block.groupValues[2], element)
            for (selector in block.groupValues[1].split(',')) {
                val parts = selector.trim().split(Regex("\\s+"))
                if (parts.any { it.isEmpty() || !compound.matches(it) }) {
                    report("Unsupported CSS selector: ${selector.trim()}", element)
                    continue
                }
                val specificity = parts.sumOf {
                    it.count { c -> c == '#' } * 10000 + it.count { c -> c == '.' } * 100 +
                        if (it.first() !in ".#*") 1 else 0
                }
                rules += Rule(parts, declarations, specificity)
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
            val important = Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(raw)
            val value = raw.replace(Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE), "").trim()
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
        rules.filter { matches(it.selector, element, ancestors) }.forEach { rule ->
            rule.declarations.forEach { add(it, rule.specificity) }
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
        return result
    }

    private fun matches(selector: List<String>, element: SvgElement, ancestors: List<SvgElement>): Boolean {
        if (!matchesCompound(selector.last(), element)) return false
        var ancestor = ancestors.lastIndex
        for (part in selector.dropLast(1).asReversed()) {
            while (ancestor >= 0 && !matchesCompound(part, ancestors[ancestor])) ancestor--
            if (ancestor < 0) return false
            ancestor--
        }
        return true
    }

    private fun matchesCompound(selector: String, element: SvgElement): Boolean {
        val type = selector.takeWhile { it != '.' && it != '#' }
        if (type.isNotEmpty() && type != "*" && type != element.name) return false
        val classes = element["class"]?.split(Regex("\\s+")) ?: emptyList()
        return Regex("([.#])([\\w-]+)").findAll(selector).all {
            if (it.groupValues[1] == "#") element.id == it.groupValues[2] else it.groupValues[2] in classes
        }
    }

    companion object {
        val defaults = mapOf(
            "fill" to "black", "stroke" to "none", "color" to "black",
            "stroke-width" to "1", "fill-opacity" to "1", "stroke-opacity" to "1", "opacity" to "1",
            "fill-rule" to "nonzero", "clip-rule" to "nonzero",
            "stroke-linecap" to "butt", "stroke-linejoin" to "miter", "stroke-miterlimit" to "4",
            "stroke-dasharray" to "none", "stroke-dashoffset" to "0",
            "display" to "inline", "visibility" to "visible",
            "clip-path" to "none", "stop-color" to "black", "stop-opacity" to "1",
            "font-family" to "sans-serif", "font-size" to "16", "font-weight" to "normal",
            "font-style" to "normal", "text-anchor" to "start"
        )
        private val inherited = defaults.keys - setOf("opacity", "display", "clip-path", "stop-color", "stop-opacity")
    }
}
