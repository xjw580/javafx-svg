package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.css.StyleOrigin
import javafx.css.StyleableProperty
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text

/** Decorations belong to their originating text scope, not a differently styled descendant. */
internal class SvgPropagatedTextDecorations(
    val text: Text, content: Node, sources: Map<String, Text>,
    private val cssTarget: Text = text, private val baseline: (String) -> Double
) : Group() {
    private data class Decoration(val kind: String, val source: Text, val line: Rectangle,
                                  val metrics: SvgTextDecorationMetrics = SvgTextDecorationMetrics())
    private val decorations = sources.map { (kind, source) ->
        Decoration(kind, source, Rectangle().apply {
            styleClass.addAll("svg-text-decoration-line", "svg-text-$kind")
            isMouseTransparent = true
            SvgTextDecorationPaint.bind(this, source)
            strokeWidthProperty().bind(source.strokeWidthProperty())
            strokeTypeProperty().bind(source.strokeTypeProperty())
            strokeLineCapProperty().bind(source.strokeLineCapProperty())
            strokeLineJoinProperty().bind(source.strokeLineJoinProperty())
            strokeMiterLimitProperty().bind(source.strokeMiterLimitProperty())
            strokeDashOffsetProperty().bind(source.strokeDashOffsetProperty())
            Bindings.bindContent(strokeDashArray, source.strokeDashArray)
        })
    }

    init {
        styleClass += "svg-propagated-decoration"
        children.addAll(decorations.filter { it.kind != "line-through" }.map { it.line })
        children += content
        children.addAll(decorations.filter { it.kind == "line-through" }.map { it.line })
        val changed = InvalidationListener { refresh() }
        text.layoutBoundsProperty().addListener(changed)
        text.localToParentTransformProperty().addListener(changed)
        text.visibleProperty().addListener(changed)
        cssTarget.underlineProperty().addListener(changed)
        cssTarget.strikethroughProperty().addListener(changed)
        cssTarget.styleProperty().addListener(changed)
        decorations.forEach { it.source.fontProperty().addListener(changed) }
        refresh()
    }

    fun refresh() {
        decorations.forEach { (kind, source, line, metrics) ->
            val (offset, thickness) = metrics.bounds(kind, source.font)
            line.x = text.x
            line.y = baseline(kind) + offset
            line.width = text.layoutBounds.width.coerceAtLeast(0.0)
            line.transforms.setAll(text.localToParentTransform)
            line.height = if (text.text.isEmpty()) 0.0 else thickness
            val property = when (kind) {
                "underline" -> cssTarget.underlineProperty()
                "line-through" -> cssTarget.strikethroughProperty()
                else -> null
            }
            val origin = (property as? StyleableProperty<*>)?.styleOrigin
            // An application CSS override wins; true is then rendered natively with the host style.
            line.isVisible = text.isVisible && (property == null || !property.isBound &&
                origin != StyleOrigin.AUTHOR && origin != StyleOrigin.INLINE)
        }
    }
}
