package club.xiaojiawei.svg

import javafx.geometry.Bounds
import javafx.scene.shape.Path
import javafx.scene.shape.Shape
import javafx.scene.text.Font
import javafx.scene.text.Text

/** Public native glyph outlines give us the font's real line positions and thicknesses. */
internal class SvgTextDecorationMetrics {
    private var font: Font? = null
    private var underline: Bounds? = null
    private var strike: Bounds? = null
    private var top = 0.0

    fun bounds(kind: String, currentFont: Font): Pair<Double, Double> {
        if (font != currentFont) {
            val probe = Text(" ").apply { font = currentFont; isUnderline = true }
            underline = Shape.union(probe, Path()).layoutBounds
            probe.isUnderline = false
            probe.isStrikethrough = true
            strike = Shape.union(probe, Path()).layoutBounds
            top = probe.layoutBounds.minY
            font = currentFont
        }
        val line = if (kind == "line-through") strike!! else underline!!
        return (if (kind == "overline") top else line.minY) to line.height.coerceAtLeast(0.0)
    }
}
