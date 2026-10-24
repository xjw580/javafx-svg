package club.xiaojiawei.svg

/** Small balanced CSS scanner. Unknown at-rules can be skipped without consuming adjacent rules. */
internal object SvgCssSyntax {
    data class Rule(val header: String, val body: String?)
    fun withoutComments(text: String, report: ((String) -> Unit)? = null): String {
        val out = StringBuilder()
        var quote = '\u0000'; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                out.append(c)
                if (c == '\\' && i + 1 < text.length) out.append(text[++i]) else if (c == quote) quote = '\u0000'
            } else if (c == '\'' || c == '"') { quote = c; out.append(c) }
            else if (c == '/' && text.getOrNull(i + 1) == '*') {
                val end = text.indexOf("*/", i + 2)
                if (end < 0 && report != null) { report("Unclosed CSS comment"); break }
                require(end >= 0) { "Unclosed CSS comment" }
                out.append(' '); i = end + 1
            } else out.append(c)
            i++
        }
        return out.toString()
    }

    fun split(text: String, separator: Char? = ','): List<String> {
        val result = mutableListOf<String>()
        var quote = '\u0000'; var depth = 0; var start = 0; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == '\\') i++ else if (c == quote) quote = '\u0000'
            } else when {
                c == '\'' || c == '"' -> quote = c
                c == '(' -> depth++
                c == ')' -> { depth--; require(depth >= 0) { "Unbalanced CSS function" } }
                depth == 0 && (if (separator == null) c.isWhitespace() else c == separator) -> {
                    val part = text.substring(start, i).trim()
                    if (separator != null || part.isNotEmpty()) result += part
                    start = i + 1
                }
            }
            i++
        }
        require(depth == 0 && quote == '\u0000') { "Unbalanced CSS value" }
        val tail = text.substring(start).trim()
        if (separator != null || tail.isNotEmpty()) result += tail
        return result
    }

    fun rules(text: String, report: (String) -> Unit): List<Rule> {
        val source = try { withoutComments(text, report) } catch (e: IllegalArgumentException) { report(e.message!!); return emptyList() }
        val result = mutableListOf<Rule>()
        var start = 0; var quote = '\u0000'; var parentheses = 0; var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quote != '\u0000') {
                if (c == '\\') i++ else if (c == quote) quote = '\u0000'
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> parentheses++
                ')' -> parentheses--
                '}' -> if (parentheses == 0) { report("Unexpected CSS closing brace"); start = i + 1 }
                ';' -> if (parentheses == 0) { result += Rule(source.substring(start, i).trim(), null); start = i + 1 }
                '{' -> if (parentheses == 0) {
                    val header = source.substring(start, i).trim()
                    val body = i + 1
                    var depth = 1; i++
                    while (i < source.length && depth > 0) {
                        val b = source[i]
                        if (quote != '\u0000') { if (b == '\\') i++ else if (b == quote) quote = '\u0000' }
                        else when (b) { '\'', '"' -> quote = b; '{' -> depth++; '}' -> depth-- }
                        i++
                    }
                    if (depth != 0) { report("Unclosed CSS rule: $header"); return result }
                    result += Rule(header, source.substring(body, i - 1))
                    start = i; i--
                }
            }
            i++
        }
        if (source.substring(start).isNotBlank()) report("Malformed CSS stylesheet")
        return result
    }

    fun name(text: String): String {
        val value = text.trim()
        val plain = if (value.length >= 2 && value.first() in "\"'" && value.last() == value.first()) value.substring(1, value.lastIndex) else value
        require(Regex("-?[_a-zA-Z][_a-zA-Z0-9-]*").matches(plain) && plain.lowercase() !in setOf("initial", "inherit", "unset", "revert", "none")) {
            "Unsupported CSS animation name: $text"
        }
        return plain
    }
}
