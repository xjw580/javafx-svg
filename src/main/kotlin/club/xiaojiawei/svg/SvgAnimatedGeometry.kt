package club.xiaojiawei.svg

/** XML geometry is local to a rendered instance, never inherited as a paint/style property. */
internal class SvgAnimatedGeometry(
    private val element: SvgElement,
    curves: List<Pair<SvgAnimation, SvgAnimation.Curve>>,
    private val clock: SvgAnimations,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache
) {
    val isAnimated: Boolean = curves.isNotEmpty()
    private val byAttribute = curves.groupBy { it.first.attribute }.mapValues { (_, values) ->
        values.sortedWith(compareBy({ it.first.begin }, { it.first.order })).asReversed()
    }

    fun value(name: String, reference: Double): Double? {
        val winner = byAttribute[name]?.firstOrNull { it.first.progress(clock.time) != null } ?: return null
        val underlying = if (winner.first.needsBase) SvgAnimation.Value.Number(base(element, name, viewport, lengths)) else null
        return (winner.second.sample(checkNotNull(winner.first.progress(clock.time)), reference, underlying) as SvgAnimation.Value.Number).value
    }

    fun length(name: String, reference: Double, default: String = "0"): Double =
        value(name, reference) ?: lengths.resolve(element[name] ?: default, reference)

    fun specified(name: String): Boolean = element[name] != null ||
        byAttribute[name]?.any { it.first.progress(clock.time) != null } == true

    companion object {
        val attributes = mapOf(
            "circle" to setOf("cx", "cy", "r"),
            "ellipse" to setOf("cx", "cy", "rx", "ry"),
            "rect" to setOf("x", "y", "width", "height", "rx", "ry"),
            "line" to setOf("x1", "y1", "x2", "y2"),
            "image" to setOf("x", "y", "width", "height"),
            "use" to setOf("x", "y"),
            "svg" to setOf("x", "y", "width", "height"),
            "text" to setOf("x", "y", "dx", "dy"),
            "tspan" to setOf("x", "y", "dx", "dy")
        )
        fun base(element: SvgElement, name: String, viewport: SvgViewport, lengths: SvgLengthCache): Double {
            if (element.name == "rect" && name in setOf("rx", "ry") && element[name] == null) {
                val other = if (name == "rx") "ry" else "rx"
                element[other]?.let { return lengths.resolve(it, reference(other, viewport)) }
            }
            val default = if (element.name == "svg" && name in setOf("width", "height")) "100%" else "0"
            return lengths.resolve(element[name] ?: default, reference(name, viewport))
        }

        fun reference(name: String, viewport: SvgViewport): Double = when (name) {
            "y", "cy", "y1", "y2", "height", "ry", "dy" -> viewport.height
            "r" -> viewport.diagonal
            else -> viewport.width
        }
    }
}
