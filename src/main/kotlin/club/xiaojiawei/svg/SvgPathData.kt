package club.xiaojiawei.svg

/** Validate before SVGPath, whose public API otherwise logs invalid paths and renders an empty shape. */
internal object SvgPathData {
    fun validate(data: String) = parse(data, null)

    fun forEach(data: String, consumer: (Char, DoubleArray) -> Unit) = parse(data, consumer)

    private fun parse(data: String, consumer: ((Char, DoubleArray) -> Unit)?) {
        var position = 0
        var command: Char? = null
        var first = true
        fun skip() { while (position < data.length && (data[position].isWhitespace() || data[position] == ',')) position++ }
        while (true) {
            skip()
            if (position == data.length) return
            if (data[position].isLetter()) command = data[position++]
            val c = command ?: throw SvgException("Path requires a command at $position")
            require(!first || c == 'M' || c == 'm') { "Path must start with moveto" }
            first = false
            val upper = c.uppercaseChar()
            val size = when (upper) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'A' -> 7
                'Z' -> 0
                else -> throw SvgException("Invalid path command: $c")
            }
            if (size == 0) { consumer?.invoke(c, DoubleArray(0)); command = null; continue }
            val values = if (consumer != null) DoubleArray(size) else null
            for (i in 0 until size) {
                skip()
                if (upper == 'A' && i in 3..4) {
                    require(position < data.length && data[position] in "01") { "Invalid arc flag at $position" }
                    values?.set(i, (data[position] - '0').toDouble())
                    position++
                } else {
                    val end = numberEnd(data, position)
                    require(end != position) { "Missing path argument at $position" }
                    val value = data.substring(position, end).toDouble()
                    require(value.isFinite()) { "Non-finite path coordinate" }
                    values?.set(i, value)
                    if (upper == 'A' && i < 2) require(value >= 0) { "Negative arc radius" }
                    position = end
                }
            }
            if (values != null) consumer?.invoke(c, values)
            if (c == 'M') command = 'L'
            if (c == 'm') command = 'l'
        }
    }

    /**
     * Scan the SVG number grammar without creating a regex matcher for every coordinate.
     * Keep conversion in Double.parseDouble (via toDouble) so rounding, signed zero and
     * overflow/underflow are identical to the regex-based parser. An incomplete exponent
     * is not part of the number, matching the old optional-exponent backtracking behavior.
     */
    private fun numberEnd(data: String, start: Int): Int {
        var end = start
        val length = data.length
        if (end < length && (data[end] == '+' || data[end] == '-')) end++
        val integerStart = end
        while (end < length && data[end] in '0'..'9') end++
        val hasInteger = end != integerStart
        if (end < length && data[end] == '.') {
            end++
            val fractionStart = end
            while (end < length && data[end] in '0'..'9') end++
            if (!hasInteger && end == fractionStart) return start
        } else if (!hasInteger) {
            return start
        }
        if (end < length && (data[end] == 'e' || data[end] == 'E')) {
            val exponentStart = end++
            if (end < length && (data[end] == '+' || data[end] == '-')) end++
            val exponentDigits = end
            while (end < length && data[end] in '0'..'9') end++
            if (end == exponentDigits) return exponentStart
        }
        return end
    }
}
