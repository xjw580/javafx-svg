package club.xiaojiawei.svg

import javafx.geometry.BoundingBox
import javafx.geometry.Bounds

/** Shared SVG filter/primitive coordinate resolution; does not apply owner transforms. */
internal fun svgFilterRegion(
    element: SvgElement, proportional: Boolean, bounds: Bounds, fallback: Bounds?,
    viewport: SvgViewport, lengths: SvgLengthCache
): Bounds {
    fun value(name: String, horizontal: Boolean, position: Boolean): Double {
        val raw = element[name]
        if (raw == null && fallback != null) return when (name) {
            "x" -> fallback.minX; "y" -> fallback.minY; "width" -> fallback.width; else -> fallback.height
        }
        val reference = if (horizontal) viewport.width else viewport.height
        val amount = lengths.resolve(raw ?: if (position) "-10%" else "120%", if (proportional) 1.0 else reference)
        val extent = if (horizontal) bounds.width else bounds.height
        val origin = if (horizontal) bounds.minX else bounds.minY
        return if (proportional) amount * extent + if (position) origin else 0.0 else amount
    }
    val x = value("x", true, true); val y = value("y", false, true)
    val width = value("width", true, false); val height = value("height", false, false)
    require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) { "Non-finite filter region" }
    require(width >= 0 && height >= 0) { "Negative filter region" }
    return BoundingBox(x, y, width, height)
}

internal fun svgFilterProportional(value: String): Boolean = when (value) {
    "objectBoundingBox" -> true
    "userSpaceOnUse" -> false
    else -> throw SvgException("Invalid filter coordinate units: " + value)
}
