package club.xiaojiawei.svg

import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.paint.Stop
import javafx.scene.shape.Shape
import javafx.scene.transform.Affine

internal fun svgTransformTo(node: Node, ancestor: Node): Affine? {
    val result = Affine()
    var current: Node? = node
    while (current !== ancestor) {
        if (current == null) return null
        require(current.localToParentTransform.isType2D) { "Markers require 2D transforms" }
        result.prepend(current.localToParentTransform)
        current = current.parent
    }
    return result
}

/** A marker instance reads its context shape's current paints, including application CSS. */
internal class SvgMarkerContext(
    private val source: Shape,
    private val owner: Node,
    private val report: (String, SvgElement?) -> Unit
) {
    val updates = mutableListOf<() -> Unit>()

    fun paint(value: String, opacity: Double, target: Node, element: SvgElement): Paint? {
        val paint = if (value == "context-fill") source.fill else source.stroke
        if (paint == null) return null
        if (paint is Color) return if (opacity == 1.0) paint else Color(paint.red, paint.green, paint.blue, paint.opacity * opacity)
        if (paint !is LinearGradient) {
            report("Marker context paint currently supports colors and linear gradients", element)
            return null
        }
        val sourceTransform = svgTransformTo(source, owner) ?: return null
        val targetTransform = svgTransformTo(target, owner) ?: return null
        if (sourceTransform.determinant() == 0.0 || targetTransform.determinant() == 0.0) return null
        val mapping = targetTransform.createInverse().apply { append(sourceTransform) }
        var x1 = paint.startX; var y1 = paint.startY; var x2 = paint.endX; var y2 = paint.endY
        if (paint.isProportional) {
            val bounds = svgGeometryBounds(source)
            if (bounds.width <= 0 || bounds.height <= 0) return null
            x1 = bounds.minX + x1 * bounds.width; x2 = bounds.minX + x2 * bounds.width
            y1 = bounds.minY + y1 * bounds.height; y2 = bounds.minY + y2 * bounds.height
        }
        val dx = x2 - x1; val dy = y2 - y1
        val squared = dx * dx + dy * dy
        val stops = paint.stops.map { Stop(it.offset, it.color.deriveColor(0.0, 1.0, 1.0, opacity)) }
        if (squared == 0.0) return stops.last().color
        val inverse = mapping.createInverse()
        val nx = (inverse.mxx * dx + inverse.myx * dy) / squared
        val ny = (inverse.mxy * dx + inverse.myy * dy) / squared
        val normalSquared = nx * nx + ny * ny
        val start = mapping.transform(x1, y1)
        require(listOf(start.x, start.y, nx, ny, normalSquared).all { it.isFinite() } && normalSquared > 0) {
            "Invalid marker context gradient transform"
        }
        return LinearGradient(start.x, start.y, start.x + nx / normalSquared, start.y + ny / normalSquared,
            false, paint.cycleMethod, stops)
    }
}
