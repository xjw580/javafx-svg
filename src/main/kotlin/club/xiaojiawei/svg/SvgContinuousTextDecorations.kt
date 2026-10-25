package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text

/** textLength spacing changes advances, so decoration must cover the gaps as well as glyphs. */
internal class SvgContinuousTextDecorations(private val source: Text, private val overline: Boolean) {
    private val metrics = SvgTextDecorationMetrics()
    val underline = line("underline")
    val over = line("overline")
    val strike = line("line-through")
    private var x = 0.0
    private var y = 0.0
    private var width = 0.0

    init {
        source.properties[SvgTextDecorationPaint.KEY] = { overline || source.isUnderline || source.isStrikethrough }
        val changed = InvalidationListener { refresh() }
        source.fontProperty().addListener(changed)
        source.underlineProperty().addListener(changed)
        source.strikethroughProperty().addListener(changed)
    }

    private fun line(kind: String) = Rectangle().apply {
        styleClass.addAll("svg-continuous-decoration", "svg-text-$kind")
        isMouseTransparent = true
        SvgTextDecorationPaint.bind(this, source)
        strokeWidthProperty().bind(source.strokeWidthProperty())
        strokeTypeProperty().bind(source.strokeTypeProperty())
        strokeLineCapProperty().bind(source.strokeLineCapProperty())
        strokeLineJoinProperty().bind(source.strokeLineJoinProperty())
        strokeMiterLimitProperty().bind(source.strokeMiterLimitProperty())
        strokeDashOffsetProperty().bind(source.strokeDashOffsetProperty())
        Bindings.bindContent(strokeDashArray, source.strokeDashArray)
    }

    fun position(x: Double, y: Double, width: Double) {
        this.x = x; this.y = y; this.width = width
        refresh()
    }

    private fun refresh() {
        for ((kind, line) in listOf("underline" to underline, "overline" to over, "line-through" to strike)) {
            val (offset, thickness) = metrics.bounds(kind, source.font)
            line.x = x; line.y = y + offset; line.width = width.coerceAtLeast(0.0); line.height = thickness
            line.isVisible = source.text.isNotEmpty() && when (kind) {
                "underline" -> source.isUnderline
                "line-through" -> source.isStrikethrough
                else -> overline
            }
        }
    }
}
