package club.xiaojiawei.svg

import javafx.geometry.Bounds
import kotlin.math.hypot
import kotlin.math.sqrt

/** Parsed once before source reparenting, and again when a raster is refreshed. */
internal data class SvgLightingParameters(
    val specular: Boolean,
    val surfaceScale: Double,
    val constant: Double,
    val exponent: Double,
    val light: SvgLightingLight,
    val kernelX: Double?,
    val kernelY: Double?
) {
    /** Resolve the primitive coordinate system once; the kernel itself uses a uniform XYZ metric. */
    fun resolved(bounds: Bounds, proportional: Boolean): SvgLightingParameters {
        if (!proportional) return this
        val zUnit = hypot(bounds.width, bounds.height) / sqrt(2.0)
        fun x(value: Double) = bounds.minX + value * bounds.width
        fun y(value: Double) = bounds.minY + value * bounds.height
        fun z(value: Double) = value * zUnit
        val resolvedLight = when (light) {
            SvgLightingLight.None -> light
            is SvgLightingLight.Distant -> light
            is SvgLightingLight.Point -> SvgLightingLight.Point(x(light.x), y(light.y), z(light.z))
            is SvgLightingLight.Spot -> SvgLightingLight.Spot(x(light.x), y(light.y), z(light.z),
                x(light.pointsAtX), y(light.pointsAtY), z(light.pointsAtZ), light.exponent, light.coneAngle)
        }
        // Browser engines keep surfaceScale in the surface/user-space height metric.
        return copy(light = resolvedLight,
            kernelX = kernelX?.times(bounds.width), kernelY = kernelY?.times(bounds.height))
    }

    companion object {
        private val descriptive = setOf("title", "desc", "metadata")
        private val lights = setOf("feDistantLight", "fePointLight", "feSpotLight")
        private fun content(element: SvgElement) = element.children.filter {
            !it.name.startsWith('{') && it.name !in descriptive
        }
        fun parse(primitive: SvgElement): SvgLightingParameters {
            val specular = primitive.name == "feSpecularLighting"
            fun number(element: SvgElement, name: String, default: String = "0") =
                SvgNumbers.scalar(element[name] ?: default)
            val surface = number(primitive, "surfaceScale", "1")
            val constant = number(primitive, if (specular) "specularConstant" else "diffuseConstant", "1")
            require(constant >= 0.0) { "Lighting constant must be non-negative" }
            val exponent = if (specular) number(primitive, "specularExponent", "1") else 1.0
            require(exponent in 1.0..128.0) { "feSpecularLighting specularExponent must be between 1 and 128" }
            val children = content(primitive)
            require(children.all { it.name in lights }) { "Unsupported child of lighting primitive" }
            require(children.all { content(it).isEmpty() }) { "Animated light sources are unsupported" }
            // Like SVG engines, use the first direct light child; an absent light is transparent.
            val element = children.firstOrNull()
            val light = if (element == null) SvgLightingLight.None else when (element.name) {
                "feDistantLight" -> SvgLightingLight.Distant(number(element, "azimuth"), number(element, "elevation"))
                "fePointLight" -> SvgLightingLight.Point(number(element, "x"), number(element, "y"), number(element, "z"))
                else -> {
                    val focus = number(element, "specularExponent", "1")
                    require(focus >= 0.0) { "feSpotLight specularExponent must be non-negative" }
                    SvgLightingLight.Spot(number(element, "x"), number(element, "y"), number(element, "z"),
                        number(element, "pointsAtX"), number(element, "pointsAtY"), number(element, "pointsAtZ"),
                        focus, element["limitingConeAngle"]?.let(SvgNumbers::scalar))
                }
            }
            val kernel = primitive["kernelUnitLength"]?.let(SvgNumbers::list)
            require(kernel == null || kernel.size in 1..2) { "Lighting kernelUnitLength needs one or two values" }
            // Filter Effects 1 specifies the default for a nonpositive kernelUnitLength.
            val validKernel = kernel?.takeIf { values -> values.all { it > 0.0 } }
            return SvgLightingParameters(specular, surface, constant, exponent, light,
                validKernel?.get(0), validKernel?.let { values -> values.getOrElse(1) { values[0] } })
        }
    }
}
