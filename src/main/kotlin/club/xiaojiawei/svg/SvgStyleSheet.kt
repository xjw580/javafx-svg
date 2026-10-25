package club.xiaojiawei.svg

/** Published once per document. No nodes, renderer callbacks, or computed/inherited styles are retained. */
internal class SvgStyleSheet private constructor(
    private val idRules: Map<String, List<Rule>>,
    private val classRules: Map<String, List<Rule>>,
    private val typeRules: Map<String, List<Rule>>,
    private val universalRules: List<Rule>,
    private val inlineDeclarations: Map<String, ParsedDeclarations>,
    private val issues: List<Pair<String, SvgElement>>,
    val keyframes: Map<String, Keyframes>
) {
    data class Keyframe(val offset: Double, val declarations: List<Declaration>)
    data class Keyframes(val frames: List<Keyframe>)
    data class Declaration(val name: String, val value: String, val important: Boolean)
    data class Compound(val type: String?, val ids: List<String>, val classes: List<String>)
    data class Rule(
        val selector: List<Compound>, val declarations: List<Declaration>, val specificity: Int, val order: Int
    )
    private data class ParsedDeclarations(val values: List<Declaration>, val issues: List<String>)

    fun reportDiagnostics(report: (String, SvgElement?) -> Unit) {
        issues.forEach { (message, element) -> report(message, element) }
    }

    fun candidates(element: SvgElement, classes: () -> Set<String>): List<Rule> {
        val candidates = ArrayList<Rule>(universalRules)
        element.id?.let { idRules[it]?.let(candidates::addAll) }
        typeRules[element.name]?.let(candidates::addAll)
        if (classRules.isNotEmpty()) {
            classes().forEach { className -> classRules[className]?.let(candidates::addAll) }
        }
        // Restore stylesheet order across index buckets before applying declarations.
        candidates.sortBy { it.order }
        return candidates
    }

    fun inline(element: SvgElement, report: (String, SvgElement?) -> Unit): List<Declaration> {
        val text = element["style"] ?: return emptyList()
        // Synthetic use/symbol elements normally reuse an existing string. Never add them to the document cache.
        val parsed = inlineDeclarations[text] ?: parseDeclarations(text)
        parsed.issues.forEach { report(it, element) }
        return parsed.values
    }

    private class Compiler {
        private val idRules = mutableMapOf<String, MutableList<Rule>>()
        private val classRules = mutableMapOf<String, MutableList<Rule>>()
        private val typeRules = mutableMapOf<String, MutableList<Rule>>()
        private val universalRules = mutableListOf<Rule>()
        private val inlineDeclarations = mutableMapOf<String, ParsedDeclarations>()
        private val issues = mutableListOf<Pair<String, SvgElement>>()
        private var ruleOrder = 0
        private val keyframes = linkedMapOf<String, Keyframes>()

        private fun report(message: String, element: SvgElement) {
            issues += message to element
        }

        fun compile(root: SvgElement, sources: List<SvgStyleSource>, resourceIssues: List<Pair<String, SvgElement>>): SvgStyleSheet {
            issues.addAll(resourceIssues)
            sources.forEach { parseSheet(it.text, it.element) }
            fun visit(element: SvgElement) {
                if (element.name.startsWith('{')) return
                // Parse now, but report inline errors only when this element is actually styled.
                element["style"]?.let { text -> inlineDeclarations.getOrPut(text) { parseDeclarations(text) } }
                element.children.forEach(::visit)
            }
            visit(root)
            return SvgStyleSheet(
                idRules.mapValues { it.value.toList() }, classRules.mapValues { it.value.toList() },
                typeRules.mapValues { it.value.toList() }, universalRules.toList(),
                inlineDeclarations.toMap(), issues.toList(), keyframes.toMap()
            )
        }

        private fun parseSheet(text: String, element: SvgElement) {
            for (block in SvgCssSyntax.rules(text) { report(it, element) }) {
                if (block.header.startsWith('@')) {
                    if (Regex("@keyframes\\s+.*", RegexOption.IGNORE_CASE).matches(block.header)) parseKeyframes(block, element)
                    else report("Unsupported CSS at-rule: " + block.header.substringBefore(' '), element)
                    continue
                }
                if (block.body == null || block.header.isEmpty()) { report("Malformed CSS stylesheet", element); continue }
                val parsed = parseDeclarations(block.body)
                parsed.issues.forEach { report(it, element) }
                val declarations = parsed.values
                for (selector in SvgCssSyntax.split(block.header)) {
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
        }

        private fun parseKeyframes(block: SvgCssSyntax.Rule, element: SvgElement) {
            try {
                val name = SvgCssSyntax.name(block.header.substring(10).trim())
                val body = requireNotNull(block.body) { "Missing CSS keyframes body" }
                val frames = mutableListOf<Keyframe>()
                for (frame in SvgCssSyntax.rules(body) { report(it, element) }) {
                    try {
                        val declarations = parseDeclarations(requireNotNull(frame.body) { "Missing CSS keyframe body" })
                        declarations.issues.forEach { report(it, element) }
                        val values = declarations.values.filterNot { it.important }
                        for (selector in SvgCssSyntax.split(frame.header)) {
                            val offset = when (selector.trim().lowercase()) {
                                "from" -> 0.0; "to" -> 1.0
                                else -> {
                                    require(selector.endsWith('%')) { "Invalid CSS keyframe selector" }
                                    SvgNumbers.scalar(selector.dropLast(1)) / 100
                                }
                            }
                            require(offset in 0.0..1.0) { "CSS keyframe offset outside 0..100%" }
                            frames += Keyframe(offset, values)
                        }
                    } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid CSS keyframe", element) }
                }
                keyframes[name] = Keyframes(frames.toList())
            } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid CSS keyframes", element) }
        }
    }

    companion object {
        private val compound = Regex("(?:\\*|[a-zA-Z_][\\w-]*)?(?:[.#][a-zA-Z_][\\w-]*)*")
        private val comments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val whitespace = Regex("\\s+")
        private val selectorTokens = Regex("([.#])([\\w-]+)")
        private val importantSuffix = Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE)

        fun compile(root: SvgElement, sources: List<SvgStyleSource>, resourceIssues: List<Pair<String, SvgElement>>): SvgStyleSheet =
            Compiler().compile(root, sources, resourceIssues)

        private fun parseDeclarations(text: String): ParsedDeclarations {
            val issues = mutableListOf<String>()
            val declarations = try { SvgCssSyntax.split(SvgCssSyntax.withoutComments(text), ';') }
                catch (e: IllegalArgumentException) { return ParsedDeclarations(emptyList(), listOf(e.message ?: "Malformed CSS declaration")) }
            val values = declarations.mapNotNull { declaration ->
                if (declaration.isBlank()) return@mapNotNull null
                val colon = declaration.indexOf(':')
                if (colon < 1) {
                    issues.add("Invalid CSS declaration: $declaration")
                    return@mapNotNull null
                }
                val name = declaration.substring(0, colon).trim().lowercase()
                if (name !in SvgStyles.defaults) {
                    issues.add("Unsupported SVG CSS property: $name")
                    return@mapNotNull null
                }
                val raw = declaration.substring(colon + 1).trim()
                val important = importantSuffix.containsMatchIn(raw)
                val value = raw.replace(importantSuffix, "").trim()
                if (value.isEmpty()) {
                    issues.add("Empty CSS property: $name")
                    return@mapNotNull null
                }
                Declaration(name, value, important)
            }
            return ParsedDeclarations(values, issues)
        }
    }
}
