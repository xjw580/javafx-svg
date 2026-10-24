package club.xiaojiawei.svg

import java.util.concurrent.ConcurrentHashMap

/** An immutable parsed length. Resolution keeps the original arithmetic order and overflow checks. */
internal class SvgLength(private val amount: Double, private val unit: String) {
    fun resolve(reference: Double): Double = when (unit) {
        "%" -> amount * reference / 100
        "in" -> amount * 96
        "cm" -> amount * 96 / 2.54
        "mm" -> amount * 96 / 25.4
        "q" -> amount * 96 / 101.6
        "pt" -> amount * 96 / 72
        "pc" -> amount * 16
        else -> amount
    }.also { require(it.isFinite()) }
}

/** Owned by one document; retains only parsed values, never viewports, nodes or resolved percentages. */
internal class SvgLengthCache {
    private val values = ConcurrentHashMap<String, SvgLength>()

    fun resolve(value: String, reference: Double): Double =
        values.computeIfAbsent(value, SvgNumbers::parseLength).resolve(reference)
}
