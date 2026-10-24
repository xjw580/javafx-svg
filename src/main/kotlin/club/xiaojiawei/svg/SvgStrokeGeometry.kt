package club.xiaojiawei.svg

import javafx.scene.shape.*
import javafx.scene.text.Text
import javafx.scene.transform.Transform
import kotlin.math.*

/** Public JavaFX geometry only. Preserve open subpaths and arcs before computing the stroke. */
internal object SvgStrokeGeometry {
    fun copy(source: Shape): Shape = when (source) {
        is SVGPath -> SVGPath().apply {
            contentProperty().bind(source.contentProperty())
            fillRuleProperty().bind(source.fillRuleProperty())
        }
        is Rectangle -> Rectangle().apply {
            xProperty().bind(source.xProperty()); yProperty().bind(source.yProperty())
            widthProperty().bind(source.widthProperty()); heightProperty().bind(source.heightProperty())
            arcWidthProperty().bind(source.arcWidthProperty()); arcHeightProperty().bind(source.arcHeightProperty())
        }
        is Circle -> Circle().apply {
            centerXProperty().bind(source.centerXProperty()); centerYProperty().bind(source.centerYProperty())
            radiusProperty().bind(source.radiusProperty())
        }
        is Ellipse -> Ellipse().apply {
            centerXProperty().bind(source.centerXProperty()); centerYProperty().bind(source.centerYProperty())
            radiusXProperty().bind(source.radiusXProperty()); radiusYProperty().bind(source.radiusYProperty())
        }
        is Line -> Line().apply {
            startXProperty().bind(source.startXProperty()); startYProperty().bind(source.startYProperty())
            endXProperty().bind(source.endXProperty()); endYProperty().bind(source.endYProperty())
        }
        is Text -> Text().apply {
            textProperty().bind(source.textProperty()); fontProperty().bind(source.fontProperty())
            xProperty().bind(source.xProperty()); yProperty().bind(source.yProperty())
            textOriginProperty().bind(source.textOriginProperty()); boundsTypeProperty().bind(source.boundsTypeProperty())
            underlineProperty().bind(source.underlineProperty()); strikethroughProperty().bind(source.strikethroughProperty())
            fontSmoothingTypeProperty().bind(source.fontSmoothingTypeProperty())
        }
        else -> throw SvgException("Unsupported non-scaling-stroke shape: ${source.javaClass.simpleName}")
    }

    fun centerline(source: Shape, limit: Int = Int.MAX_VALUE): List<PathElement> = when (source) {
        is SVGPath -> path(source.content, limit)
        is Line -> listOf(MoveTo(source.startX, source.startY), LineTo(source.endX, source.endY))
        is Circle -> ellipse(source.centerX, source.centerY, source.radius, source.radius)
        is Ellipse -> ellipse(source.centerX, source.centerY, source.radiusX, source.radiusY)
        is Rectangle -> rectangle(source)
        is Text -> {
            // Detached native glyph outlines; union must never include the scene's transforms.
            val text = Text(source.text).apply {
                font = source.font; x = source.x; y = source.y; textOrigin = source.textOrigin
                boundsType = source.boundsType; isUnderline = source.isUnderline; isStrikethrough = source.isStrikethrough
                fill = javafx.scene.paint.Color.BLACK; stroke = null
            }
            (Shape.union(text, Path()) as Path).elements.toList()
        }
        else -> throw SvgException("Unsupported non-scaling-stroke geometry")
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<PathElement> =
        if (rx <= 0 || ry <= 0) emptyList() else listOf(MoveTo(cx + rx, cy),
            ArcTo(rx, ry, 0.0, cx - rx, cy, false, true),
            ArcTo(rx, ry, 0.0, cx + rx, cy, false, true), ClosePath())

    private fun rectangle(rect: Rectangle): List<PathElement> {
        val x = rect.x; val y = rect.y; val w = rect.width; val h = rect.height
        if (w <= 0 || h <= 0) return emptyList()
        val rx = min(abs(rect.arcWidth) / 2, w / 2)
        val ry = min(abs(rect.arcHeight) / 2, h / 2)
        if (rx == 0.0 || ry == 0.0) return listOf(MoveTo(x, y), LineTo(x + w, y),
            LineTo(x + w, y + h), LineTo(x, y + h), ClosePath())
        return listOf(MoveTo(x + rx, y), LineTo(x + w - rx, y),
            ArcTo(rx, ry, 0.0, x + w, y + ry, false, true), LineTo(x + w, y + h - ry),
            ArcTo(rx, ry, 0.0, x + w - rx, y + h, false, true), LineTo(x + rx, y + h),
            ArcTo(rx, ry, 0.0, x, y + h - ry, false, true), LineTo(x, y + ry),
            ArcTo(rx, ry, 0.0, x + rx, y, false, true), ClosePath())
    }

    internal fun path(data: String, limit: Int = Int.MAX_VALUE): List<PathElement> {
        val result = mutableListOf<PathElement>()
        var x = 0.0; var y = 0.0
        var startX = 0.0; var startY = 0.0
        var controlX = 0.0; var controlY = 0.0
        var previous = ' '
        SvgPathData.forEach(data) { command, a ->
            require(result.size < limit) { "SVG marker vertex limit exceeded" }
            val c = command.uppercaseChar()
            val ox = if (command.isLowerCase()) x else 0.0
            val oy = if (command.isLowerCase()) y else 0.0
            when (c) {
                'Z' -> { result += ClosePath(); x = startX; y = startY }
                'M', 'L', 'T' -> {
                    val nextX = a[0] + ox; val nextY = a[1] + oy
                    result += when (c) {
                        'M' -> MoveTo(nextX, nextY).also { startX = nextX; startY = nextY }
                        'T' -> {
                            val cx = if (previous in "QT") 2 * x - controlX else x
                            val cy = if (previous in "QT") 2 * y - controlY else y
                            controlX = cx; controlY = cy
                            QuadCurveTo(cx, cy, nextX, nextY)
                        }
                        else -> LineTo(nextX, nextY)
                    }
                    x = nextX; y = nextY
                }
                'H' -> { x = a[0] + ox; result += LineTo(x, y) }
                'V' -> { y = a[0] + oy; result += LineTo(x, y) }
                'C', 'S' -> {
                    val cx = if (c == 'C') a[0] + ox else if (previous in "CS") 2 * x - controlX else x
                    val cy = if (c == 'C') a[1] + oy else if (previous in "CS") 2 * y - controlY else y
                    val index = if (c == 'C') 2 else 0
                    controlX = a[index] + ox; controlY = a[index + 1] + oy
                    x = a[index + 2] + ox; y = a[index + 3] + oy
                    result += CubicCurveTo(cx, cy, controlX, controlY, x, y)
                }
                'Q' -> {
                    controlX = a[0] + ox; controlY = a[1] + oy
                    x = a[2] + ox; y = a[3] + oy
                    result += QuadCurveTo(controlX, controlY, x, y)
                }
                'A' -> {
                    x = a[5] + ox; y = a[6] + oy
                    result += if (a[0] == 0.0 || a[1] == 0.0) LineTo(x, y)
                        else ArcTo(a[0], a[1], a[2], x, y, a[3] != 0.0, a[4] != 0.0)
                }
            }
            previous = c
        }
        return result
    }

    fun transform(elements: List<PathElement>, matrix: Transform): List<PathElement> {
        fun point(x: Double, y: Double) = matrix.transform(x, y).also {
            require(it.x.isFinite() && it.y.isFinite()) { "Non-finite transformed stroke geometry" }
        }
        return elements.map { element ->
            when (element) {
                is MoveTo -> point(element.x, element.y).let { MoveTo(it.x, it.y) }
                is LineTo -> point(element.x, element.y).let { LineTo(it.x, it.y) }
                is QuadCurveTo -> {
                    val c = point(element.controlX, element.controlY); val p = point(element.x, element.y)
                    QuadCurveTo(c.x, c.y, p.x, p.y)
                }
                is CubicCurveTo -> {
                    val a = point(element.controlX1, element.controlY1); val b = point(element.controlX2, element.controlY2)
                    val p = point(element.x, element.y)
                    CubicCurveTo(a.x, a.y, b.x, b.y, p.x, p.y)
                }
                is ArcTo -> {
                    val p = point(element.x, element.y)
                    // Singular values of A * rotation(phi) * diag(rx, ry) give the transformed ellipse.
                    val phi = Math.toRadians(element.xAxisRotation)
                    val a = (matrix.mxx * cos(phi) + matrix.mxy * sin(phi)) * element.radiusX
                    val b = (-matrix.mxx * sin(phi) + matrix.mxy * cos(phi)) * element.radiusY
                    val c = (matrix.myx * cos(phi) + matrix.myy * sin(phi)) * element.radiusX
                    val d = (-matrix.myx * sin(phi) + matrix.myy * cos(phi)) * element.radiusY
                    val xx = a * a + b * b; val yy = c * c + d * d; val xy = a * c + b * d
                    val delta = hypot(xx - yy, 2 * xy)
                    val major = sqrt(max(0.0, (xx + yy + delta) / 2))
                    // Determinant / major avoids cancellation for narrow transformed ellipses.
                    val minor = if (major == 0.0) 0.0 else abs(a * d - b * c) / major
                    val angle = Math.toDegrees(atan2(2 * xy, xx - yy) / 2)
                    require(listOf(major, minor, angle).all { it.isFinite() }) { "Non-finite transformed arc" }
                    if (major == 0.0 || minor == 0.0) LineTo(p.x, p.y)
                    else ArcTo(major, minor, angle, p.x, p.y, element.isLargeArcFlag,
                        element.isSweepFlag.xor(matrix.determinant() < 0))
                }
                is ClosePath -> ClosePath()
                else -> throw SvgException("Unsupported native stroke path element")
            }
        }
    }
}
