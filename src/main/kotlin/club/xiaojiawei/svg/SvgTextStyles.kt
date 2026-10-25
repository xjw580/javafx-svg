package club.xiaojiawei.svg

/** SVG 1.1 horizontal text properties, separate from JavaFX's application CSS layer. */
internal object SvgTextStyles {
    private val whitespace = Regex("\\s+")
    private val lines = setOf("underline", "overline", "line-through", "blink")

    fun decoration(value: String): Set<String> {
        val tokens = value.trim().lowercase().split(whitespace)
        if (tokens == listOf("none")) return emptySet()
        require(tokens.isNotEmpty() && tokens.all { it in lines } && tokens.distinct().size == tokens.size) {
            "Unsupported or invalid text-decoration: $value"
        }
        return tokens.toSet()
    }

    fun validateShift(value: String) {
        if (value.trim().lowercase() in setOf("baseline", "sub", "super")) return
        val normalized = value.trim().lowercase()
        if (normalized.endsWith("em") || normalized.endsWith("ex")) SvgNumbers.scalar(normalized.dropLast(2))
        else SvgNumbers.parseLength(normalized)
    }

    /** Percentages use the parent text's line-height (= its font-size in SVG 1.1).
     * Font-relative lengths are computed here so explicit inherit inherits a length, not an expression.
     */
    fun computeShift(value: String, fontSize: Double, parentFontSize: Double, lengths: SvgLengthCache): String {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "baseline", "sub", "super" -> normalized
            else -> (when {
                normalized.endsWith("em") -> SvgNumbers.scalar(normalized.dropLast(2)) * fontSize
                // JavaFX's public Font API exposes no x-height; an ex fallback is diagnosed by the renderer.
                normalized.endsWith("ex") -> SvgNumbers.scalar(normalized.dropLast(2)) * fontSize * .5
                else -> lengths.resolve(normalized, parentFontSize)
            }).also { require(it.isFinite()) { "Non-finite baseline-shift" } }.toString()
        }
    }

    /** JavaFX has no public sub/superscript font metrics. SVG explicitly permits these heuristics. */
    fun shift(value: String, parentFontSize: Double): Double = when (value) {
        "baseline" -> 0.0
        "sub" -> -.3 * parentFontSize
        "super" -> .6 * parentFontSize
        else -> SvgNumbers.scalar(value)
    }
}
