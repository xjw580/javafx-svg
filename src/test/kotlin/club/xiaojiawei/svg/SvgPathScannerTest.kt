package club.xiaojiawei.svg

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SvgPathScannerTest {
    @Test fun compactCoordinatesAndArcFlagsKeepTheirExactValues() {
        val commands = mutableListOf<Pair<Char, List<Double>>>()
        SvgPathData.forEach("M.6.5L1e2-3E-2a10 20 30 0110-20z") { command, values ->
            commands += command to values.toList()
        }
        assertEquals(listOf(
            'M' to listOf(0.6, 0.5),
            'L' to listOf(100.0, -0.03),
            'a' to listOf(10.0, 20.0, 30.0, 0.0, 1.0, 10.0, -20.0),
            'z' to emptyList(),
        ), commands)
    }

    @Test fun numericBoundariesAndLegacyDiagnosticsRemainUnchanged() {
        val paths = listOf(
            "", " ,\t\n", "M0 0", "m+1.+2.3-4z", "M.0-.0H-0V+0",
            "M0 0A-0 +0 0 010-0", "M0 0a1 2 30 10-4-.5z",
            "M0 0A-1e-9999 -0e9999999 0 010 0",
            "M0 0L1.7976931348623157e308 2.2250738585072014e-308",
            "M0 0L4.9406564584124654e-324 -1e-9999",
            "M0 0L9007199254740993 0.1000000000000000055511151231257827021181583404541015625",
            "M0 0L0e99999999999999999999999999999999 -0e-9999999999999999999999999",
            "M0\u00a00L1\u20032,3\t4", "M,,0,,,0,,L,,1,,1,,", "M0 0ſ1 2 3 4",
            "0 0", "L0 0", "M", "M.", "M+", "M-. 0", "M0", "M0 0L", "M0 0L1",
            "M0 1e", "M0 1e+", "M0 1e-", "M1e+ 0", "M0 1e+.2", "M0 1E--2",
            "M0 0L1e309 0", "M0 0L-1e309 0", "M0 0LNaN 1", "M0 0LInfinity 1",
            "M0 0L0x1p0 1", "M0 0L١ 2", "M0 0L１ 2", "M0 0L1_000 2",
            "M0 0A-1 2 0 0 1 3 4", "M0 0A1 -2 0 0 1 3 4",
            "M0 0A1 2 0 2 1 3 4", "M0 0A1 2 0 0 -1 3 4", "M0 0A1 2 0 01",
            "M0 0Z1 2", "M0 0X1 2", "M0 0Zz", "M0 0L1.2.3.4", "M0 0\u0000",
        )
        paths.forEach(::assertMatchesLegacy)
        assertEquals("Missing path argument at 2", assertFailsWith<IllegalArgumentException> {
            SvgPathData.validate("M1e+ 0")
        }.message)
        assertEquals("Invalid path command: e", assertFailsWith<SvgException> {
            SvgPathData.validate("M0 1e+")
        }.message)
        assertEquals("Non-finite path coordinate", assertFailsWith<IllegalArgumentException> {
            SvgPathData.validate("M0 0L1e309 0")
        }.message)
        assertEquals("Negative arc radius", assertFailsWith<IllegalArgumentException> {
            SvgPathData.validate("M0 0A-1 2 0 0 1 3 4")
        }.message)
    }

    @Test fun generatedValidPathsAndMutationsMatchLegacyParser() {
        val random = Random(0x5A6C2026)
        val mutations = "MmLlHhVvCcSsQqTtAaZzEeXx0123456789+-. ,\t\n\u00a0\u2003١_"
        repeat(700) {
            val path = generatedPath(random)
            assertEquals(null, outcome(path, SvgPathData::forEach).failure, path)
            assertMatchesLegacy(path)
            repeat(4) { mutation ->
                val offset = random.nextInt(path.length + 1)
                val altered = when (mutation) {
                    0 -> path.take(offset)
                    1 -> path.substring(0, offset) + mutations[random.nextInt(mutations.length)] + path.substring(offset)
                    2 -> if (offset == path.length) path else path.removeRange(offset, offset + 1)
                    else -> if (offset == path.length) path else path.replaceRange(
                        offset, offset + 1, mutations[random.nextInt(mutations.length)].toString(),
                    )
                }
                assertMatchesLegacy(altered)
            }
        }
        repeat(3000) {
            val path = buildString {
                if (random.nextBoolean()) append("M0 0")
                repeat(random.nextInt(70)) { append(mutations[random.nextInt(mutations.length)]) }
            }
            assertMatchesLegacy(path)
        }
    }

    @Test fun longPathsKeepImplicitCommandsAndCallbackOrder() {
        val path = buildString {
            append("m0 0")
            repeat(2000) { append("+1-2") }
            append("zM1 2")
            repeat(1000) { append("C.1.2.3.4.5.6") }
        }
        assertMatchesLegacy(path)
        val commands = mutableListOf<Char>()
        SvgPathData.forEach(path) { command, _ -> commands += command }
        assertEquals(3003, commands.size)
        assertEquals(listOf('m', 'l', 'l'), commands.take(3))
        assertEquals(listOf('l', 'z', 'M', 'C'), commands.subList(2000, 2004))
    }

    private fun generatedPath(random: Random): String = buildString {
        val separators = arrayOf(" ", ",", "\t", "\n", " , ", "\u00a0")
        val commands = "MmLlHhVvCcSsQqTtAa"
        append("M0 0")
        repeat(random.nextInt(1, 12)) {
            val command = commands[random.nextInt(commands.length)]
            val upper = command.uppercaseChar()
            val arity = when (upper) {
                'H', 'V' -> 1
                'C' -> 6
                'S', 'Q' -> 4
                'A' -> 7
                else -> 2
            }
            append(command)
            repeat(random.nextInt(1, 4)) {
                repeat(arity) { argument ->
                    append(separators[random.nextInt(separators.size)])
                    if (upper == 'A' && argument in 3..4) append(random.nextInt(2))
                    else append(randomNumber(random, allowNegative = upper != 'A' || argument >= 2))
                }
            }
        }
        if (random.nextBoolean()) append('z')
    }

    private fun randomNumber(random: Random, allowNegative: Boolean): String {
        val digits = random.nextInt(100000).toString()
        val sign = when (random.nextInt(if (allowNegative) 3 else 2)) { 1 -> "+"; 2 -> "-"; else -> "" }
        val mantissa = when (random.nextInt(5)) {
            0 -> digits
            1 -> "$digits."
            2 -> ".$digits"
            3 -> "0.$digits"
            else -> "$digits.${random.nextInt(10000)}"
        }
        val exponent = if (random.nextBoolean()) "" else {
            val marker = if (random.nextBoolean()) 'e' else 'E'
            val exponentSign = if (random.nextBoolean()) '+' else '-'
            "$marker$exponentSign${random.nextInt(300)}"
        }
        return sign + mantissa + exponent
    }

    private data class Failure(val type: String, val message: String?)
    private data class Segment(val command: Char, val bits: List<Long>)
    private data class Outcome(val segments: List<Segment>, val failure: Failure?)

    private fun outcome(data: String, parser: (String, (Char, DoubleArray) -> Unit) -> Unit): Outcome {
        val segments = mutableListOf<Segment>()
        val failure = failure { parser(data) { command, values -> segments += Segment(command, values.map { it.toBits() }) } }
        return Outcome(segments, failure)
    }

    private fun failure(block: () -> Unit): Failure? = try {
        block()
        null
    } catch (e: RuntimeException) {
        Failure(e.javaClass.name, e.message)
    }

    private fun assertMatchesLegacy(data: String) {
        assertEquals(outcome(data, LegacyRegexPathData::forEach), outcome(data, SvgPathData::forEach), data)
        assertEquals(failure { LegacyRegexPathData.validate(data) }, failure { SvgPathData.validate(data) }, data)
    }
}

// Frozen pre-optimization implementation: compare callbacks, bit-exact doubles, and exception
// types/messages, not merely acceptance, so tokenization changes cannot silently alter diagnostics.
private object LegacyRegexPathData {
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
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
            val size = when (c.uppercaseChar()) {
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
                if (c.uppercaseChar() == 'A' && i in 3..4) {
                    require(position < data.length && data[position] in "01") { "Invalid arc flag at $position" }
                    values?.set(i, (data[position] - '0').toDouble())
                    position++
                } else {
                    val match = number.find(data, position)
                    require(match != null && match.range.first == position) { "Missing path argument at $position" }
                    val value = match.value.toDouble()
                    require(value.isFinite()) { "Non-finite path coordinate" }
                    values?.set(i, value)
                    if (c.uppercaseChar() == 'A' && i < 2) require(value >= 0) { "Negative arc radius" }
                    position = match.range.last + 1
                }
            }
            if (values != null) consumer?.invoke(c, values)
            if (c == 'M') command = 'L'
            if (c == 'm') command = 'l'
        }
    }
}
