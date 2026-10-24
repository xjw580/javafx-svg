package club.xiaojiawei.svg

import javafx.scene.paint.Color
import javafx.scene.transform.Affine
import kotlin.math.floor
import kotlin.math.pow

/** A bounded SMIL subset. Values are parsed once; no frame values enter document caches. */
internal class SvgAnimation private constructor(
    val element: SvgElement,
    val attribute: String,
    val transformType: String?,
    val begin: Double,
    val duration: Double,
    val repeats: Double,
    val freeze: Boolean,
    val additive: Boolean,
    private val mode: String,
    private val raw: List<String>,
    private val by: Boolean,
    private val times: List<Double>,
    private val splines: List<DoubleArray>,
    val order: Int
) {
    val needsBase: Boolean get() = raw.size == 1
    val end: Double = (begin + duration * repeats).also {
        require((it.isFinite() && (it * 1000).isFinite() && (duration * repeats * 1000).isFinite()) || repeats == Double.POSITIVE_INFINITY) { "Animation duration overflow" }
    }

    /** Null means the animation is not contributing. Endpoints include fractional final repeats. */
    fun progress(time: Double): Double? {
        if (time < begin) return null
        val elapsed = time - begin
        val activeDuration = duration * repeats
        if (elapsed >= activeDuration) {
            if (!freeze) return null
            val partial = repeats - floor(repeats)
            return if (partial == 0.0) 1.0 else partial
        }
        return ((elapsed / duration) % 1.0).coerceIn(0.0, 1.0)
    }

    sealed interface Value {
        data class Number(val value: Double) : Value { init { require(value.isFinite()) { "Non-finite animated number" } } }
        data class Colour(val value: Color) : Value
        data class Vector(val value: DoubleArray) : Value
    }

    /** A per-instance curve; resolves only original lengths against the current viewport. */
    inner class Curve(baseColour: Color?, private val linearRgb: Boolean) {
        private val numbers = if (attribute in NUMBERS) raw.map { SvgNumbers.parseLength(it) } else emptyList()
        private val colours = if (attribute in COLOURS) raw.map {
            if (it == "currentColor") requireNotNull(baseColour) { "Animation currentColor has no colour" } else Color.web(it)
        } else emptyList()
        private val vectors = if (transformType != null) raw.map { vector(it, transformType) } else emptyList()
        // Constant colour/vector endpoints and key positions do not depend on the current viewport.
        private val fixedValues: List<Value> = if (colours.isNotEmpty()) colours.map { Value.Colour(it) }
            else vectors.map { Value.Vector(it) }
        private val knots = if (times.isNotEmpty()) times else {
            val count = maxOf(2, raw.size)
            List(count) { it.toDouble() / (if (mode == "discrete") count else count - 1) }
        }


        init {
            if (attribute in setOf("opacity", "fill-opacity", "stroke-opacity")) {
                require(raw.all { Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?").matches(it) }) {
                    "Opacity animation requires unitless numbers"
                }
            }
            if (attribute in NONNEGATIVE && !by) require(numbers.all { it.resolve(1.0) >= 0 }) { "Negative animated size: $attribute" }
            require(!by || attribute !in COLOURS) { "Colour by-animation is unsupported" }
        }

        fun sample(progress: Double, reference: Double, underlying: Value?): Value {
            val values = if (numbers.isNotEmpty()) numbers.map { Value.Number(it.resolve(reference)) } else fixedValues
            val endpoints = when {
                raw.size == 1 && by -> {
                    val start = underlying ?: zero()
                    listOf(start, add(start, values.single()))
                }
                raw.size == 1 -> listOf(requireNotNull(underlying) { "Animation has no underlying value" }, values.single())
                by -> listOf(values[0], add(values[0], values[1]))
                else -> values
            }
            if (attribute in NONNEGATIVE) require(endpoints.all { (it as Value.Number).value >= 0 }) { "Negative animated size: $attribute" }
            if (progress >= 1) return endpoints.last()
            var index = 0
            while (index < knots.lastIndex && progress >= knots[index + 1]) index++
            if (mode == "discrete" || index == endpoints.lastIndex) return endpoints[index]
            val span = knots[index + 1] - knots[index]
            var ratio = if (span == 0.0) 1.0 else ((progress - knots[index]) / span).coerceIn(0.0, 1.0)
            if (mode == "spline") ratio = spline(splines[index], ratio)
            return interpolate(endpoints[index], endpoints[index + 1], ratio, linearRgb)
        }

        private fun zero(): Value = when {
            attribute in NUMBERS -> Value.Number(0.0)
            transformType != null -> Value.Vector(when (transformType) {
                "scale" -> doubleArrayOf(1.0, 1.0)
                "rotate" -> doubleArrayOf(0.0, 0.0, 0.0)
                else -> doubleArrayOf(0.0, 0.0)
            })
            else -> throw SvgException("No additive colour base")
        }
    }

    fun append(transform: Affine, value: Value.Vector) {
        val n = value.value
        when (transformType) {
            "translate" -> transform.appendTranslation(n[0], n[1])
            "scale" -> transform.appendScale(n[0], n[1])
            "rotate" -> transform.appendRotation(n[0], n[1], n[2])
            "skewX" -> transform.appendShear(kotlin.math.tan(Math.toRadians(n[0])), 0.0)
            "skewY" -> transform.appendShear(0.0, kotlin.math.tan(Math.toRadians(n[0])))
        }
        require(transform.mxx.isFinite() && transform.mxy.isFinite() && transform.tx.isFinite() &&
            transform.myx.isFinite() && transform.myy.isFinite() && transform.ty.isFinite()) {
            "Non-finite animated transform"
        }
    }

    companion object {
        val NAMES = setOf("animate", "animateTransform")
        private val NONNEGATIVE = setOf("stroke-width", "r", "rx", "ry", "width", "height")
        val STYLE_NUMBERS = setOf("opacity", "fill-opacity", "stroke-opacity", "stroke-width", "stroke-dashoffset")
        val GEOMETRY = SvgAnimatedGeometry.attributes.values.flatten().toSet()
        val NUMBERS = STYLE_NUMBERS + GEOMETRY
        val COLOURS = setOf("fill", "stroke", "color")
        val TARGETS = setOf("svg", "g", "a", "use", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "image")
        private val clock = Regex("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(h|min|s|ms)?")

        fun parse(element: SvgElement, order: Int): SvgAnimation {
            val attribute = element["attributeName"] ?: throw SvgException("Animation requires attributeName")
            val type = if (element.name == "animateTransform") element["type"] ?: throw SvgException("animateTransform requires type") else null
            require(if (type == null) attribute in NUMBERS + COLOURS else attribute == "transform") { "Unsupported animated attribute: $attribute" }
            require(type == null || type in setOf("translate", "scale", "rotate", "skewX", "skewY")) { "Unsupported animation transform: $type" }
            require(element["attributeType"] in setOf(null, "auto", "XML", "CSS")) { "Invalid animation attributeType" }
            require(type == null || element["attributeType"] != "CSS") { "animateTransform requires an XML transform" }
            for (unsupported in listOf("end", "min", "max", "repeatDur", "speed", "accelerate", "decelerate", "autoReverse")) {
                require(element[unsupported] == null) { "Unsupported animation timing: $unsupported" }
            }
            require(element["accumulate"] in setOf(null, "none")) { "Accumulated animation is unsupported" }
            require(element["restart"] in setOf(null, "always")) { "Animation restart modes are unsupported" }
            val additive = element["additive"] ?: "replace"
            require(additive in setOf("replace", "sum") && (type != null || additive == "replace")) { "Additive attribute animation is unsupported" }
            val begin = time(element["begin"] ?: "0s")
            val duration = time(element["dur"] ?: throw SvgException("Animation requires a finite dur"))
            require(duration > 0 && (duration * 1000).isFinite()) { "Animation dur must be positive" }
            val repeats = when (val text = element["repeatCount"] ?: "1") {
                "indefinite" -> Double.POSITIVE_INFINITY
                else -> SvgNumbers.scalar(text).also { require(it > 0) { "Invalid animation repeatCount" } }
            }
            val fill = element["fill"] ?: "remove"
            require(fill in setOf("remove", "freeze")) { "Invalid animation fill" }
            val mode = element["calcMode"] ?: "linear"
            require(mode in setOf("linear", "discrete", "spline")) { "Unsupported animation calcMode: $mode" }
            val values = element["values"]?.split(';')?.map(String::trim)
            val by = values == null && element["to"] == null && element["by"] != null
            val raw = values ?: listOfNotNull(element["from"], element["to"] ?: element["by"])
            require(raw.isNotEmpty() && raw.all { it.isNotEmpty() }) { "Animation requires values, to or by" }
            require(values == null || raw.size >= 2) { "Animation values requires at least two values" }
            require(element["from"] == null || raw.size >= 2) { "Animation from requires to or by" }
            require(type == null || values != null || element["from"] != null) { "animateTransform requires explicit values or from" }
            require(type == null || !by) { "Transform by-animation is unsupported; use values or from/to" }
            val count = maxOf(2, raw.size)
            val times = element["keyTimes"]?.split(';')?.map { SvgNumbers.scalar(it.trim()) } ?: emptyList()
            require(times.isEmpty() || (times.size == count && times.first() == 0.0 && times.all { it in 0.0..1.0 } &&
                times.zipWithNext().all { it.first <= it.second } && (mode == "discrete" || times.last() == 1.0))) {
                "Invalid animation keyTimes"
            }
            val splines = element["keySplines"]?.split(';')?.map { SvgNumbers.list(it).toDoubleArray() } ?: emptyList()
            require(mode != "spline" || (splines.size == count - 1 && splines.all { it.size == 4 && it.all { n -> n in 0.0..1.0 } })) {
                "Spline animation requires valid keySplines"
            }
            require(mode == "spline" || splines.isEmpty()) { "keySplines requires calcMode=spline" }
            return SvgAnimation(element, attribute, type, begin, duration, repeats, fill == "freeze", additive == "sum",
                mode, raw, by, times, splines, order)
        }

        fun time(text: String): Double {
            val value = text.trim()
            if (':' in value) {
                val parts = value.split(':')
                require(parts.size in 2..3 && parts.dropLast(1).all { Regex("\\d+").matches(it) }) { "Unsupported animation clock: $text" }
                val seconds = SvgNumbers.scalar(parts.last())
                require(seconds in 0.0..<60.0) { "Invalid animation clock: $text" }
                val minutes = parts[parts.lastIndex - 1].toDouble()
                require(parts.size == 2 || minutes < 60) { "Invalid animation clock: $text" }
                return ((if (parts.size == 3) parts[0].toDouble() * 3600 else 0.0) + minutes * 60 + seconds)
                    .also { require(it.isFinite()) }
            }
            val match = clock.matchEntire(value) ?: throw SvgException("Unsupported animation clock/event: $text")
            return (match.groupValues[1].toDouble() * when (match.groupValues[2]) {
                "h" -> 3_600.0; "min" -> 60.0; "ms" -> .001; else -> 1.0
            }).also { require(it.isFinite()) { "Non-finite animation time" } }
        }

        private fun vector(text: String, type: String): DoubleArray {
            val values = SvgNumbers.list(text)
            require(values.size in when (type) { "rotate" -> setOf(1, 3); "skewX", "skewY" -> setOf(1); else -> setOf(1, 2) }) {
                "Invalid $type animation value"
            }
            return when (type) {
                "rotate" -> doubleArrayOf(values[0], values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
                "scale" -> doubleArrayOf(values[0], values.getOrElse(1) { values[0] })
                else -> doubleArrayOf(values[0], values.getOrElse(1) { 0.0 })
            }
        }

        private fun add(a: Value, b: Value): Value = when {
            a is Value.Number && b is Value.Number -> Value.Number(a.value + b.value)
            a is Value.Vector && b is Value.Vector -> Value.Vector(DoubleArray(a.value.size) { a.value[it] + b.value[it] })
            else -> throw SvgException("Incompatible animation values")
        }

        fun interpolate(a: Value, b: Value, t: Double, linearRgb: Boolean): Value = when {
            a is Value.Number && b is Value.Number -> Value.Number(a.value * (1 - t) + b.value * t)
            a is Value.Vector && b is Value.Vector -> Value.Vector(DoubleArray(a.value.size) { a.value[it] * (1 - t) + b.value[it] * t })
            a is Value.Colour && b is Value.Colour -> Value.Colour(if (!linearRgb) Color((a.value.red * (1 - t) + b.value.red * t).coerceIn(0.0, 1.0), (a.value.green * (1 - t) + b.value.green * t).coerceIn(0.0, 1.0), (a.value.blue * (1 - t) + b.value.blue * t).coerceIn(0.0, 1.0), (a.value.opacity * (1 - t) + b.value.opacity * t).coerceIn(0.0, 1.0)) else {
                fun channel(x: Double, y: Double): Double {
                    fun linear(n: Double) = if (n <= .04045) n / 12.92 else ((n + .055) / 1.055).pow(2.4)
                    val mixed = linear(x) * (1 - t) + linear(y) * t
                    return if (mixed <= .0031308) 12.92 * mixed else 1.055 * mixed.pow(1 / 2.4) - .055
                }
                Color(channel(a.value.red, b.value.red).coerceIn(0.0, 1.0), channel(a.value.green, b.value.green).coerceIn(0.0, 1.0), channel(a.value.blue, b.value.blue).coerceIn(0.0, 1.0),
                    (a.value.opacity * (1 - t) + b.value.opacity * t).coerceIn(0.0, 1.0))
            })
            else -> throw SvgException("Incompatible animation values")
        }

        private fun spline(points: DoubleArray, t: Double): Double {
            fun bezier(a: Double, b: Double, u: Double) = 3 * (1 - u) * (1 - u) * u * a + 3 * (1 - u) * u * u * b + u * u * u
            var low = 0.0
            var high = 1.0
            repeat(28) {
                val middle = (low + high) / 2
                if (bezier(points[0], points[2], middle) < t) low = middle else high = middle
            }
            return bezier(points[1], points[3], (low + high) / 2)
        }
    }
}
