package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.transform.Affine
import java.util.IdentityHashMap

/** Per-renderer animation instances. Shared documents never retain clocks or JavaFX objects. */
internal class SvgAnimations(
    private val document: SvgDocument,
    private val report: (String, SvgElement?) -> Unit,
    private val requestLayout: () -> Unit
) {
    private data class Binding(val end: Double?, val transform: Boolean = false, val requiresLayout: Boolean = !transform, val cacheTarget: Node? = null, val cacheBlocked: Boolean = false, val refresh: () -> Unit)
    private val index = IdentityHashMap<SvgElement, MutableList<SvgAnimation>>()
    private val motions = IdentityHashMap<SvgElement, MutableList<SvgMotion>>()
    private val bindings = mutableListOf<Binding>()
    private var cssPointsRemaining = document.options.maxElements
    var time = 0.0
        private set
    val size: Int get() = bindings.size
    val cacheTargets: List<Node> get() = if (bindings.any { it.cacheBlocked }) emptyList() else bindings.mapNotNull { it.cacheTarget }.distinct()
    val hasAnimations: Boolean get() = bindings.any { it.end != null }
    val transformsOnly: Boolean get() = hasAnimations && bindings.none { it.requiresLayout }
    val duration: Double get() = bindings.mapNotNull { it.end }.maxOfOrNull { it.coerceAtLeast(0.0) } ?: 0.0

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
                    if (element.name == "animateMotion") {
                        require(target.name != "tspan") { "tspan motion animation is unsupported" }
                        motions.getOrPut(target) { mutableListOf() } += SvgMotion.create(element, document, order++)
                        return
                    }
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
        val css = SvgCssAnimations.create(document.styleSheet, base, element, specs(element).map { it.attribute }.toSet(), document.lengths, { points ->
            require(points <= cssPointsRemaining) { "CSS expanded keyframe limit exceeded" }
            cssPointsRemaining -= points
        }, report)
        if (local.isEmpty() && inheritedAnimated.isEmpty() && css == null) return base
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
        if (accepted.isNotEmpty()) bindings += Binding(accepted.maxOf { it.first.end }) {}
        if (css != null) bindings += Binding(css.duration, requiresLayout = css.affected.any { it != "transform" }) {}
        return SvgAnimatedStyle(base, parent as? SvgAnimatedStyle, inherited, accepted, this, document.lengths, css)
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
        if (accepted.isNotEmpty()) bindings += Binding(accepted.maxOf { it.first.end }) {}
        return SvgAnimatedGeometry(element, accepted, this, viewport, document.lengths)
    }

    fun transform(element: SvgElement, source: Affine, style: Map<String, String>, viewport: SvgViewport, node: Node, isRoot: Boolean) {
        val accepted = mutableListOf<Pair<SvgAnimation, SvgAnimation.Curve>>()
        specs(element).filter { it.transformType != null }.forEach { spec ->
            try {
                val curve = spec.Curve(null, false)
                curve.sample(.5, 1.0, null)
                accepted += spec to curve
            } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid transform animation", spec.element) }
        }
        val css = (style as? SvgAnimatedStyle)?.css
        val info = style as? SvgStyleInfo
        var cssEnabled = info?.cssTransform == true || css?.hasTransform == true || "transform-origin" in (info?.specified ?: emptySet())
        val motionTracks = motions[element] ?: element.id?.let { document.elementsById[it]?.let(motions::get) } ?: emptyList()
        if (accepted.isEmpty() && !cssEnabled && motionTracks.isEmpty()) {
            if (style.getValue("transform-box") != "view-box" && style.getValue("transform-box") != "fill-box") {
                report("Unsupported CSS transform-box", element)
            }
            return
        }
        var cssBase = SvgCssTransform.fromAffine(source)
        var origin = SvgCssTransform.Origin("0 0")
        try {
            if (info?.cssTransform == true) cssBase = SvgCssTransform.parse(style.getValue("transform"))
            origin = SvgCssTransform.Origin(if (isRoot && "transform-origin" !in (info?.specified ?: emptySet())) "50% 50%" else style.getValue("transform-origin"))
            require(style.getValue("transform-box") in setOf("view-box", "fill-box")) { "Unsupported CSS transform-box" }
            css?.validateTransformBase(cssBase)
        } catch (failure: IllegalArgumentException) {
            report(failure.message ?: "Invalid CSS transform", element)
            cssEnabled = false
        }
        val motion = motionTracks.sortedWith(compareBy({ it.timing.begin }, { it.timing.order }))
        if (accepted.isEmpty() && !cssEnabled && motion.isEmpty()) return
        accepted.sortWith(compareBy({ it.first.begin }, { it.first.order }))
        val base = Affine(source)
        if (cssEnabled && !isRoot && style.getValue("transform-box") == "fill-box") {
            node.boundsInLocalProperty().addListener { _, _, _ -> requestLayout() }
        }
        val result = Affine()
        val motionResult = Affine()
        val refresh = {
            if (cssEnabled) {
                val box = if (!isRoot && style.getValue("transform-box") == "fill-box") svgGeometryBounds(node)
                    else BoundingBox(viewport.x, viewport.y, viewport.width, viewport.height)
                val pivot = origin.point(box)
                css?.transform(time, cssBase, box, pivot, result) ?: cssBase.affine(box, pivot, result)
            } else result.setToTransform(base)
            accepted.forEach { (spec, curve) ->
                spec.progress(time)?.let { progress ->
                    if (!spec.additive) result.setToIdentity()
                    spec.append(result, curve.sample(progress, 1.0, null) as SvgAnimation.Value.Vector)
                }
            }
            motionResult.setToIdentity()
            for (track in motion) {
                if (track.timing.progress(time) == null) continue
                if (!track.additive) motionResult.setToIdentity()
                track.append(time, motionResult)
            }
            result.append(motionResult)
            source.setToTransform(result)
        }
        val animated = accepted.isNotEmpty() || motion.isNotEmpty() || css?.hasTransform == true
        val cacheable = accepted.all { it.first.transformType in setOf("rotate", "translate") } && css?.rotationCacheable(cssBase) != false
        bindings += Binding((accepted.map { it.first.end } + motion.map { it.timing.end }).maxOrNull(), transform = true,
            requiresLayout = cssEnabled && !isRoot && style.getValue("transform-box") == "fill-box",
            cacheTarget = if (animated && cacheable) node else null, cacheBlocked = animated && !cacheable) {
            try { refresh() } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid animated transform", element) }
        }
        refresh()
    }

    fun seek(seconds: Double) {
        time = seconds
    }

    fun refreshTransforms() {
        bindings.forEach { if (it.transform) it.refresh() }
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
    private val lengths: SvgLengthCache,
    val css: SvgCssAnimations? = null
) : Map<String, String> by base, SvgStyleInfo {
    override val important: Set<String> get() = (base as? SvgStyleInfo)?.important ?: emptySet()
    override val specified: Set<String> get() = (base as? SvgStyleInfo)?.specified ?: emptySet()
    override val cssTransform: Boolean get() = (base as? SvgStyleInfo)?.cssTransform == true
    val affected: Set<String> = curves.mapTo(mutableSetOf()) { it.first.attribute }.apply {
        parent?.affected?.filterTo(this) { it in inherited }
        css?.affected?.let(::addAll)
    }

    private val byAttribute = curves.groupBy { it.first.attribute }.mapValues { (_, values) ->
        values.sortedWith(compareBy({ it.first.begin }, { it.first.order })).asReversed()
    }

    fun value(name: String, reference: Double): SvgAnimation.Value? {
        val inheritedValue = if (name in inherited) parent?.value(name, reference) else null
        val winner = byAttribute[name]?.firstOrNull { it.first.progress(clock.time) != null }
        val smilBase = if (winner?.first?.needsBase == true) inheritedValue ?: baseValue(base, name, reference, lengths) else null
        val value = winner?.let { it.second.sample(checkNotNull(it.first.progress(clock.time)), reference, smilBase) } ?: inheritedValue
        if (css == null || name !in css.affected || name == "transform") return value
        return css.value(name, clock.time, reference, value ?: baseValue(base, name, reference, lengths),
            base.getValue("color-interpolation") == "linearRGB") ?: value
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
