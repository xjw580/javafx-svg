package club.xiaojiawei.svg

/** Bounded static selectors. Namespace selectors, escapes and dynamic/functional pseudo-classes
 * are deliberately diagnosed rather than silently reinterpreted. */
internal object SvgCssSelectors {
    fun parse(raw: String): List<SvgStyleSheet.Compound> {
        val text = raw.trim()
        require(text.isNotEmpty())
        var i = 0
        val parts = ArrayList<SvgStyleSheet.Compound>()
        var relation = ' '
        fun ident(): String {
            val start = i
            require(i < text.length && (text[i].isLetter() || text[i] == '_' || text[i] == '-'))
            if (text[i] == '-') require(i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] in "_-"))
            i++
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_-")) i++
            return text.substring(start, i)
        }
        fun spaces(): Boolean {
            val start = i
            while (i < text.length && text[i].isWhitespace()) i++
            return i != start
        }
        while (i < text.length) {
            var type: String? = null
            val ids = ArrayList<String>(); val classes = ArrayList<String>()
            val attrs = ArrayList<SvgStyleSheet.Attribute>()
            var any = false
            if (text[i] == '*') { any = true; i++ }
            else if (text[i].isLetter() || text[i] in "_-") { type = ident(); any = true }
            while (i < text.length) {
                when (text[i]) {
                    '#' -> { i++; ids += ident(); any = true }
                    '.' -> { i++; classes += ident(); any = true }
                    '[' -> {
                        any = true; i++; spaces(); val name = ident(); spaces()
                        var op: String? = null; var value: String? = null
                        if (i < text.length && text[i] != ']') {
                            op = if (text[i] == '=') { i++; "=" } else {
                                require(text[i] in "~|^$*"); val c = text[i++]; require(i < text.length && text[i++] == '='); "$c="
                            }
                            spaces(); require(i < text.length)
                            if (text[i] == '\'' || text[i] == '"') {
                                val quote = text[i++]; val start = i
                                while (i < text.length && text[i] != quote) { require(text[i] != '\\' && text[i] != '\n' && text[i] != '\r'); i++ }
                                require(i < text.length); value = text.substring(start, i); i++
                            } else value = ident()
                            spaces()
                        }
                        require(i < text.length && text[i++] == ']')
                        attrs += SvgStyleSheet.Attribute(name, op, value)
                    }
                    else -> break
                }
            }
            require(any)
            require(parts.size < 128) { "CSS selector exceeds 128 compounds" }
            parts += SvgStyleSheet.Compound(type, ids, classes, attrs, relation)
            val separated = spaces()
            if (i == text.length) break
            relation = if (text[i] in ">+~") text[i++].also { spaces(); require(i < text.length) }
                else { require(separated); ' ' }
        }
        return parts
    }
}
