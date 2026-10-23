package club.xiaojiawei.svg

/** Validate before SVGPath, whose public API otherwise logs invalid paths and renders an empty shape. */
internal object SvgPathData {
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    fun validate(data: String) {
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
            val size = when (c.uppercaseChar()) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'A' -> 7
                'Z' -> 0
                else -> throw SvgException("Invalid path command: $c")
            }
            if (size == 0) { command = null; continue }
            for (i in 0 until size) {
                skip()
                if (c.uppercaseChar() == 'A' && i in 3..4) {
                    require(position < data.length && data[position] in "01") { "Invalid arc flag at $position" }
                    position++
                } else {
                    val match = number.find(data, position)
                    require(match != null && match.range.first == position) { "Missing path argument at $position" }
                    val value = match.value.toDouble()
                    require(value.isFinite()) { "Non-finite path coordinate" }
                    if (c.uppercaseChar() == 'A' && i < 2) require(value >= 0) { "Negative arc radius" }
                    position = match.range.last + 1
                }
            }
            if (c == 'M') command = 'L'
            if (c == 'm') command = 'l'
        }
    }
}
