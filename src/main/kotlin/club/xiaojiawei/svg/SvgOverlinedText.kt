package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.scene.Group
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text

/** JavaFX has native underline/strikethrough but no overline. Keep Text as the CSS target. */
internal class SvgOverlinedText(val text: Text) : Group() {
    private val metrics = SvgTextDecorationMetrics()
    private val line = Rectangle().apply {
        styleClass += "svg-text-overline"
        isMouseTransparent = true
        SvgTextDecorationPaint.bind(this, text)
        strokeWidthProperty().bind(text.strokeWidthProperty())
        strokeTypeProperty().bind(text.strokeTypeProperty())
        strokeLineCapProperty().bind(text.strokeLineCapProperty())
        strokeLineJoinProperty().bind(text.strokeLineJoinProperty())
        strokeMiterLimitProperty().bind(text.strokeMiterLimitProperty())
        strokeDashOffsetProperty().bind(text.strokeDashOffsetProperty())
        Bindings.bindContent(strokeDashArray, text.strokeDashArray)
        opacityProperty().bind(text.opacityProperty())
        visibleProperty().bind(text.visibleProperty())
    }

    init {
        styleClass += "svg-text-decoration"
        children.setAll(line, text)
        val changed = InvalidationListener { position() }
        text.layoutBoundsProperty().addListener(changed)
        text.localToParentTransformProperty().addListener(changed)
        position()
    }

    private fun position() {
        val bounds = text.layoutBounds
        // Use the native font's underline thickness and logical ascent; avoid private JavaFX APIs.
        val (_, thickness) = metrics.bounds("overline", text.font)
        line.x = text.x
        line.y = bounds.minY
        line.width = bounds.width.coerceAtLeast(0.0)
        line.height = if (text.text.isEmpty()) 0.0 else thickness
        line.transforms.setAll(text.localToParentTransform)
    }
}
