package club.xiaojiawei.svg

import javafx.scene.transform.Affine
import javafx.scene.transform.Rotate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

internal object SvgNumbers {
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val lengthPattern = Regex("^($number)(px|%|in|cm|mm|pt|pc|q)?$", RegexOption.IGNORE_CASE)
    fun list(value: String): List<Double> {
        var end = 0
        val result = number.findAll(value).map {
            require(value.substring(end, it.range.first).all { c -> c.isWhitespace() || c == ',' }) {
                "Invalid number list: $value"
            }
            end = it.range.last + 1
            it.value.toDouble().also { n -> require(n.isFinite()) { "Non-finite number" } }
        }.toList()
        require(value.substring(end).all { it.isWhitespace() || it == ',' }) { "Invalid number list: $value" }
        return result
    }

    fun scalar(value: String): Double {
        require(number.matches(value.trim())) { "Invalid number: $value" }
        return value.trim().toDouble().also { require(it.isFinite()) }
    }

    fun length(value: String, reference: Double): Double {
        val match = lengthPattern.matchEntire(value.trim()) ?: throw SvgException("Unsupported length: $value")
        val n = scalar(match.groupValues[1])
        return when (match.groupValues[2].lowercase()) {
            "%" -> n * reference / 100
            "in" -> n * 96
            "cm" -> n * 96 / 2.54
            "mm" -> n * 96 / 25.4
            "q" -> n * 96 / 101.6
            "pt" -> n * 96 / 72
            "pc" -> n * 16
            else -> n
        }.also { require(it.isFinite()) }
    }

    fun fraction(value: String): Double =
        (if (value.trim().endsWith('%')) scalar(value.trim().dropLast(1)) / 100 else scalar(value)).coerceIn(0.0, 1.0)
}

internal object SvgTransforms {
    fun parse(value: String?): Affine {
        val result = Affine()
        if (value.isNullOrBlank()) return result
        var end = 0
        for (match in Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)").findAll(value)) {
            require(value.substring(end, match.range.first).all { it.isWhitespace() || it == ',' }) {
                "Invalid transform: $value"
            }
            end = match.range.last + 1
            val args = SvgNumbers.list(match.groupValues[2])
            fun count(vararg sizes: Int) { require(args.size in sizes) { "Invalid transform arguments: ${match.value}" } }
            when (match.groupValues[1]) {
                "matrix" -> { count(6); result.append(Affine(args[0], args[2], args[4], args[1], args[3], args[5])) }
                "translate" -> { count(1, 2); result.appendTranslation(args[0], args.getOrElse(1) { 0.0 }) }
                "scale" -> { count(1, 2); result.appendScale(args[0], args.getOrElse(1) { args[0] }) }
                "rotate" -> {
                    count(1, 3)
                    result.append(Rotate(args[0], args.getOrElse(1) { 0.0 }, args.getOrElse(2) { 0.0 }))
                }
                "skewX" -> { count(1); result.appendShear(tan(Math.toRadians(args[0])), 0.0) }
                "skewY" -> { count(1); result.appendShear(0.0, tan(Math.toRadians(args[0]))) }
                else -> throw SvgException("Unsupported transform: ${match.groupValues[1]}")
            }
        }
        require(value.substring(end).isBlank()) { "Invalid transform: $value" }
        require(listOf(result.mxx, result.mxy, result.tx, result.myx, result.myy, result.ty).all { it.isFinite() })
        return result
    }
}

internal data class SvgBox(val x: Double, val y: Double, val width: Double, val height: Double) {
    companion object {
        fun parse(value: String): SvgBox {
            val n = SvgNumbers.list(value)
            require(n.size == 4 && n[2] >= 0 && n[3] >= 0) { "Invalid viewBox: $value" }
            return SvgBox(n[0], n[1], n[2], n[3])
        }
    }

    fun transform(width: Double, height: Double, aspect: String?): Affine {
        if (this.width == 0.0 || this.height == 0.0) return Affine(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val tokens = (aspect ?: "xMidYMid meet").trim().split(Regex("\\s+"))
        val align = tokens[0]
        require(tokens.size <= 2 && (tokens.size < 2 || tokens[1] in listOf("meet", "slice"))) {
            "Invalid preserveAspectRatio: $aspect"
        }
        require(align == "none" || Regex("x(Min|Mid|Max)Y(Min|Mid|Max)").matches(align))
        var sx = width / this.width
        var sy = height / this.height
        if (align != "none") {
            val s = if (tokens.getOrNull(1) == "slice") max(sx, sy) else min(sx, sy)
            sx = s; sy = s
        }
        val dx = if (align.contains("xMax")) width - this.width * sx else if (align.contains("xMid")) (width - this.width * sx) / 2 else 0.0
        val dy = if (align.contains("YMax")) height - this.height * sy else if (align.contains("YMid")) (height - this.height * sy) / 2 else 0.0
        return Affine(sx, 0.0, dx - x * sx, 0.0, sy, dy - y * sy)
    }
}
