package club.xiaojiawei.svg

import javafx.scene.shape.*
import kotlin.math.*

/** Immutable, bounded arc-length polyline. No scene-graph objects are retained. */
internal class SvgPathMeasure(data: String, limit: Int, transform: String? = null) {
    data class Point(val x: Double, val y: Double)
    data class Sample(val x: Double, val y: Double, val angle: Double)
    private data class Segment(val a: Point, val b: Point, val start: Double, val end: Double)
    private val segments = mutableListOf<Segment>()
    private val commandEnds = mutableListOf<Double>()
    private var first = Point(0.0, 0.0)
    var length = 0.0
        private set
    val size: Int get() = segments.size
    val fractions: List<Double> get() = if (length == 0.0 || commandEnds.isEmpty()) listOf(0.0, 1.0)
        else listOf(0.0) + commandEnds.map { it / length }

    init {
        var point = Point(0.0, 0.0)
        var start = point
        var moved = false
        fun line(a: Point, b: Point) {
            require(a.x.isFinite() && a.y.isFinite() && b.x.isFinite() && b.y.isFinite()) { "Non-finite measured path" }
            val size = hypot(b.x - a.x, b.y - a.y)
            require(size.isFinite() && (length + size).isFinite()) { "Path length overflow" }
            if (size > 0) {
                require(segments.size < limit) { "Measured path exceeds segment budget" }
                segments += Segment(a, b, length, length + size)
                length += size
            }
        }
        fun middle(a: Point, b: Point) = Point(a.x / 2 + b.x / 2, a.y / 2 + b.y / 2)
        fun cubic(a: Point, b: Point, c: Point, d: Point, depth: Int) {
            val chord = hypot(d.x - a.x, d.y - a.y)
            val polygon = hypot(b.x - a.x, b.y - a.y) + hypot(c.x - b.x, c.y - b.y) + hypot(d.x - c.x, d.y - c.y)
            require(polygon.isFinite()) { "Path curve overflow" }
            if (depth >= 18 || polygon - chord <= .005) { line(a, d); return }
            val ab = middle(a, b); val bc = middle(b, c); val cd = middle(c, d)
            val abc = middle(ab, bc); val bcd = middle(bc, cd); val centre = middle(abc, bcd)
            cubic(a, ab, abc, centre, depth + 1); cubic(centre, bcd, cd, d, depth + 1)
        }
        fun arc(a: Point, e: ArcTo) {
            val b = Point(e.x, e.y)
            if (a == b) return
            var rx = abs(e.radiusX); var ry = abs(e.radiusY)
            if (rx == 0.0 || ry == 0.0) { line(a, b); return }
            val phi = Math.toRadians(e.xAxisRotation % 360)
            val co = cos(phi); val si = sin(phi)
            val dx = (a.x - b.x) / 2; val dy = (a.y - b.y) / 2
            var ux = (co * dx + si * dy) / rx; var uy = (-si * dx + co * dy) / ry
            val scale = hypot(ux, uy)
            if (scale > 1) { rx *= scale; ry *= scale; ux /= scale; uy /= scale }
            val squared = ux * ux + uy * uy
            val factor = (if (e.isLargeArcFlag == e.isSweepFlag) -1 else 1) * sqrt(max(0.0, (1 - squared) / squared))
            val cx = factor * uy; val cy = -factor * ux
            val centreX = co * rx * cx - si * ry * cy + a.x / 2 + b.x / 2
            val centreY = si * rx * cx + co * ry * cy + a.y / 2 + b.y / 2
            val begin = atan2(uy - cy, ux - cx)
            var sweep = atan2(-uy - cy, -ux - cx) - begin
            if (e.isSweepFlag && sweep < 0) sweep += 2 * PI
            if (!e.isSweepFlag && sweep > 0) sweep -= 2 * PI
            val step = min(PI / 8, max(.00001, 2 * acos((1 - .01 / max(rx, ry)).coerceIn(-1.0, 1.0))))
            val count = ceil(abs(sweep) / step).toInt().coerceAtLeast(1)
            require(count <= limit - segments.size) { "Measured arc exceeds segment budget" }
            var previous = a
            for (i in 1..count) {
                val angle = begin + sweep * i / count
                val next = if (i == count) b else Point(centreX + co * rx * cos(angle) - si * ry * sin(angle),
                    centreY + si * rx * cos(angle) + co * ry * sin(angle))
                line(previous, next); previous = next
            }
        }
        val raw = SvgStrokeGeometry.path(data, limit.coerceAtLeast(1))
        val elements = if (transform == null) raw else SvgStrokeGeometry.transform(raw, SvgTransforms.parse(transform))
        for (e in elements) {
            when (e) {
                is MoveTo -> { point = Point(e.x, e.y); start = point; if (!moved) { first = point; moved = true }; continue }
                is LineTo -> line(point, Point(e.x, e.y))
                is CubicCurveTo -> cubic(point, Point(e.controlX1, e.controlY1), Point(e.controlX2, e.controlY2), Point(e.x, e.y), 0)
                is QuadCurveTo -> {
                    val control = Point(e.controlX, e.controlY); val end = Point(e.x, e.y)
                    cubic(point, Point(point.x / 3 + control.x * (2.0 / 3), point.y / 3 + control.y * (2.0 / 3)),
                        Point(end.x / 3 + control.x * (2.0 / 3), end.y / 3 + control.y * (2.0 / 3)), end, 0)
                }
                is ArcTo -> arc(point, e)
                is ClosePath -> line(point, start)
                else -> throw SvgException("Unsupported measured path command")
            }
            point = when (e) {
                is LineTo -> Point(e.x, e.y)
                is CubicCurveTo -> Point(e.x, e.y)
                is QuadCurveTo -> Point(e.x, e.y)
                is ArcTo -> Point(e.x, e.y)
                else -> start
            }
            commandEnds += length
        }
    }

    fun at(distance: Double): Sample {
        require(distance.isFinite())
        if (segments.isEmpty()) return Sample(first.x, first.y, 0.0)
        val d = distance.coerceIn(0.0, length)
        var low = 0; var high = segments.lastIndex
        while (low < high) { val mid = (low + high) ushr 1; if (segments[mid].end <= d) low = mid + 1 else high = mid }
        val segment = segments[low]
        val ratio = ((d - segment.start) / (segment.end - segment.start)).coerceIn(0.0, 1.0)
        return Sample(segment.a.x * (1 - ratio) + segment.b.x * ratio, segment.a.y * (1 - ratio) + segment.b.y * ratio,
            Math.toDegrees(atan2(segment.b.y - segment.a.y, segment.b.x - segment.a.x)))
    }
}
