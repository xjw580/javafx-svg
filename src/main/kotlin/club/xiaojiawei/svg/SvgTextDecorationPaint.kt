package club.xiaojiawei.svg

import javafx.beans.binding.Bindings
import javafx.geometry.Bounds
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.paint.RadialGradient
import javafx.scene.shape.Shape
import javafx.scene.text.Text

/** Paint coordinates must remain those of the source text, not each thin decoration rectangle. */
internal object SvgTextDecorationPaint {
    fun supported(paint: Paint?): Boolean = when (paint) {
        is RadialGradient -> !paint.isProportional
        is ImagePattern -> !paint.isProportional
        else -> true
    }

    private fun resolve(paint: Paint?, bounds: Bounds): Paint? {
        if (!supported(paint)) return null
        if (paint !is LinearGradient || !paint.isProportional) return paint
        return LinearGradient(bounds.minX + paint.startX * bounds.width, bounds.minY + paint.startY * bounds.height,
            bounds.minX + paint.endX * bounds.width, bounds.minY + paint.endY * bounds.height,
            false, paint.cycleMethod, paint.stops)
    }

    fun bind(target: Shape, source: Text) {
        source.properties[KEY] = true
        target.fillProperty().bind(Bindings.createObjectBinding(
            { resolve(source.fill, source.layoutBounds) }, source.fillProperty(), source.layoutBoundsProperty()))
        target.strokeProperty().bind(Bindings.createObjectBinding(
            { resolve(source.stroke, source.layoutBounds) }, source.strokeProperty(), source.layoutBoundsProperty()))
    }

    const val KEY = "svg-custom-text-decoration"
}
