package club.xiaojiawei.svg

import javafx.scene.paint.Color
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.transform.Affine

/** Per-instance CSS keyframes; shared stylesheets retain only their immutable declarations. */
internal class SvgCssAnimations private constructor(private val animations: List<Animation>) {
    private data class Animation(val timing: SvgCssTiming, val curves: Map<String, Curve>)
    private data class Point(val offset: Double, val value: Parsed?, val easing: SvgCssEasing?)
    private sealed interface Parsed {
        class Number(val length: SvgLength) : Parsed
        class Colour(val colour: Color) : Parsed
        class Transform(val transform: SvgCssTransform) : Parsed
    }
    private class Curve(val attribute: String, frames: SvgStyleSheet.Keyframes, colour: Color?) {
        val points: List<Point>
        init {
            val values = frames.frames.groupBy { it.offset }.mapValues { (_, framesAtOffset) ->
                framesAtOffset.flatMap { it.declarations }.associate { it.name to it.value }
            }
            fun parse(value: String): Parsed = when (attribute) {
                in SvgAnimation.STYLE_NUMBERS -> Parsed.Number(SvgNumbers.parseLength(value).also {
                    if (attribute == "stroke-width") require(it.resolve(1.0) >= 0) { "Negative CSS animated stroke width" }
                })
                "transform" -> Parsed.Transform(SvgCssTransform.parse(value))
                else -> Parsed.Colour(if (value == "currentColor") requireNotNull(colour) else Color.web(value))
            }
            val result = values.mapNotNull { (offset, properties) ->
                properties[attribute]?.let { Point(offset, parse(it), properties["animation-timing-function"]?.let(SvgCssEasing::parse)) }
            }.sortedBy { it.offset }.toMutableList()
            require(result.isNotEmpty())
            if (result.first().offset != 0.0) result.add(0, Point(0.0, null, values[0.0]?.get("animation-timing-function")?.let(SvgCssEasing::parse)))
            if (result.last().offset != 1.0) result += Point(1.0, null, null)
            points = result
        }

        fun validateBase(base: SvgAnimation.Value?) {
            require(points.none { it.value == null } || base != null) { "CSS animation requires an interpolable source value: $attribute" }
        }
        fun validateTransforms(base: SvgCssTransform) {
            points.zipWithNext().forEach { (a, b) ->
                ((a.value as? Parsed.Transform)?.transform ?: base).validatePair((b.value as? Parsed.Transform)?.transform ?: base)
            }
        }
        private fun segment(progress: Double, easing: SvgCssEasing, before: Boolean): Triple<Point, Point, Double> {
            if (before) { val point = if (progress == 1.0) points.last() else points.first(); return Triple(point, point, 0.0) }
            var index = 0
            while (index < points.size - 2 && progress >= points[index + 1].offset) index++
            val a = points[index]; val b = points[index + 1]
            val ratio = if (b.offset == a.offset) 1.0 else ((progress - a.offset) / (b.offset - a.offset)).coerceIn(0.0, 1.0)
            return Triple(a, b, (a.easing ?: easing).map(ratio))
        }
        fun value(progress: Double, reference: Double, underlying: SvgAnimation.Value?, easing: SvgCssEasing, linearRgb: Boolean, before: Boolean): SvgAnimation.Value {
            fun resolve(point: Point): SvgAnimation.Value = when (val value = point.value) {
                null -> requireNotNull(underlying) { "CSS animation has no source value" }
                is Parsed.Number -> SvgAnimation.Value.Number(value.length.resolve(reference))
                is Parsed.Colour -> SvgAnimation.Value.Colour(value.colour)
                else -> throw SvgException("Transform is not a paint value")
            }
            val (a, b, ratio) = segment(progress, easing, before)
            val result = SvgAnimation.interpolate(resolve(a), resolve(b), ratio, linearRgb)
            return if (attribute == "stroke-width") SvgAnimation.Value.Number((result as SvgAnimation.Value.Number).value.coerceAtLeast(0.0)) else result
        }
        fun transform(progress: Double, base: SvgCssTransform, box: Bounds, origin: Point2D, easing: SvgCssEasing, before: Boolean, output: Affine): Affine {
            val (a, b, ratio) = segment(progress, easing, before)
            val first = (a.value as? Parsed.Transform)?.transform ?: base
            val last = (b.value as? Parsed.Transform)?.transform ?: base
            return first.interpolate(last, ratio, box, origin, output)
        }
    }

    val affected: Set<String> = animations.flatMap { it.curves.keys }.toSet()
    val duration: Double get() = animations.maxOfOrNull { it.timing.end } ?: 0.0
    val hasTransform: Boolean get() = "transform" in affected

    fun value(name: String, time: Double, reference: Double, base: SvgAnimation.Value?, linearRgb: Boolean): SvgAnimation.Value? {
        for (animation in animations.asReversed()) {
            val curve = animation.curves[name] ?: continue
            val progress = animation.timing.progress(time) ?: continue
            return curve.value(progress, reference, base, animation.timing.easing, linearRgb, animation.timing.isBefore(time))
        }
        return null
    }

    fun rotationCacheable(base: SvgCssTransform): Boolean = animations.all { animation ->
        animation.curves["transform"]?.points?.all { ((it.value as? Parsed.Transform)?.transform ?: base).rigid } != false
    }

    fun validateTransformBase(base: SvgCssTransform) {
        animations.forEach { it.curves["transform"]?.validateTransforms(base) }
    }
    fun transform(time: Double, base: SvgCssTransform, box: Bounds, origin: Point2D, output: Affine = Affine()): Affine? {
        for (animation in animations.asReversed()) {
            val curve = animation.curves["transform"] ?: continue
            val progress = animation.timing.progress(time) ?: continue
            return curve.transform(progress, base, box, origin, animation.timing.easing, animation.timing.isBefore(time), output)
        }
        return null
    }

    companion object {
        fun create(
            sheet: SvgStyleSheet, style: Map<String, String>, element: SvgElement,
            conflicts: Set<String>, lengths: SvgLengthCache, reserve: (Int) -> Unit, report: (String, SvgElement?) -> Unit
        ): SvgCssAnimations? {
            if (style["animation-name"]?.trim()?.equals("none", true) != false) return null
            val important = (style as? SvgStyleInfo)?.important ?: emptySet()
            val colour = try { Color.web(style.getValue("color")) } catch (_: IllegalArgumentException) { null }
            val animations = mutableListOf<Animation>()
            val timings = try { SvgCssTiming.parse(style) } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid CSS animation", element); return null }
            for (timing in timings) {
                if (element.name !in SvgAnimation.TARGETS && element.name != "symbol") {
                    report("Unsupported CSS animation target: " + element.name, element)
                    continue
                }
                val frames = sheet.keyframes[timing.name] ?: continue
                val properties = frames.frames.flatMap { it.declarations }.map { it.name }.toSet() - SvgCssTiming.defaults.keys - "animation"
                val curves = mutableMapOf<String, Curve>()
                for (property in properties) {
                    if (property in important) continue
                    try {
                        require(property in SvgAnimation.STYLE_NUMBERS + SvgAnimation.COLOURS + "transform") { "Unsupported CSS keyframe property: $property" }
                        require(property !in conflicts) { "CSS and SMIL animate the same property: $property" }
                        require(property != "transform" || element.name != "tspan") { "CSS tspan transforms are unsupported" }
                        val offsets = frames.frames.filter { frame -> frame.declarations.any { it.name == property } }.map { it.offset }.toSet()
                        reserve(offsets.size + (if (0.0 in offsets) 0 else 1) + (if (1.0 in offsets) 0 else 1))
                        val curve = Curve(property, frames, colour)
                        if (property != "transform") curve.validateBase(SvgAnimatedStyle.baseValue(style, property, 1.0, lengths))
                        else {
                            val base = if ((style as? SvgStyleInfo)?.cssTransform == true) SvgCssTransform.parse(style.getValue("transform"))
                                else SvgCssTransform.fromAffine(SvgTransforms.parse(element["transform"]))
                            curve.validateTransforms(base)
                            SvgCssTransform.Origin(style.getValue("transform-origin"))
                            require(style.getValue("transform-box") in setOf("view-box", "fill-box")) { "Unsupported CSS transform-box" }
                        }
                        curves[property] = curve
                    } catch (e: IllegalArgumentException) { report(e.message ?: "Invalid CSS keyframe values", element) }
                }
                if (curves.isNotEmpty()) animations += Animation(timing, curves.toMap())
            }
            return if (animations.isEmpty()) null else SvgCssAnimations(animations)
        }
    }
}
