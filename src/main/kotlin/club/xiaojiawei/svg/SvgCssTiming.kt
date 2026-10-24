package club.xiaojiawei.svg

import kotlin.math.ceil
import kotlin.math.floor

internal class SvgCssEasing private constructor(private val kind: String, private val values: DoubleArray) {
    fun map(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        if (kind == "linear") return t
        if (kind == "steps") {
            val n = values[0]
            val jump = if (values[1] == 1.0) 1 else 0
            return ((floor(t * n) + jump) / n).coerceIn(0.0, 1.0)
        }
        fun bezier(a: Double, b: Double, u: Double) = 3 * (1 - u) * (1 - u) * u * a + 3 * (1 - u) * u * u * b + u * u * u
        if (t == 0.0 || t == 1.0) return t
        var low = 0.0; var high = 1.0
        repeat(30) {
            val mid = (low + high) / 2
            if (bezier(values[0], values[2], mid) < t) low = mid else high = mid
        }
        return bezier(values[1], values[3], (low + high) / 2)
    }
    companion object {
        fun parse(text: String): SvgCssEasing {
            val value = text.trim().lowercase()
            val predefined = mapOf("ease" to ".25 .1 .25 1", "ease-in" to ".42 0 1 1", "ease-out" to "0 0 .58 1", "ease-in-out" to ".42 0 .58 1")
            if (value == "linear") return SvgCssEasing("linear", doubleArrayOf())
            if (value in predefined) return SvgCssEasing("bezier", SvgNumbers.list(predefined.getValue(value)).toDoubleArray())
            if (value in setOf("step-start", "step-end")) return SvgCssEasing("steps", doubleArrayOf(1.0, if (value == "step-start") 1.0 else 0.0))
            if (value.startsWith("cubic-bezier(") && value.endsWith(')')) {
                val n = SvgNumbers.list(value.substringAfter('(').dropLast(1))
                require(n.size == 4 && n[0] in 0.0..1.0 && n[2] in 0.0..1.0) { "Invalid CSS cubic-bezier" }
                return SvgCssEasing("bezier", n.toDoubleArray())
            }
            if (value.startsWith("steps(") && value.endsWith(')')) {
                val args = SvgCssSyntax.split(value.substringAfter('(').dropLast(1))
                val count = SvgNumbers.scalar(args[0])
                val position = args.getOrElse(1) { "end" }
                require(args.size <= 2 && count >= 1 && count <= Int.MAX_VALUE && count == floor(count) && position in setOf("start", "end", "jump-start", "jump-end")) {
                    "Unsupported CSS steps function"
                }
                return SvgCssEasing("steps", doubleArrayOf(count, if (position in setOf("start", "jump-start")) 1.0 else 0.0))
            }
            throw SvgException("Unsupported CSS timing function: $text")
        }
    }
}

internal data class SvgCssTiming(
    val name: String, val duration: Double, val delay: Double, val iterations: Double,
    val direction: String, val fill: String, val easing: SvgCssEasing, val paused: Boolean
) {
    val end: Double = if (paused) 0.0 else if (iterations == Double.POSITIVE_INFINITY && duration > 0) Double.POSITIVE_INFINITY
        else (delay + duration * if (iterations.isFinite()) iterations else 0.0).coerceAtLeast(0.0).also {
            require(it.isFinite() && (it * 1000).isFinite()) { "CSS animation duration overflow" }
        }

    fun isBefore(documentTime: Double): Boolean = (if (paused) 0.0 else documentTime) < delay

    fun progress(documentTime: Double): Double? {
        val time = if (paused) 0.0 else documentTime
        val elapsed = time - delay
        fun directed(progress: Double, iteration: Double): Double {
            val odd = iteration % 2.0 >= 1.0
            val reverse = direction == "reverse" || direction == "alternate" && odd || direction == "alternate-reverse" && !odd
            return if (reverse) 1 - progress else progress
        }
        if (elapsed < 0) return if (fill in setOf("backwards", "both")) directed(0.0, 0.0) else null
        val activeDuration = if (duration == 0.0 || iterations == 0.0) 0.0 else duration * iterations
        if (elapsed >= activeDuration) {
            if (fill !in setOf("forwards", "both")) return null
            if (iterations == 0.0) return directed(0.0, 0.0)
            val count = if (iterations.isFinite()) iterations else 1.0
            val part = count - floor(count)
            return directed(if (part == 0.0) 1.0 else part, (ceil(count) - 1).coerceAtLeast(0.0))
        }
        val cycles = elapsed / duration
        return directed(cycles % 1.0, floor(cycles))
    }

    companion object {
        val defaults = linkedMapOf(
            "animation-name" to "none", "animation-duration" to "0s", "animation-timing-function" to "ease",
            "animation-delay" to "0s", "animation-iteration-count" to "1", "animation-direction" to "normal",
            "animation-fill-mode" to "none", "animation-play-state" to "running"
        )
        private val timePattern = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(ms|s)")
        fun seconds(text: String): Double {
            if (text == "0") return 0.0
            val match = timePattern.matchEntire(text.lowercase()) ?: throw SvgException("Invalid CSS animation time: $text")
            return (SvgNumbers.scalar(match.groupValues[1]) * if (match.groupValues[2] == "ms") .001 else 1.0)
                .also { require((it * 1000).isFinite()) { "CSS animation time overflow" } }
        }
        private fun count(text: String): Double = if (text.lowercase() == "infinite") Double.POSITIVE_INFINITY
            else SvgNumbers.scalar(text).also { require(it >= 0) { "Negative CSS iteration count" } }

        fun validate(name: String, value: String) {
            if (value in setOf("inherit", "initial", "unset")) return
            for (item in SvgCssSyntax.split(value)) {
                require(item.isNotEmpty()) { "Empty CSS animation list item" }
                when (name) {
                    "animation-name" -> if (!item.equals("none", true)) SvgCssSyntax.name(item)
                    "animation-duration" -> require(seconds(item) >= 0) { "Negative CSS animation duration" }
                    "animation-delay" -> seconds(item)
                    "animation-iteration-count" -> count(item)
                    "animation-timing-function" -> SvgCssEasing.parse(item)
                    "animation-direction" -> require(item.lowercase() in setOf("normal", "reverse", "alternate", "alternate-reverse")) { "Invalid CSS animation direction" }
                    "animation-fill-mode" -> require(item.lowercase() in setOf("none", "forwards", "backwards", "both")) { "Invalid CSS animation fill-mode" }
                    "animation-play-state" -> require(item.lowercase() in setOf("running", "paused")) { "Invalid CSS animation play-state" }
                }
            }
        }

        fun expand(value: String): Map<String, String> {
            if (value in setOf("inherit", "initial", "unset")) return defaults.mapValues { value }
            val animations = SvgCssSyntax.split(value).map { item ->
                val result = defaults.toMutableMap()
                val seen = mutableSetOf<String>()
                for (token in SvgCssSyntax.split(item, null)) {
                    val lower = token.lowercase()
                    val key = when {
                        timePattern.matches(lower) || lower == "0" -> if ("animation-duration" !in seen) "animation-duration" else "animation-delay"
                        (lower in setOf("linear", "ease", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end") || lower.startsWith("cubic-bezier(") || lower.startsWith("steps(")) && "animation-timing-function" !in seen -> "animation-timing-function"
                        (lower == "infinite" || lower.toDoubleOrNull() != null) && "animation-iteration-count" !in seen -> "animation-iteration-count"
                        lower in setOf("normal", "reverse", "alternate", "alternate-reverse") && "animation-direction" !in seen -> "animation-direction"
                        lower in setOf("none", "forwards", "backwards", "both") && "animation-fill-mode" !in seen -> "animation-fill-mode"
                        lower in setOf("running", "paused") && "animation-play-state" !in seen -> "animation-play-state"
                        else -> "animation-name"
                    }
                    require(seen.add(key)) { "Duplicate CSS animation shorthand component: $token" }
                    result[key] = if (key == "animation-name") token else lower
                }
                require(seen.isNotEmpty()) { "Empty CSS animation shorthand" }
                result.forEach(::validate)
                result
            }
            return defaults.mapValues { (key, _) -> animations.joinToString(", ") { it.getValue(key) } }
        }

        fun parse(style: Map<String, String>): List<SvgCssTiming> {
            val lists = defaults.mapValues { (key, default) -> SvgCssSyntax.split(style[key] ?: default) }
            val names = lists.getValue("animation-name")
            return names.mapIndexedNotNull { index, name ->
                if (name.equals("none", true)) return@mapIndexedNotNull null
                fun item(key: String): String = lists.getValue(key).let { it[index % it.size] }
                SvgCssTiming(SvgCssSyntax.name(name), seconds(item("animation-duration")), seconds(item("animation-delay")),
                    count(item("animation-iteration-count")), item("animation-direction").lowercase(), item("animation-fill-mode").lowercase(),
                    SvgCssEasing.parse(item("animation-timing-function")), item("animation-play-state").equals("paused", true))
            }
        }
    }
}
