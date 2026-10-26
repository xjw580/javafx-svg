package club.xiaojiawei.svg

import javafx.scene.paint.Color
import java.util.IdentityHashMap

/** Per-renderer filter animation snapshots. Immutable document elements and style caches stay unchanged. */
internal class SvgFilterAnimation(
    primitives: List<SvgElement>,
    specs: Map<SvgElement, List<SvgAnimation>>,
    private val styleOf: (SvgElement) -> Map<String, String>,
    private val time: () -> Double,
    private val viewport: SvgViewport,
    private val lengths: SvgLengthCache,
    private val proportional: Boolean,
    report: (String, SvgElement?) -> Unit
) {
    private data class Track(val spec: SvgAnimation, val curve: SvgAnimation.Curve, val base: () -> SvgAnimation.Value)
    private val tracks = IdentityHashMap<SvgElement, Map<String, List<Track>>>()
    val isAnimated: Boolean get() = tracks.isNotEmpty()
    val duration: Double get() = tracks.values.flatMap { it.values }.flatten().maxOfOrNull { it.spec.end } ?: 0.0

    init {
        for (primitive in primitives) {
            val style = styleOf(primitive)
            val accepted = ArrayList<Track>()
            for (spec in specs[primitive].orEmpty()) {
                try {
                    require(spec.attribute in ATTRIBUTES[primitive.name].orEmpty()) { "Unsupported animated filter attribute: ${spec.attribute}" }
                    val colour = Color.web(style.getValue("color"))
                    val curve = spec.Curve(colour, style.getValue("color-interpolation") == "linearRGB")
                    val base = underlying(primitive, style, spec.attribute)
                    curve.validationSamples(1.0, base).forEach { validate(spec.attribute, it) }
                    accepted += Track(spec, curve) { underlying(primitive, styleOf(primitive), spec.attribute) }
                } catch (failure: IllegalArgumentException) { report(failure.message ?: "Invalid filter animation", spec.element) }
            }
            if (accepted.isNotEmpty()) tracks[primitive] = accepted.groupBy { it.spec.attribute }.mapValues { (_, list) ->
                list.sortedWith(compareBy<Track>({ it.spec.begin }, { it.spec.order })).asReversed()
            }
        }
    }

    private fun values(primitive: SvgElement): Map<String, String> = tracks[primitive].orEmpty().mapNotNull { (name, list) ->
        val winner = list.firstOrNull { it.spec.progress(time()) != null } ?: return@mapNotNull null
        val value = winner.curve.sample(checkNotNull(winner.spec.progress(time())), 1.0, winner.base())
        validate(name, value)
        name to when(value) {
            is SvgAnimation.Value.Number -> value.value.toString()
            is SvgAnimation.Value.Vector -> value.value.joinToString(" ")
            is SvgAnimation.Value.Colour -> value.value.toString()
        }
    }.toMap()

    fun primitive(original: SvgElement): SvgElement {
        val values = values(original).filterKeys { it !in STYLE }
        val children = original.children.filterNot { it.name in SvgAnimation.NAMES }
        if (values.isEmpty() && children.size == original.children.size) return original
        return SvgElement(original.name, original.attributes + values, children, original.text, original.content)
    }

    fun style(original: SvgElement): Map<String, String> = styleOf(original) + values(original).filterKeys { it in STYLE }

    private fun underlying(primitive: SvgElement, style: Map<String, String>, name: String): SvgAnimation.Value {
        val default = when(name) {
            "stdDeviation", "dx", "dy" -> if (primitive.name == "feDropShadow") "2" else "0"
            "surfaceScale", "diffuseConstant", "specularConstant", "specularExponent" -> "1"
            else -> "0"
        }
        val text = if (name in STYLE) style.getValue(name) else primitive[name] ?: default
        return when(name) {
            in SvgAnimation.FILTER_COLOURS -> SvgAnimation.Value.Colour(Color.web(if (text == "currentColor") style.getValue("color") else text))
            in SvgAnimation.FILTER_VECTORS -> SvgAnimation.Value.Vector(SvgAnimation.filterVector(text))
            "flood-opacity" -> SvgAnimation.Value.Number(SvgNumbers.fraction(text))
            "dx", "dy" -> SvgAnimation.Value.Number(lengths.resolve(text, if (proportional) 1.0 else if (name == "dx") viewport.width else viewport.height))
            else -> SvgAnimation.Value.Number(SvgNumbers.scalar(text))
        }
    }

    private fun validate(name: String, value: SvgAnimation.Value) {
        if (name in setOf("diffuseConstant", "specularConstant")) require((value as SvgAnimation.Value.Number).value >= 0) { "Negative animated lighting constant" }
        if (name == "specularExponent") require((value as SvgAnimation.Value.Number).value in 1.0..128.0) { "Animated specularExponent outside 1..128" }
    }

    companion object {
        val STYLE = setOf("flood-color", "flood-opacity", "lighting-color")
        val ATTRIBUTES = mapOf(
            "feGaussianBlur" to setOf("stdDeviation"),
            "feOffset" to setOf("dx", "dy"),
            "feDropShadow" to setOf("stdDeviation", "dx", "dy", "flood-color", "flood-opacity"),
            "feDisplacementMap" to setOf("scale"),
            "feMorphology" to setOf("radius"),
            "feFlood" to setOf("flood-color", "flood-opacity"),
            "feDiffuseLighting" to setOf("lighting-color", "surfaceScale", "diffuseConstant"),
            "feSpecularLighting" to setOf("lighting-color", "surfaceScale", "specularConstant", "specularExponent")
        )
    }
}
