package club.xiaojiawei.svg

import club.xiaojiawei.svg.SvgStyleSheet.Compound
import club.xiaojiawei.svg.SvgStyleSheet.Declaration
import club.xiaojiawei.svg.SvgStyleSheet.Specificity
import java.util.IdentityHashMap

/** A deliberately bounded SVG stylesheet engine. JavaFX CSS remains the host application's override layer. */
internal class SvgStyles(
    document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit
) {
    private val sheet = document.styleSheet
    private val lengths = document.lengths
    private data class Winner(val value: String, val important: Boolean, val specificity: Specificity, val order: Int)
    private val elementClasses = IdentityHashMap<SvgElement, Set<String>>()
    // Shared across all styled elements in this renderer, not reset per selector/target.
    // Charge before scans/allocation so hostile sibling chains cannot turn maxElements into
    // unbounded cubic matching work. Loose mode diagnoses once and stops stylesheet matching.
    private var selectorWork = 0L
    private var selectorLimitReported = false
    private class SelectorWorkLimit : RuntimeException()
    private fun selectorCharge(amount: Long) {
        if (selectorLimitReported || amount < 0 || amount > 2_000_000L - selectorWork) throw SelectorWorkLimit()
        selectorWork += amount
    }

    init {
        sheet.reportDiagnostics(report)
    }

    fun compute(element: SvgElement, ancestors: List<SvgElement>, parent: Map<String, String>, inheritedKeys: MutableSet<String>? = null): Map<String, String> {
        val winners = mutableMapOf<String, Winner>()
        var order = 0
        fun add(d: Declaration, specificity: Specificity) {
            if (d.name == "animation") {
                try { SvgCssTiming.expand(d.value).forEach { (name, value) -> add(d.copy(name = name, value = value), specificity) } }
                catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid CSS animation", element) }
                return
            }
            if (d.name in SvgCssTiming.defaults) {
                try { SvgCssTiming.validate(d.name, d.value) }
                catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid CSS animation property", element); return }
            }
            if (d.name in setOf("text-decoration", "baseline-shift") && d.value.trim().lowercase() !in setOf("inherit", "initial", "unset")) {
                try {
                    if (d.name == "text-decoration") SvgTextStyles.decoration(d.value)
                    else SvgTextStyles.validateShift(d.value)
                } catch (failure: IllegalArgumentException) {
                    report(failure.message ?: "Invalid text property", element)
                    return
                }
            }
            if (d.name == "marker") {
                listOf("marker-start", "marker-mid", "marker-end").forEach { add(d.copy(name = it), specificity) }
                return
            }
            val next = Winner(if (d.name in setOf("text-decoration", "baseline-shift")) d.value.trim().lowercase() else d.value, d.important, specificity, order++)
            val old = winners[d.name]
            if (old == null || compareValuesBy(next, old, { it.important }, { it.specificity }, { it.order }) >= 0) {
                winners[d.name] = next
            }
        }
        element.attributes.forEach { (k, v) -> if (k in defaults) add(Declaration(k, v, false), Specificity(0)) }
        if (!selectorLimitReported) try {
            sheet.candidates(element) { classes(element) }.forEach { rule ->
                if (matches(rule.selector, element, ancestors)) {
                    rule.declarations.forEach { add(it, rule.specificity) }
                }
            }
        } catch (_: SelectorWorkLimit) {
            selectorLimitReported = true
            report("SVG stylesheet exceeds 2000000 selector-work limit", element)
        }
        sheet.inline(element, report).forEach { d -> add(d, Specificity(2)) }
        val result = LinkedHashMap<String, String>(defaults.size)
        defaults.forEach { (key, default) ->
            val initial = if (key == "overflow" && element.name in setOf("marker", "pattern")) "hidden" else default
            val value = winners[key]?.value ?: if (key in inherited) parent[key] ?: initial else initial
            if (value == "inherit" || (value == "unset" && key in inherited) || (winners[key] == null && key in inherited)) inheritedKeys?.add(key)
            result[key] = when (value) {
                "inherit" -> parent[key] ?: initial
                "initial" -> initial
                "unset" -> if (key in inherited) parent[key] ?: initial else initial
                else -> value
            }
        }
        if (result["color"] == "currentColor") result["color"] = parent["color"] ?: "black"
        val fontSize = result.getValue("font-size")
        val parentFontSize = parent["font-size"]?.toDoubleOrNull() ?: 16.0
        result["font-size"] = (if (fontSize.endsWith("em")) SvgNumbers.scalar(fontSize.dropLast(2)) * parentFontSize
            else lengths.resolve(fontSize, parentFontSize)).also { require(it >= 0) { "Negative font size" } }.toString()
        result["text-decoration"] = result.getValue("text-decoration").lowercase()
        val baselineShift = result.getValue("baseline-shift")
        if (baselineShift.endsWith("ex", ignoreCase = true)) {
            report("baseline-shift in ex uses a half-em fallback because JavaFX exposes no x-height", element)
        }
        result["baseline-shift"] = SvgTextStyles.computeShift(baselineShift,
            result.getValue("font-size").toDouble(), if (element.name == "text") result.getValue("font-size").toDouble() else parentFontSize, lengths)
        if (result.getValue("vector-effect") != "none" && result.getValue("vector-effect") != "non-scaling-stroke") {
            report("Unsupported vector-effect: ${result.getValue("vector-effect")}", element)
            result["vector-effect"] = "none"
        }
        return SvgComputedStyle(result, winners.filterValues { it.important }.keys.toSet(), winners.keys.toSet(),
            winners["transform"]?.let { it.specificity.level >= 1 } == true)
    }

    private fun matches(selector: List<Compound>, element: SvgElement, ancestors: List<SvgElement>): Boolean {
        // Right-to-left matching needs backtracking: a nearest matching ancestor can fail the
        // remainder while an earlier matching ancestor succeeds. Memoization bounds repeated work.
        data class State(val part: Int, val node: SvgElement, val depth: Int)
        val memo = HashMap<State, Boolean>()
        fun match(part: Int, node: SvgElement, path: List<SvgElement>): Boolean {
            selectorCharge(1L + path.size + selector[part].ids.size + selector[part].classes.size + selector[part].attributes.size)
            val key = State(part, node, path.size)
            memo[key]?.let { return it }
            val answer = if (!matchesCompound(selector[part], node)) false else if (part == 0) true else {
                when (selector[part].combinator) {
                    '>' -> path.lastOrNull()?.let { match(part - 1, it, path.dropLast(1)) } ?: false
                    '+', '~' -> {
                        val siblings = path.lastOrNull()?.children.orEmpty()
                        selectorCharge(siblings.size.toLong())
                        val index = siblings.indexOfFirst { it === node }
                        if (index <= 0) false else if (selector[part].combinator == '+')
                            match(part - 1, siblings[index - 1], path)
                        else (index - 1 downTo 0).any { match(part - 1, siblings[it], path) }
                    }
                    else -> path.indices.reversed().any { match(part - 1, path[it], path.take(it)) }
                }
            }
            memo[key] = answer
            return answer
        }
        // A use instance is a shadow subtree: inherited properties cross its host, complex
        // selectors do not. Ancestors before/including the nearest use were not cloned.
        val shadowBoundary = ancestors.indexOfLast { it.name == "use" }
        selectorCharge(ancestors.size.toLong())
        return match(selector.lastIndex, element, if (shadowBoundary < 0) ancestors else ancestors.drop(shadowBoundary + 1))
    }

    private fun matchesCompound(selector: Compound, element: SvgElement): Boolean {
        selectorCharge(element.name.length.toLong() + selector.ids.sumOf { it.length.toLong() } + selector.classes.sumOf { it.length.toLong() } +
            selector.attributes.sumOf { (it.value?.length ?: 0).toLong() + (element[it.name]?.length ?: 0).toLong() })
        return (selector.type == null || selector.type == element.name) &&
            selector.ids.all { it == element.id } &&
            (selector.classes.isEmpty() || classes(element).containsAll(selector.classes)) &&
            selector.attributes.all { condition ->
                val actual = element[condition.name]
                val wanted = condition.value.orEmpty()
                actual != null && when (condition.operator) {
                    null -> true
                    "=" -> actual == wanted
                    "~=" -> wanted.isNotEmpty() && !wanted.any(Char::isWhitespace) && actual.split(whitespace).contains(wanted)
                    "|=" -> actual == wanted || actual.startsWith("$wanted-")
                    "^=" -> wanted.isNotEmpty() && actual.startsWith(wanted)
                    "$=" -> wanted.isNotEmpty() && actual.endsWith(wanted)
                    "*=" -> wanted.isNotEmpty() && wanted.length <= actual.length && run {
                        // String substring search may compare a long repetitive prefix at every
                        // offset. Bound that worst-case work before calling the platform search.
                        selectorCharge(actual.length.toLong() * wanted.length.toLong())
                        actual.contains(wanted)
                    }
                    else -> false
                }
            }
    }

    private fun classes(element: SvgElement): Set<String> = elementClasses.getOrPut(element) {
        val value = element["class"] ?: return@getOrPut emptySet()
        // Synthetic use roots have distinct identities, even when their attribute strings
        // are shared. Charge each uncached tokenization before allocating its token set.
        selectorCharge(value.length.toLong())
        value.split(whitespace).filter { it.isNotEmpty() }.toSet()
    }

    companion object {
        private val whitespace = Regex("\\s+")
        val defaults = mapOf(
            "fill" to "black", "stroke" to "none", "color" to "black", "color-interpolation" to "sRGB",
            "stroke-width" to "1", "fill-opacity" to "1", "stroke-opacity" to "1", "opacity" to "1",
            "fill-rule" to "nonzero", "clip-rule" to "nonzero",
            "stroke-linecap" to "butt", "stroke-linejoin" to "miter", "stroke-miterlimit" to "4",
            "stroke-dasharray" to "none", "stroke-dashoffset" to "0", "vector-effect" to "none",
            "display" to "inline", "visibility" to "visible", "overflow" to "visible",
            "marker" to "none", "marker-start" to "none", "marker-mid" to "none", "marker-end" to "none",
            "clip-path" to "none", "mask" to "none", "mask-type" to "luminance", "filter" to "none", "color-interpolation-filters" to "linearRGB",
            "stop-color" to "black", "stop-opacity" to "1", "flood-color" to "black", "flood-opacity" to "1", "lighting-color" to "white",
            "font-family" to "sans-serif", "font-size" to "16", "font-weight" to "normal",
            "font-style" to "normal", "text-anchor" to "start", "text-decoration" to "none", "baseline-shift" to "baseline",
            "transform" to "none", "transform-origin" to "0 0", "transform-box" to "view-box", "animation" to "none"
        ) + SvgCssTiming.defaults
        private val inherited = defaults.keys - setOf("text-decoration", "baseline-shift", "opacity", "display", "clip-path", "mask", "mask-type", "filter", "stop-color", "stop-opacity", "flood-color", "flood-opacity", "lighting-color", "vector-effect", "overflow", "transform", "transform-origin", "transform-box", "animation") - SvgCssTiming.defaults.keys
    }
}

internal interface SvgStyleInfo {
    val important: Set<String>
    val specified: Set<String>
    val cssTransform: Boolean
}

internal class SvgComputedStyle(
    private val staticValues: Map<String, String>,
    override val important: Set<String>,
    override val specified: Set<String>,
    override val cssTransform: Boolean
) : AbstractMap<String, String>(), SvgStyleInfo {
    override val entries: Set<Map.Entry<String, String>> get() = staticValues.entries
    override fun get(key: String): String? = staticValues[key]
    override fun containsKey(key: String): Boolean = staticValues.containsKey(key)
}
