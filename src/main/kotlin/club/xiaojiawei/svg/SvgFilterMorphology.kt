package club.xiaojiawei.svg

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val MAX_MORPHOLOGY_RADIUS = 256
private const val MAX_MORPHOLOGY_COMPONENT_OPERATIONS = 150_000_000L

/**
 * Conservative component reads/writes in the separable sliding-extrema passes, excluding the
 * input scan. A fresh plan is essential because [SvgFilterPixels.data] is mutable.
 */
internal fun SvgFilterPixels.morphologyWork(radiusX: Double, radiusY: Double): Long =
    morphologyPlan(radiusX, radiusY).componentOperations

/**
 * SVG rectangular erosion/dilation, on premultiplied RGBA in this image's working color space.
 * The caller resolves primitiveUnits and converts to color-interpolation-filters first.
 *
 * The kernel includes pixel centers within the requested radii. Outside the finite input is
 * transparent black, not an edge duplicate. Nonpositive radii disable the primitive, as specified
 * by https://www.w3.org/TR/filter-effects-1/#feMorphologyElement .
 *
 * Two separable monotonic-deque passes are O(width * height), independent of kernel area. At most
 * two RGBA images and one deque of <= 513 integers are allocated, in addition to the input.
 */
internal fun SvgFilterPixels.morphology(
    radiusX: Double,
    radiusY: Double,
    operator: String = "erode"
): SvgFilterPixels {
    require(operator == "erode" || operator == "dilate") { "Unsupported feMorphology operator: $operator" }
    val plan = morphologyPlan(radiusX, radiusY)
    require(plan.componentOperations <= MAX_MORPHOLOGY_COMPONENT_OPERATIONS) {
        "Morphology filter exceeds the $MAX_MORPHOLOGY_COMPONENT_OPERATIONS component-operation work limit"
    }
    if (plan.componentOperations == 0L) return this
    val deque = IntArray(min(max(width, height), 2 * max(plan.radiusX, plan.radiusY) + 1))
    var result = this
    if (plan.radiusX > 0) result = result.morphologyPass(plan.radiusX, true, operator == "dilate", plan.alphaOnly, deque)
    if (plan.radiusY > 0) result = result.morphologyPass(plan.radiusY, false, operator == "dilate", plan.alphaOnly, deque)
    return result
}

private data class MorphologyPlan(
    val radiusX: Int,
    val radiusY: Int,
    val alphaOnly: Boolean,
    val componentOperations: Long
)

private fun SvgFilterPixels.morphologyPlan(radiusX: Double, radiusY: Double): MorphologyPlan {
    require(radiusX.isFinite() && radiusY.isFinite()) { "Non-finite feMorphology radius" }
    val disabled = MorphologyPlan(0, 0, true, 0)
    if (radiusX <= 0.0 || radiusY <= 0.0) return disabled
    // Validate before examining support: even empty images cannot bypass a radius protection.
    require(radiusX <= MAX_MORPHOLOGY_RADIUS && radiusY <= MAX_MORPHOLOGY_RADIUS) {
        "Morphology filter exceeds the $MAX_MORPHOLOGY_RADIUS-pixel kernel radius limit"
    }
    val rx = floor(radiusX).toInt()
    val ry = floor(radiusY).toInt()
    if (rx == 0 && ry == 0) return disabled
    var alphaOnly = true
    var empty = true
    for (i in data.indices step 4) {
        if (data[i] != 0f || data[i + 1] != 0f || data[i + 2] != 0f) alphaOnly = false
        if (data[i + 3] != 0f) empty = false
    }
    if (empty && alphaOnly) return disabled
    val passes = (if (rx > 0) 1 else 0) + (if (ry > 0) 1 else 0)
    // Each component enters once, is compared at most twice amortized, and is read/written once
    // at output. Six component operations per pixel conservatively covers both deque extremes.
    val work = width.toLong() * height * passes * (if (alphaOnly) 1 else 4) * 6
    return MorphologyPlan(rx, ry, alphaOnly, work)
}

private fun SvgFilterPixels.morphologyPass(
    radius: Int,
    horizontal: Boolean,
    dilate: Boolean,
    alphaOnly: Boolean,
    deque: IntArray
): SvgFilterPixels {
    val result = SvgFilterPixels(width, height, linear)
    val length = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val stride = if (horizontal) 4 else width * 4
    val firstChannel = if (alphaOnly) 3 else 0
    for (line in 0 until lines) {
        val base = if (horizontal) line * width * 4 else line * 4
        for (channel in firstChannel..3) {
            var head = 0
            var size = 0
            var next = 0
            for (position in 0 until length) {
                val left = position - radius
                while (size > 0 && deque[head] < left) {
                    head = (head + 1) % deque.size
                    size--
                }
                val right = min(length - 1, position + radius)
                while (next <= right) {
                    val value = data[base + next * stride + channel]
                    while (size > 0) {
                        val last = (head + size - 1) % deque.size
                        val previous = data[base + deque[last] * stride + channel]
                        if (if (dilate) previous > value else previous < value) break
                        size--
                    }
                    deque[(head + size) % deque.size] = next++
                    size++
                }
                // Dilation's maximum includes zero beyond the edge; valid premultiplied inputs
                // are nonnegative. Any off-canvas sample forces erosion's minimum to zero.
                if (dilate || (left >= 0 && position + radius < length)) {
                    result.data[base + position * stride + channel] = data[base + deque[head] * stride + channel]
                }
            }
        }
    }
    return result
}
