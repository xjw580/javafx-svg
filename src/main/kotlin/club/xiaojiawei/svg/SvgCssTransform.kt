package club.xiaojiawei.svg

import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.transform.Affine
import kotlin.math.tan

/** Parsed 2D CSS transforms. Compatible primitive lists interpolate without matrix decomposition. */
internal class SvgCssTransform private constructor(private val operations: List<Operation>) {
    private data class Component(val number: Double?, val length: SvgLength?) {
        fun resolve(axis: Double) = number ?: checkNotNull(length).resolve(axis)
    }
    private data class Operation(val kind: String, val components: List<Component>) {
        fun values(box: Bounds) = DoubleArray(components.size) { i -> components[i].resolve(if (i == 0) box.width else box.height) }
        fun identity() = when (kind) {
            "scale" -> doubleArrayOf(1.0, 1.0)
            "matrix" -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            else -> DoubleArray(components.size)
        }
    }

    fun affine(box: Bounds, origin: Point2D, output: Affine = Affine()): Affine = compose(box, origin, null, 0.0, output)

    fun interpolate(other: SvgCssTransform, ratio: Double, box: Bounds, origin: Point2D, output: Affine = Affine()): Affine =
        compose(box, origin, other, ratio, output)

    fun validatePair(other: SvgCssTransform) {
        for (index in 0 until maxOf(operations.size, other.operations.size)) {
            val a = operations.getOrNull(index); val b = other.operations.getOrNull(index)
            require(a == null || b == null || a.kind == b.kind) { "CSS transform lists require compatible 2D primitives" }
            require((a ?: b)?.kind != "matrix") { "Animated CSS matrix decomposition is unsupported" }
        }
    }

    private fun compose(box: Bounds, origin: Point2D, other: SvgCssTransform?, ratio: Double, output: Affine): Affine {
        output.setToIdentity()
        output.appendTranslation(origin.x, origin.y)
        val size = if (other == null) operations.size else maxOf(operations.size, other.operations.size)
        for (index in 0 until size) {
            val a = operations.getOrNull(index)
            val b = other?.operations?.getOrNull(index)
            val op = checkNotNull(a ?: b)
            val n = if (other == null) op.values(box) else {
                val from = a?.values(box) ?: op.identity()
                val to = b?.values(box) ?: op.identity()
                DoubleArray(from.size) { from[it] * (1 - ratio) + to[it] * ratio }
            }
            when (op.kind) {
                "translate" -> output.appendTranslation(n[0], n[1])
                "scale" -> output.appendScale(n[0], n[1])
                "rotate" -> output.appendRotation(n[0])
                "skew" -> output.appendShear(tan(Math.toRadians(n[0])), tan(Math.toRadians(n[1])))
                "matrix" -> output.append(Affine(n[0], n[2], n[4], n[1], n[3], n[5]))
            }
        }
        output.appendTranslation(-origin.x, -origin.y)
        require(output.mxx.isFinite() && output.mxy.isFinite() && output.tx.isFinite() &&
            output.myx.isFinite() && output.myy.isFinite() && output.ty.isFinite()) { "Non-finite CSS transform" }
        return output
    }

    class Origin(text: String) {
        private val x: SvgLength
        private val y: SvgLength
        init {
            val words = SvgCssSyntax.split(text.trim(), null).map { it.lowercase() }.toMutableList()
            require(words.size in 1..3) { "Invalid CSS transform-origin" }
            if (words.size == 3) require(SvgNumbers.parseLength(words.removeAt(2)).resolve(1.0) == 0.0) { "3D transform-origin is unsupported" }
            if (words.size == 1) {
                if (words[0] in setOf("top", "bottom")) words.add(0, "center") else words += "center"
            } else if (words[0] in setOf("top", "bottom") || words[1] in setOf("left", "right")) words.reverse()
            fun coordinate(value: String, horizontal: Boolean): SvgLength {
                val length = when (value) {
                    "center" -> "50%"
                    "left" -> { require(horizontal); "0%" }
                    "right" -> { require(horizontal); "100%" }
                    "top" -> { require(!horizontal); "0%" }
                    "bottom" -> { require(!horizontal); "100%" }
                    else -> value
                }
                return SvgNumbers.parseLength(length)
            }
            x = coordinate(words[0], true); y = coordinate(words[1], false)
        }
        fun point(box: Bounds) = Point2D(box.minX + x.resolve(box.width), box.minY + y.resolve(box.height))
    }

    companion object {
        val NONE = SvgCssTransform(emptyList())
        fun fromAffine(affine: Affine): SvgCssTransform = if (affine.isIdentity) NONE else SvgCssTransform(listOf(
            Operation("matrix", listOf(affine.mxx, affine.myx, affine.mxy, affine.myy, affine.tx, affine.ty).map { Component(it, null) })
        ))
        fun parse(text: String): SvgCssTransform {
            if (text.trim().equals("none", true)) return NONE
            val functions = Regex("([a-zA-Z]+)\\s*\\(([^()]*)\\)")
            val operations = mutableListOf<Operation>()
            var end = 0
            for (match in functions.findAll(text)) {
                require(text.substring(end, match.range.first).isBlank()) { "Invalid CSS transform list" }
                end = match.range.last + 1
                val args = SvgCssSyntax.split(match.groupValues[2].replace(',', ' '), null)
                val type = match.groupValues[1].lowercase()
                fun count(vararg sizes: Int) { require(args.size in sizes) { "Invalid CSS transform arguments: $type" } }
                fun number(value: String) = Component(SvgNumbers.scalar(value), null)
                fun length(value: String) = Component(null, SvgNumbers.parseLength(value.lowercase()))
                fun angle(value: String): Component {
                    val parsed = Regex("(.+?)(deg|rad|grad|turn)?", RegexOption.IGNORE_CASE).matchEntire(value) ?: throw SvgException("Invalid CSS angle")
                    val n = SvgNumbers.scalar(parsed.groupValues[1])
                    require(parsed.groupValues[2].isNotEmpty() || n == 0.0) { "CSS angles require a unit" }
                    return Component(when (parsed.groupValues[2].lowercase()) { "rad" -> Math.toDegrees(n); "grad" -> n * .9; "turn" -> n * 360; else -> n }, null)
                }
                val op = when (type) {
                    "translate" -> { count(1, 2); Operation("translate", listOf(length(args[0]), length(args.getOrElse(1) { "0" }))) }
                    "translatex" -> { count(1); Operation("translate", listOf(length(args[0]), length("0"))) }
                    "translatey" -> { count(1); Operation("translate", listOf(length("0"), length(args[0]))) }
                    "scale" -> { count(1, 2); Operation("scale", listOf(number(args[0]), number(args.getOrElse(1) { args[0] }))) }
                    "scalex" -> { count(1); Operation("scale", listOf(number(args[0]), number("1"))) }
                    "scaley" -> { count(1); Operation("scale", listOf(number("1"), number(args[0]))) }
                    "rotate" -> { count(1); Operation("rotate", listOf(angle(args[0]))) }
                    "skew" -> { count(1, 2); Operation("skew", listOf(angle(args[0]), angle(args.getOrElse(1) { "0" }))) }
                    "skewx" -> { count(1); Operation("skew", listOf(angle(args[0]), angle("0"))) }
                    "skewy" -> { count(1); Operation("skew", listOf(angle("0"), angle(args[0]))) }
                    "matrix" -> { count(6); Operation("matrix", args.map(::number)) }
                    else -> throw SvgException("Unsupported CSS transform: $type")
                }
                operations += op
            }
            require(operations.isNotEmpty() && text.substring(end).isBlank()) { "Invalid CSS transform: $text" }
            return SvgCssTransform(operations)
        }
    }
}
