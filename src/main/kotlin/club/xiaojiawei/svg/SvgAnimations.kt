package club.xiaojiawei.svg

import javafx.scene.paint.Color
import javafx.scene.transform.Affine
import java.util.IdentityHashMap

/** Per-renderer animation instances. Shared documents never retain clocks or JavaFX objects. */
internal class SvgAnimations(
    private val document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit
) {
    private data class Binding(val specs: List<SvgAnimation>, val refresh: () -> Unit)
    private val index = IdentityHashMap<SvgElement, MutableList<SvgAnimation>>()
    private val bindings = mutableListOf<Binding>()
    var time = 0.0
        private set
    val size: Int get() = bindings.size
    val hasAnimations: Boolean get() = bindings.any { it.specs.isNotEmpty() }
    val duration: Double get() = bindings.flatMap { it.specs }.maxOfOrNull { it.end.coerceAtLeast(0.0) } ?: 0.0

    init {
        var order = 0
        fun visit(element: SvgElement, parent: SvgElement?) {
            if (element.name in SvgAnimation.NAMES) {
                try {
                    val href = element["href"] ?: element["xlink:href"]
                    val target = if (href == null) parent else {
                        require(href.startsWith('#') && href.length > 1) { "Only local animation targets are supported" }
                        document.elementsById[href.drop(1)] ?: throw SvgException("Missing animation target: $href")
                    }
                    require(target != null && target.name in SvgAnimation.TARGETS) { "Unsupported animation target: " + target?.name }
                    val spec = SvgAnimation.parse(element, order++)
                    if (spec.attribute in SvgAnimation.GEOMETRY) {
                        require(spec.attribute in (SvgAnimatedGeometry.attributes[target.name] ?: emptySet())) {
                            "Unsupported geometry animation on " + target.name + ": " + spec.attribute
                        }
                        require(target !== document.root) { "Root SVG geometry animation is unsupported; use SvgView sizing" }
                        require(element["attributeType"] != "CSS") { "Geometry animation supports XML/auto attributes only" }
                    }
                    require(target.name != "tspan" || spec.transformType == null) { "tspan transform animation is unsupported" }
                    index.getOrPut(target) { mutableListOf() } += spec
                } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid SVG animation", element) }
            }
            element.children.forEach { visit(it, element) }
        }
        visit(document.root, null)
    }

    private fun specs(element: SvgElement): List<SvgAnimation> =
        index[element] ?: element.id?.let { document.elementsById[it]?.let(index::get) } ?: emptyList()

    fun style(
        element: SvgElement, base: Map<String, String>, parent: Map<String, String>,
        inherited: Set<String>, viewport: SvgViewport
    ): Map<String, String> {
        val local = specs(element).filter { it.transformType == null && it.attribute !in SvgAnimation.GEOMETRY }
        val inheritedAnimated = (parent as? SvgAnimatedStyle)?.affected?.intersect(inherited) ?: emptySet()
        if (local.isEmpty() && inheritedAnimated.isEmpty()) return base
        val accepted = mutableListOf<Pair<SvgAnimation, SvgAnimation.Curve>>()
        val colour = try { Color.web(base.getValue("color")) } catch (_: IllegalArgumentException) { null }
        local.forEach { spec ->
            try {
                val curve = spec.Curve(colour, base.getValue("color-interpolation") == "linearRGB")
                val underlying = if (spec.needsBase) SvgAnimatedStyle.baseValue(base, spec.attribute, viewport.diagonal, document.lengths) else null
                curve.sample(.5, viewport.diagonal, underlying)
                accepted += spec to curve
            } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid SVG animation values", spec.element) }
        }
        if (accepted.isNotEmpty()) bindings += Binding(accepted.map { it.first }) {}
        return SvgAnimatedStyle(base, parent as? SvgAnimatedStyle, inherited, accepted, this, document.lengths)
    }

    fun geometry(element: SvgElement, viewport: SvgViewport): SvgAnimatedGeometry {
        val accepted = mutableListOf<Pair<SvgAnimation, SvgAnimation.Curve>>()
        specs(element).filter { it.attribute in SvgAnimation.GEOMETRY }.forEach { spec ->
            try {
                val curve = spec.Curve(null, false)
                val reference = SvgAnimatedGeometry.reference(spec.attribute, viewport)
                val base = if (spec.needsBase) SvgAnimation.Value.Number(SvgAnimatedGeometry.base(element, spec.attribute, viewport, document.lengths)) else null
                curve.sample(.5, reference, base)
                accepted += spec to curve
            } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid geometry animation", spec.element) }
        }
        if (accepted.isNotEmpty()) bindings += Binding(accepted.map { it.first }) {}
        return SvgAnimatedGeometry(element, accepted, this, viewport, document.lengths)
    }

    fun transform(element: SvgElement, source: Affine) {
        val accepted = mutableListOf<Pair<SvgAnimation, SvgAnimation.Curve>>()
        specs(element).filter { it.transformType != null }.forEach { spec ->
            try {
                val curve = spec.Curve(null, false)
                curve.sample(.5, 1.0, null)
                accepted += spec to curve
            } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid transform animation", spec.element) }
        }
        if (accepted.isEmpty()) return
        accepted.sortWith(compareBy({ it.first.begin }, { it.first.order }))
        val base = Affine(source)
        val refresh = {
            val result = Affine(base)
            accepted.forEach { (spec, curve) ->
                spec.progress(time)?.let { progress ->
                    if (!spec.additive) result.setToIdentity()
                    spec.append(result, curve.sample(progress, 1.0, null) as SvgAnimation.Value.Vector)
                }
            }
            source.setToTransform(result)
        }
        bindings += Binding(accepted.map { it.first }) {
            try { refresh() } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid animated transform", element) }
        }
        refresh()
    }

    fun seek(seconds: Double) {
        time = seconds
        bindings.forEach { it.refresh() }
    }

    fun discardFrom(checkpoint: Int) {
        while (bindings.size > checkpoint) bindings.removeAt(bindings.lastIndex)
    }
}

/** Static cascade plus typed animated overrides, including inherited group animations. */
internal class SvgAnimatedStyle(
    private val base: Map<String, String>,
    private val parent: SvgAnimatedStyle?,
    private val inherited: Set<String>,
    private val curves: List<Pair<SvgAnimation, SvgAnimation.Curve>>,
    private val clock: SvgAnimations,
    private val lengths: SvgLengthCache
) : Map<String, String> by base {
    val affected: Set<String> = curves.mapTo(mutableSetOf()) { it.first.attribute }.apply {
        parent?.affected?.filterTo(this) { it in inherited }
    }

    private val byAttribute = curves.groupBy { it.first.attribute }.mapValues { (_, values) ->
        values.sortedWith(compareBy({ it.first.begin }, { it.first.order })).asReversed()
    }

    fun value(name: String, reference: Double): SvgAnimation.Value? {
        val inheritedValue = if (name in inherited) parent?.value(name, reference) else null
        val winner = byAttribute[name]?.firstOrNull { it.first.progress(clock.time) != null } ?: return inheritedValue
        val baseValue = if (winner.first.needsBase) inheritedValue ?: baseValue(base, name, reference, lengths) else null
        return winner.second.sample(checkNotNull(winner.first.progress(clock.time)), reference, baseValue)
    }

    companion object {
        fun baseValue(base: Map<String, String>, name: String, reference: Double, lengths: SvgLengthCache): SvgAnimation.Value? =
            when (name) {
                in SvgAnimation.STYLE_NUMBERS -> SvgAnimation.Value.Number(lengths.resolve(base.getValue(name), reference))
                in SvgAnimation.COLOURS -> {
                    val value = base.getValue(name).let { if (it == "currentColor") base.getValue("color") else it }
                    try { SvgAnimation.Value.Colour(Color.web(value)) } catch (_: IllegalArgumentException) { null }
                }
                else -> null
            }
    }
}

internal fun svgStyleNumber(style: Map<String, String>, name: String, reference: Double, lengths: SvgLengthCache): Double =
    ((style as? SvgAnimatedStyle)?.value(name, reference) as? SvgAnimation.Value.Number)?.value
        ?: lengths.resolve(style.getValue(name), reference)

internal fun svgStyleColour(style: Map<String, String>, name: String): Color? =
    ((style as? SvgAnimatedStyle)?.value(name, 1.0) as? SvgAnimation.Value.Colour)?.value
