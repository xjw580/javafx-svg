package club.xiaojiawei.svg

import javafx.scene.paint.Color
import kotlin.math.*

/** Light coordinates are resolved into the same user-space coordinate system as the surface. */
internal sealed interface SvgLightingLight {
    data object None : SvgLightingLight
    data class Distant(val azimuth: Double = 0.0, val elevation: Double = 0.0) : SvgLightingLight
    data class Point(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) : SvgLightingLight
    data class Spot(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0,
        val pointsAtX: Double = 0.0, val pointsAtY: Double = 0.0, val pointsAtZ: Double = 0.0,
        val exponent: Double = 1.0, val coneAngle: Double? = null) : SvgLightingLight
}

/** Fixed work charge: eight alpha neighbours and normal/light arithmetic; explicit cones add four AA samples. */
internal fun SvgFilterPixels.lightingWork(light: SvgLightingLight? = null, grid: SvgFilterGridPlan? = null): Long =
    (grid?.gridPixels ?: (width.toLong() * height)) *
        (if (light is SvgLightingLight.Spot && light.coneAngle != null) 320L else 160L) + (grid?.resampleWork ?: 0L)

/**
 * SVG Sobel/Phong lighting: https://www.w3.org/TR/filter-effects-1/#feDiffuseLightingElement
 * and https://www.w3.org/TR/filter-effects-1/#feSpecularLightingElement .
 * Only input alpha is read. [originX]/[originY] locate the first pixel center; steps and kernel
 * spacings are in resolved surface coordinates, not device pixels. Explicit kernel spacing first
 * resamples the input onto its implicit grid, shades that grid, then resamples the light map back. The nine edge kernels are expressed as
 * separable differences and smoothing weights, with the specification's normalization factors.
 * [edgeMinX]/[edgeMinY]/[edgeMaxX]/[edgeMaxY] delimit coincident input/output edges
 * for missing-neighbour kernels and duplicate-edge interpolation on an explicit grid.
 * Expanded output regions keep their transparent padding instead of clamping to a smaller input.
 * A one-pixel dimension has zero derivative on that axis (no invented transparent neighbours).
 * Returned RGB is already premultiplied: specular alpha is max(R,G,B), not another multiplier.
 */
internal fun SvgFilterPixels.lighting(
    specular: Boolean,
    surfaceScale: Double,
    constant: Double,
    exponent: Double,
    color: Color,
    outputLinear: Boolean,
    light: SvgLightingLight,
    originX: Double,
    originY: Double,
    stepX: Double,
    stepY: Double,
    kernelX: Double = stepX,
    kernelY: Double = stepY,
    edgeMinX: Int = 0,
    edgeMinY: Int = 0,
    edgeMaxX: Int = width - 1,
    edgeMaxY: Int = height - 1
): SvgFilterPixels {
    require(listOf(surfaceScale, constant, exponent, originX, originY, stepX, stepY, kernelX, kernelY).all { it.isFinite() }) {
        "Non-finite lighting parameter"
    }
    require(constant >= 0.0) { "Lighting constant must be non-negative" }
    require(!specular || exponent in 1.0..128.0) { "Lighting specularExponent must be between 1 and 128" }
    require(stepX > 0.0 && stepY > 0.0 && kernelX > 0.0 && kernelY > 0.0) { "Lighting sample spacing must be positive" }
    require((originX + (width - 1) * stepX).isFinite() && (originY + (height - 1) * stepY).isFinite()) {
        "Non-finite lighting raster coordinates"
    }
    require(edgeMinX in 0..width && edgeMinY in 0..height &&
        edgeMaxX in -1 until width && edgeMaxY in -1 until height &&
        edgeMinX <= edgeMaxX + 1 && edgeMinY <= edgeMaxY + 1) { "Invalid lighting edge bounds" }
    val kx = kernelX / stepX
    val ky = kernelY / stepY
    require(kx.isFinite() && ky.isFinite() && kx > 0.0 && ky > 0.0) { "Invalid lighting kernel spacing" }
    val grid = SvgFilterGridPlan(width, height, kx, ky)
    require(lightingWork(light, grid) <= 200_000_000L) { "Lighting filter exceeds the 200000000 operation work limit" }
    if (!grid.identity && width > 0 && height > 0) {
        // Integer edge indices describe pixel footprints, hence floor(start)/ceil(end) mapping.
        fun lo(edge: Int, scale: Double, size: Int) = floor(edge / scale).toInt().coerceIn(0, size)
        fun hi(edge: Int, scale: Double, size: Int) = (ceil((edge + 1) / scale).toInt() - 1).coerceIn(-1, size - 1)
        val nextOriginX = originX + (kernelX - stepX) * .5
        val nextOriginY = originY + (kernelY - stepY) * .5
        require(nextOriginX.isFinite() && nextOriginY.isFinite()) { "Non-finite lighting grid origin" }
        // Coincident input/output edges delimit the actual height-map footprint. Duplicate
        // those boundary pixels during resampling, otherwise fractional cells invent a bevel
        // from transparent padding outside the input. Expanded output edges retain full bounds.
        val resampled = grid.input(this, edgeMinX, edgeMinY, edgeMaxX, edgeMaxY)
        val shaded = resampled.lighting(specular, surfaceScale, constant, exponent, color, outputLinear,
            light, nextOriginX, nextOriginY, kernelX, kernelY, kernelX, kernelY,
            lo(edgeMinX, kx, grid.gridWidth), lo(edgeMinY, ky, grid.gridHeight),
            hi(edgeMaxX, kx, grid.gridWidth), hi(edgeMaxY, ky, grid.gridHeight))
        return grid.output(shaded)
    }
    val distant: LightingVector?
    val spotDirection: LightingVector?
    when (light) {
        SvgLightingLight.None -> { distant = null; spotDirection = null }
        is SvgLightingLight.Distant -> {
            require(light.azimuth.isFinite() && light.elevation.isFinite()) { "Non-finite distant light" }
            // Reduce before converting to radians so even very large finite angles are safe.
            val azimuth = Math.toRadians(light.azimuth % 360.0)
            val elevation = Math.toRadians(light.elevation % 360.0)
            distant = LightingVector(cos(azimuth) * cos(elevation), sin(azimuth) * cos(elevation), sin(elevation))
            spotDirection = null
        }
        is SvgLightingLight.Point -> {
            require(listOf(light.x, light.y, light.z).all { it.isFinite() }) { "Non-finite point light" }
            distant = null; spotDirection = null
        }
        is SvgLightingLight.Spot -> {
            require(listOf(light.x, light.y, light.z, light.pointsAtX, light.pointsAtY, light.pointsAtZ, light.exponent).all { it.isFinite() }) {
                "Non-finite spot light"
            }
            require(light.exponent >= 0.0) { "Spot light exponent must be non-negative" }
            require(light.coneAngle == null || light.coneAngle.isFinite()) { "Non-finite spot light cone angle" }
            distant = null
            spotDirection = lightingDifference(light.pointsAtX, light.pointsAtY, light.pointsAtZ, light.x, light.y, light.z)
        }
    }
    val antialiasCone = light is SvgLightingLight.Spot && light.coneAngle != null
    if (antialiasCone) require(listOf(originX - .25 * stepX, originY - .25 * stepY,
        (originX + (width - 1) * stepX) + .25 * stepX, (originY + (height - 1) * stepY) + .25 * stepY).all { it.isFinite() }) {
        "Non-finite lighting cone sample coordinates"
    }
    require(lightingWork(light) <= 200_000_000L) { "Lighting filter exceeds the 200000000 operation work limit" }
    val cone = (light as? SvgLightingLight.Spot)?.coneAngle?.let { cos(Math.toRadians(it % 360.0)) }
    fun channel(value: Double) = if (!outputLinear) value else if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    // lighting-color's alpha does not attenuate illumination.
    val red = channel(color.red); val green = channel(color.green); val blue = channel(color.blue)
    val result = SvgFilterPixels(width, height, outputLinear)
    if (light == SvgLightingLight.None) return result
    fun alpha(x: Double, y: Double): Double {
        val xx = x.coerceIn(0.0, (width - 1).toDouble())
        val yy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(xx).toInt(); val y0 = floor(yy).toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = xx - x0; val fy = yy - y0
        val a = data[(y0 * width + x0) * 4 + 3].toDouble()
        val b = data[(y0 * width + x1) * 4 + 3].toDouble()
        val c = data[(y1 * width + x0) * 4 + 3].toDouble()
        val d = data[(y1 * width + x1) * 4 + 3].toDouble()
        return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy).coerceIn(0.0, 1.0)
    }
    for (y in 0 until height) for (x in 0 until width) {
        val xm = x.toDouble() - kx >= edgeMinX.toDouble()
        val xp = x.toDouble() + kx <= edgeMaxX.toDouble()
        val ym = y.toDouble() - ky >= edgeMinY.toDouble()
        val yp = y.toDouble() + ky <= edgeMaxY.toDouble()
        val x0 = if (xm) x - kx else x.toDouble()
        val x1 = if (xp) x + kx else x.toDouble()
        val y0 = if (ym) y - ky else y.toDouble()
        val y1 = if (yp) y + ky else y.toDouble()
        val xSpan = (if (xm) 1 else 0) + (if (xp) 1 else 0)
        val ySpan = (if (ym) 1 else 0) + (if (yp) 1 else 0)
        // Share the 3x3 stencil between both gradients. The center alpha (surface height)
        // is read directly below; all eight other positions use at most four source taps.
        val topLeft = alpha(x0, y0)
        val topCenter = alpha(x.toDouble(), y0)
        val topRight = alpha(x1, y0)
        val middleLeft = alpha(x0, y.toDouble())
        val middleRight = alpha(x1, y.toDouble())
        val bottomLeft = alpha(x0, y1)
        val bottomCenter = alpha(x.toDouble(), y1)
        val bottomRight = alpha(x1, y1)
        var gx = 0.0; var gy = 0.0
        if (xSpan != 0) {
            gx = 2 * (middleRight - middleLeft)
            if (ym) gx += topRight - topLeft
            if (yp) gx += bottomRight - bottomLeft
            gx = gx * (2.0 / (xSpan * (2 + ySpan))) / kernelX
        }
        if (ySpan != 0) {
            gy = 2 * (bottomCenter - topCenter)
            if (xm) gy += bottomLeft - topLeft
            if (xp) gy += bottomRight - topRight
            gy = gy * (2.0 / (ySpan * (2 + xSpan))) / kernelY
        }
        require(gx.isFinite() && gy.isFinite()) { "Lighting surface gradient exceeds numeric range" }
        // Scale before normalizing; avoid overflow from a large finite surfaceScale * gradient.
        val normal = if (surfaceScale == 0.0) LightingVector(0.0, 0.0, 1.0) else {
            val magnitude = max(1.0, abs(surfaceScale))
            lightingNormalize(-gx * (surfaceScale / magnitude), -gy * (surfaceScale / magnitude), 1.0 / magnitude)
        }
        val i = (y * width + x) * 4
        val z = surfaceScale * data[i + 3].toDouble().coerceIn(0.0, 1.0)
        val px = originX + x * stepX; val py = originY + y * stepY
        val direction = distant ?: when (light) {
            is SvgLightingLight.Point -> lightingDifference(light.x, light.y, light.z, px, py, z)
            is SvgLightingLight.Spot -> lightingDifference(light.x, light.y, light.z, px, py, z)
            else -> error("Distant light was prepared")
        }
        val attenuation = if (light is SvgLightingLight.Spot) {
            fun focus(vector: LightingVector): Double {
                val cosine = -vector.dot(spotDirection!!)
                return if (spotDirection.isZero() || vector.isZero() || cosine < 0.0 || (cone != null && cosine < cone)) 0.0
                    else cosine.coerceIn(0.0, 1.0).pow(light.exponent)
            }
            val centerFocus = focus(direction)
            if (antialiasCone) {
                // Bounded 2x2 cone-edge coverage. Subpixel heights are bilinear alpha samples.
                // Keep center shading for fully covered pixels; only mixed cone coverage is
                // averaged. The normal and material Phong response remain center-sampled.
                var sum = 0.0
                var inside = 0
                for (iy in 0..1) for (ix in 0..1) {
                    val dx = if (ix == 0) -.25 else .25
                    val dy = if (iy == 0) -.25 else .25
                    val sampleZ = surfaceScale * alpha(x + dx, y + dy)
                    val sampleDirection = lightingDifference(light.x, light.y, light.z,
                        px + dx * stepX, py + dy * stepY, sampleZ)
                    val cosine = -sampleDirection.dot(spotDirection!!)
                    val covered = !spotDirection.isZero() && !sampleDirection.isZero() && cosine >= 0.0 && cosine >= cone!!
                    if (covered) {
                        inside++
                        sum += cosine.coerceIn(0.0, 1.0).pow(light.exponent)
                    }
                }
                when (inside) { 0 -> 0.0; 4 -> centerFocus; else -> sum * .25 }
            } else centerFocus
        } else if (direction.isZero()) 0.0 else 1.0
        val dot = if (specular) normal.dot(lightingNormalize(direction.x, direction.y, direction.z + 1.0)) else normal.dot(direction)
        val response = if (direction.isZero() || dot <= 0.0 || attenuation == 0.0 || constant == 0.0) 0.0
            else (if (specular) dot.coerceAtMost(1.0).pow(exponent) else dot.coerceAtMost(1.0)) * attenuation
        // Multiply each color before the possibly huge constant; 0 * infinity must never occur.
        fun lit(component: Double): Float = if (component == 0.0 || response == 0.0) 0f
            else (component * response * constant).coerceIn(0.0, 1.0).toFloat()
        val r = lit(red); val g = lit(green); val b = lit(blue)
        result.data[i] = r; result.data[i + 1] = g; result.data[i + 2] = b
        result.data[i + 3] = if (specular) max(r, max(g, b)) else 1f
    }
    return result
}

private data class LightingVector(val x: Double, val y: Double, val z: Double) {
    fun dot(other: LightingVector): Double = x * other.x + y * other.y + z * other.z
    fun isZero(): Boolean = x == 0.0 && y == 0.0 && z == 0.0
}
private fun lightingNormalize(x: Double, y: Double, z: Double): LightingVector {
    val scale = max(abs(x), max(abs(y), abs(z)))
    if (scale == 0.0) return LightingVector(0.0, 0.0, 0.0)
    val xx = x / scale; val yy = y / scale; val zz = z / scale
    val length = sqrt(xx * xx + yy * yy + zz * zz)
    return LightingVector(xx / length, yy / length, zz / length)
}
private fun lightingDifference(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): LightingVector {
    val scale = max(max(abs(ax), abs(bx)), max(max(abs(ay), abs(by)), max(abs(az), abs(bz))))
    if (scale == 0.0) return LightingVector(0.0, 0.0, 0.0)
    return lightingNormalize(ax / scale - bx / scale, ay / scale - by / scale, az / scale - bz / scale)
}
