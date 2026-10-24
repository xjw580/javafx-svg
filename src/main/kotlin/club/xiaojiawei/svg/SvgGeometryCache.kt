package club.xiaojiawei.svg

import java.util.concurrent.ConcurrentHashMap

/** Document-owned, demand-driven geometry preparation. Stores no nodes, viewports, or exception instances. */
internal class SvgGeometryCache {
    private enum class Kind { PATH, POLYLINE, POLYGON }
    private data class Key(val kind: Kind, val data: String)
    private data class Prepared(val content: String?, val problem: String? = null)
    private val entries = ConcurrentHashMap<Key, Prepared>()

    fun path(data: String): String = content(Key(Kind.PATH, data))

    fun points(data: String, closed: Boolean): String =
        content(Key(if (closed) Kind.POLYGON else Kind.POLYLINE, data))

    private fun content(key: Key): String {
        val prepared = entries.computeIfAbsent(key) {
            try {
                Prepared(when (it.kind) {
                    Kind.PATH -> it.data.also(SvgPathData::validate)
                    Kind.POLYLINE, Kind.POLYGON -> {
                        val points = SvgNumbers.list(it.data)
                        require(points.size % 2 == 0) { "Odd number of polygon coordinates" }
                        points.chunked(2).mapIndexed { index, pair ->
                            (if (index == 0) "M" else "L") + pair.joinToString(" ")
                        }.joinToString(" ") + if (it.kind == Kind.POLYGON && points.isNotEmpty()) " Z" else ""
                    }
                })
            } catch (e: IllegalArgumentException) {
                Prepared(null, e.message ?: "Invalid SVG element")
            }
        }
        // A fresh exception lets the renderer report the current element and preserve strict-mode behavior.
        return prepared.content ?: throw SvgException(checkNotNull(prepared.problem))
    }
}
