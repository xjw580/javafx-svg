package club.xiaojiawei.svg

import javafx.scene.Group
import javafx.scene.text.Text
import java.util.Collections
import java.util.IdentityHashMap

/** Horizontal text flow. Absolute coordinates start independently anchored chunks. */
internal class SvgTextLayout(private val lengths: SvgLengthCache, private val viewport: SvgViewport) : Group() {
    class Scope(
        val coordinates: Map<String, String>, val geometry: SvgAnimatedGeometry? = null,
        val baselineShift: String = "baseline", val parentFontSize: Double = 16.0
    )
    class Run(val text: Text, val scopes: List<Scope>, val anchor: Double)
    val runs = mutableListOf<Run>()

    fun position() {
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

    companion object {
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
