package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.text.Text
import java.util.Collections
import java.util.IdentityHashMap

/** Horizontal text flow. Absolute coordinates start independently anchored chunks. */
internal class SvgTextLayout(private val lengths: SvgLengthCache, private val viewport: SvgViewport) : Group() {
    class Scope(
        val coordinates: Map<String, String>, val geometry: SvgAnimatedGeometry? = null,
        val baselineShift: String = "baseline", val parentFontSize: Double = 16.0,
        val rotations: List<Double> = emptyList(), val textLength: String? = null, val lengthAdjust: String = "spacing"
    ) {
        val lists = coordinates.mapValues { lengthList(it.value) }
        val positioned = rotations.isNotEmpty() || textLength != null || lists.values.any { it.size > 1 }
        init { require(lengthAdjust in setOf("spacing", "spacingAndGlyphs")) { "Invalid lengthAdjust" } }
    }
    class Run(val text: Text, val scopes: List<Scope>, val anchor: Double, val advance: ((Text) -> Double)? = null)
    val runs = mutableListOf<Run>()

    fun position() {
        if (runs.any { run -> run.scopes.any { it.positioned } }) { positionCharacters(); return }
        positionRuns()
    }

    private fun positionRuns() {
        val started = Collections.newSetFromMap(IdentityHashMap<Scope, Boolean>())
        var x = 0.0
        var y = 0.0
        var chunkStart = 0
        var chunkX = 0.0
        var chunkAnchor = 0.0
        fun finish(end: Int) {
            val shift = chunkAnchor * (x - chunkX)
            for (index in chunkStart until end) runs[index].text.x -= shift
        }
        runs.forEachIndexed { index, run ->
            // A scalar applies to the first rendered character in its scope. Inner values win.
            val coordinates = mutableMapOf<String, Double>()
            run.scopes.forEach { scope ->
                if (started.add(scope)) for (name in listOf("x", "y", "dx", "dy")) {
                    val reference = SvgAnimatedGeometry.reference(name, viewport)
                    val value = scope.geometry?.value(name, reference)
                        ?: scope.coordinates[name]?.let { lengths.resolve(it, reference) }
                    if (value != null) coordinates[name] = value
                }
            }
            val absolute = "x" in coordinates || "y" in coordinates
            if (absolute || index == 0) finish(index)
            coordinates["x"]?.let { x = it }
            coordinates["y"]?.let { y = it }
            coordinates["dx"]?.let { x += it }
            coordinates["dy"]?.let { y += it }
            if (absolute || index == 0) {
                chunkStart = index
                chunkX = x
                chunkAnchor = run.anchor
            }
            run.text.x = x
            run.text.y = y - run.scopes.sumOf { SvgTextStyles.shift(it.baselineShift, it.parentFontSize) }
            x += run.text.layoutBounds.width
            require(x.isFinite() && y.isFinite()) { "Non-finite text position" }
        }
        finish(runs.size)
    }

    /** Character-addressed horizontal subset. Nested fixed-length spans are indivisible spacing
     * units for an ancestor's spacing adjustment; glyph scaling composes inside-out. */
    private fun positionCharacters() {
        val n = runs.size
        if (n == 0) return
        val counters = IdentityHashMap<Scope, Int>()
        val xs = arrayOfNulls<Double>(n); val ys = arrayOfNulls<Double>(n)
        val dx = DoubleArray(n); val dy = DoubleArray(n); val angles = DoubleArray(n)
        val advances = DoubleArray(n) { i -> runs[i].advance?.invoke(runs[i].text) ?: runs[i].text.layoutBounds.width }
        val scales = DoubleArray(n) { 1.0 }
        val scopeRuns = linkedMapOf<Scope, MutableList<Int>>()
        runs.forEachIndexed { i, run ->
            run.scopes.forEach { scope ->
                val index = counters[scope] ?: 0
                scopeRuns.getOrPut(scope) { mutableListOf() } += i
                for (name in listOf("x", "y", "dx", "dy")) {
                    val ref = SvgAnimatedGeometry.reference(name, viewport)
                    val value = if (index == 0) scope.geometry?.value(name, ref) ?: scope.lists[name]?.getOrNull(index)?.let { lengths.resolve(it, ref) }
                        else scope.lists[name]?.getOrNull(index)?.let { lengths.resolve(it, ref) }
                    if (value != null) when (name) { "x" -> xs[i] = value; "y" -> ys[i] = value; "dx" -> dx[i] = value; "dy" -> dy[i] = value }
                }
                if (scope.rotations.isNotEmpty()) angles[i] = scope.rotations[minOf(index, scope.rotations.lastIndex)]
                counters[scope] = index + run.text.text.codePointCount(0, run.text.text.length)
            }
        }
        // Preserve authored relative offsets separately from textLength-generated spacing/scaling.
        // An absolute x reset discards prior horizontal length adjustment, but still applies its
        // own authored dx. A y-only reset does not discard horizontal adjustment.
        val authoredDx = dx.copyOf()
        // Inner textLength ranges are resolved before their ancestors (SVG2 11.5 steps 5–6).
        for ((scope, indices) in scopeRuns.entries.toList().asReversed()) {
            val raw = scope.textLength ?: continue
            val target = lengths.resolve(raw, viewport.width)
            require(target.isFinite() && target >= 0) { "Negative or non-finite textLength" }
            val first = indices.first(); val last = indices.last()
            var cursor = 0.0; var left = Double.POSITIVE_INFINITY; var right = Double.NEGATIVE_INFINITY
            for (i in indices) {
                if (i != first) cursor += dx[i]
                left = minOf(left, cursor, cursor + advances[i]); right = maxOf(right, cursor, cursor + advances[i])
                cursor += advances[i]
            }
            val natural = right - left
            // Resolved descendants exclusively own their length adjustment, including glyph scaling.
            val nestedAt = indices.associateWith { i -> runs[i].scopes.dropWhile { it !== scope }.drop(1).firstOrNull { it.textLength != null } }
            val units = mutableListOf<Int>()
            var at = first
            while (at <= last) {
                units += at
                val nested = nestedAt[at]
                at = if (nested == null) at + 1 else scopeRuns.getValue(nested).last() + 1
            }
            if (scope.lengthAdjust == "spacingAndGlyphs") {
                val fixed = indices.sumOf { i ->
                    val nested = nestedAt[i]
                    if (nested == null) 0.0 else advances[i] + if (i != scopeRuns.getValue(nested).first()) dx[i] else 0.0
                }
                val adjustable = natural - fixed
                if (adjustable > 0.0) {
                    require(target >= fixed) { "Conflicting nested textLength leaves a negative adjustable length" }
                    val factor = (target - fixed) / adjustable
                    require(factor.isFinite()) { "Non-finite textLength scale" }
                    indices.forEach { i ->
                        val nested = nestedAt[i]
                        if (nested == null) { advances[i] *= factor; scales[i] *= factor }
                        if (i != first && (nested == null || i == scopeRuns.getValue(nested).first())) dx[i] *= factor
                    }
                } else if (units.size > 1) {
                    val gap = (target - natural) / (units.size - 1)
                    for (start in units.drop(1)) dx[start] += gap
                }
            } else if (units.size > 1) {
                val gap = (target - natural) / (units.size - 1)
                for (start in units.drop(1)) dx[start] += gap
            }
        }
        val px = DoubleArray(n); val py = DoubleArray(n)
        val chunk = BooleanArray(n)
        var x = 0.0; var y = 0.0
        for (i in 0 until n) {
            xs[i]?.let { x = it }; ys[i]?.let { y = it }
            x += if (xs[i] != null) authoredDx[i] else dx[i]; y += dy[i]
            chunk[i] = i == 0 || xs[i] != null || ys[i] != null
            px[i] = x; py[i] = y - runs[i].scopes.sumOf { SvgTextStyles.shift(it.baselineShift, it.parentFontSize) }
            x += advances[i]
            require(listOf(x, y, px[i], py[i], scales[i]).all { it.isFinite() }) { "Non-finite text position" }
        }
        var start = 0
        while (start < n) {
            var end = start + 1
            while (end < n && !chunk[end]) end++
            // SVG2 11.5 step 7 anchors advance extents (not ink bounds), even with negative dx.
            val a = (start until end).minOf { minOf(px[it], px[it] + advances[it]) }
            val b = (start until end).maxOf { maxOf(px[it], px[it] + advances[it]) }
            val shift = px[start] - (a + runs[start].anchor * (b - a))
            for (i in start until end) px[i] += shift
            start = end
        }
        runs.forEachIndexed { i, run ->
            run.text.x = px[i]; run.text.y = py[i]
            run.text.transforms.setAll(javafx.scene.transform.Affine().apply {
                appendTranslation(px[i], py[i]); appendRotation(angles[i]); appendScale(scales[i], 1.0); appendTranslation(-px[i], -py[i])
            })
        }
    }

    companion object {
        fun lengthList(raw: String): List<String> {
            val value = raw.trim()
            if (value.isEmpty()) return emptyList()
            require(!value.startsWith(',') && !value.endsWith(',') && !Regex(",\\s*,").containsMatchIn(value)) { "Invalid text length list" }
            return value.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        }
        fun needsPositioned(element: SvgElement): Boolean = element["rotate"] != null ||
            listOf("x", "y", "dx", "dy").any { name -> element[name]?.let { lengthList(it).size > 1 } == true }

        /** Collapse across element boundaries, keeping each surviving space in its source run. */
        fun normalize(values: List<Pair<String, Boolean>>): List<String> {
            val output = values.map { StringBuilder() }
            var pendingSpace = -1
            var hasText = false
            var lastWasSpace = false
            fun flushSpace() {
                if (pendingSpace >= 0) {
                    output[pendingSpace].append(' ')
                    hasText = true
                    lastWasSpace = true
                    pendingSpace = -1
                }
            }
            values.forEachIndexed { index, (value, preserve) ->
                value.forEach { original ->
                    if (!preserve && (original == '\n' || original == '\r')) return@forEach
                    val c = if (original == '\t' || original == '\n' || original == '\r') ' ' else original
                    if (preserve) {
                        flushSpace()
                        output[index].append(c)
                        hasText = true
                        lastWasSpace = c == ' '
                    } else if (c == ' ') {
                        if (hasText && !lastWasSpace && pendingSpace < 0) pendingSpace = index
                    } else {
                        flushSpace()
                        output[index].append(c)
                        hasText = true
                        lastWasSpace = false
                    }
                }
            }
            return output.map { it.toString() }
        }
    }
}
