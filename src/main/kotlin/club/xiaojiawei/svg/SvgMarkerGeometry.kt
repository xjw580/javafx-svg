package club.xiaojiawei.svg

import javafx.geometry.Point2D
import javafx.scene.shape.*
import javafx.scene.transform.Transform
import kotlin.math.*

/** Vertices of the equivalent path; start/end refer to the entire path, not each subpath. */
internal object SvgMarkerGeometry {
    data class Vertex(val point: Point2D, val incoming: Point2D?, val outgoing: Point2D?) {
        fun angle(transform: Transform? = null): Double {
            fun direction(vector: Point2D): Double {
                val v = transform?.deltaTransform(vector) ?: vector
                return Math.toDegrees(atan2(v.y, v.x))
            }
            val a = incoming?.let(::direction)
            val b = outgoing?.let(::direction)
            if (a == null) return b ?: 0.0
            if (b == null) return a
            var difference = (b - a) % 360
            if (difference > 180) difference -= 360
            if (difference <= -180) difference += 360
            return a + difference / 2
        }
    }
    private data class Segment(val start: Point2D?, val end: Point2D?)
    private class Subpath(val offset: Int, val points: MutableList<Point2D>, var closed: Boolean = false)

    fun key(shape: Shape): Any = when (shape) {
        is SVGPath -> shape.content
        is Line -> listOf(shape.startX, shape.startY, shape.endX, shape.endY)
        is Rectangle -> listOf(shape.x, shape.y, shape.width, shape.height, shape.arcWidth, shape.arcHeight)
        is Circle -> listOf(shape.centerX, shape.centerY, shape.radius)
        is Ellipse -> listOf(shape.centerX, shape.centerY, shape.radiusX, shape.radiusY)
        else -> throw SvgException("Markers require a path or basic shape")
    }

    fun vertices(shape: Shape, limit: Int, basicShape: Boolean = shape !is SVGPath): List<Vertex> {
        val elements = when (shape) {
            is Circle -> ellipse(shape.centerX, shape.centerY, shape.radius, shape.radius)
            is Ellipse -> ellipse(shape.centerX, shape.centerY, shape.radiusX, shape.radiusY)
            else -> SvgStrokeGeometry.centerline(shape, limit)
        }
        val paths = mutableListOf<Subpath>()
        val segments = mutableListOf<Segment>()
        var current: Subpath? = null
        var point = Point2D.ZERO
        var count = 0
        fun addPoint(value: Point2D) {
            require(++count <= limit) { "SVG marker vertex limit exceeded" }
            require(value.x.isFinite() && value.y.isFinite()) { "Non-finite marker vertex" }
            checkNotNull(current).points += value
        }
        fun begin(value: Point2D) {
            current = Subpath(segments.size, mutableListOf()).also { paths += it }
            addPoint(value)
            point = value
        }
        fun vector(from: Point2D, to: Point2D): Point2D? =
            to.subtract(from).takeUnless { it.x == 0.0 && it.y == 0.0 }?.also {
                require(it.x.isFinite() && it.y.isFinite()) { "Non-finite marker tangent" }
            }
        elements.forEach { element ->
            if (element is MoveTo) {
                begin(Point2D(element.x, element.y))
            } else {
                if (current == null) begin(point)
                val start = point
                var a: Point2D? = null
                var b: Point2D? = null
                val end = when (element) {
                    is LineTo -> Point2D(element.x, element.y).also { a = vector(start, it); b = a }
                    is CubicCurveTo -> Point2D(element.x, element.y).also {
                        val c1 = Point2D(element.controlX1, element.controlY1)
                        val c2 = Point2D(element.controlX2, element.controlY2)
                        a = vector(start, c1) ?: vector(start, c2) ?: vector(start, it)
                        b = vector(c2, it) ?: vector(c1, it) ?: vector(start, it)
                    }
                    is QuadCurveTo -> Point2D(element.x, element.y).also {
                        val c = Point2D(element.controlX, element.controlY)
                        a = vector(start, c) ?: vector(start, it)
                        b = vector(c, it) ?: vector(start, it)
                    }
                    is ArcTo -> {
                        val value = Point2D(element.x, element.y)
                        if (value == start) return@forEach
                        val tangents = arcTangents(start, element)
                        a = tangents.first; b = tangents.second
                        value
                    }
                    is ClosePath -> {
                        val value = checkNotNull(current).points.first()
                        current?.closed = true
                        if (basicShape && start == value) {
                            current = null
                            return@forEach
                        }
                        a = vector(start, value); b = a
                        value
                    }
                    else -> throw SvgException("Unsupported marker path element")
                }
                segments += Segment(a, b)
                addPoint(end)
                point = end
                if (element is ClosePath) current = null
            }
        }
        // Resolve zero-length segments in two linear scans, including across moveto boundaries.
        val before = arrayOfNulls<Point2D>(segments.size + 1)
        val after = arrayOfNulls<Point2D>(segments.size + 1)
        for (i in segments.indices) before[i + 1] = segments[i].end ?: before[i]
        for (i in segments.indices.reversed()) after[i] = segments[i].start ?: after[i + 1]
        fun start(i: Int) = segments[i].start ?: before[i] ?: after[i + 1] ?: Point2D(1.0, 0.0)
        fun end(i: Int) = segments[i].end ?: after[i + 1] ?: before[i] ?: Point2D(1.0, 0.0)
        return paths.flatMap { path ->
            val last = path.points.lastIndex
            path.points.mapIndexed { index, p ->
                val incoming = when {
                    index > 0 -> end(path.offset + index - 1)
                    path.closed && last > 0 -> end(path.offset + last - 1)
                    else -> null
                }
                val outgoing = when {
                    index < last -> start(path.offset + index)
                    path.closed && last > 0 -> start(path.offset)
                    else -> null
                }
                if (incoming == null && outgoing == null)
                    Vertex(p, null, after[path.offset] ?: before[path.offset] ?: Point2D(1.0, 0.0))
                else Vertex(p, incoming, outgoing)
            }
        }
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<PathElement> =
        if (rx <= 0 || ry <= 0) emptyList() else listOf(MoveTo(cx + rx, cy),
            ArcTo(rx, ry, 0.0, cx, cy + ry, false, true),
            ArcTo(rx, ry, 0.0, cx - rx, cy, false, true),
            ArcTo(rx, ry, 0.0, cx, cy - ry, false, true),
            ArcTo(rx, ry, 0.0, cx + rx, cy, false, true), ClosePath())

    private fun arcTangents(start: Point2D, arc: ArcTo): Pair<Point2D, Point2D> {
        var rx = arc.radiusX; var ry = arc.radiusY
        val fallback = Point2D(arc.x - start.x, arc.y - start.y)
        if (rx == 0.0 || ry == 0.0) return fallback to fallback
        val phi = Math.toRadians(arc.xAxisRotation)
        val dx = (start.x - arc.x) / 2; val dy = (start.y - arc.y) / 2
        var ux = (cos(phi) * dx + sin(phi) * dy) / rx
        var uy = (-sin(phi) * dx + cos(phi) * dy) / ry
        val radiiScale = hypot(ux, uy)
        if (radiiScale > 1) {
            rx *= radiiScale; ry *= radiiScale
            ux /= radiiScale; uy /= radiiScale
        }
        val lengthSquared = ux * ux + uy * uy
        val factor = (if (arc.isLargeArcFlag == arc.isSweepFlag) -1 else 1) *
            sqrt(max(0.0, (1 - lengthSquared) / lengthSquared))
        val cx = factor * uy; val cy = -factor * ux
        val sign = if (arc.isSweepFlag) 1 else -1
        fun tangent(x: Double, y: Double): Point2D {
            val tx = -rx * y * sign; val ty = ry * x * sign
            return Point2D(cos(phi) * tx - sin(phi) * ty, sin(phi) * tx + cos(phi) * ty).also {
                require(it.x.isFinite() && it.y.isFinite()) { "Non-finite marker arc tangent" }
            }
        }
        return tangent(ux - cx, uy - cy) to tangent(-ux - cx, -uy - cy)
    }
}
