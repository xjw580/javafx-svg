package club.xiaojiawei.svg

import javafx.beans.InvalidationListener
import javafx.beans.value.ChangeListener
import javafx.beans.property.Property
import javafx.css.StyleOrigin
import javafx.css.StyleableProperty
import javafx.scene.paint.Paint
import javafx.scene.shape.Shape

/** Per-view defaults beneath application CSS. Source paints are retained for reversible overrides. */
internal class SvgShapeStyles(private val requestLayout: () -> Unit) {
    private val entries = mutableListOf<Entry>()
    private var fill: Paint? = null
    private var stroke: Paint? = null
    private var strokeWidth = -1.0
    private var dirty = false
    private var applying = false
    private var tracking = false
    private val active: Boolean get() = fill != null || stroke != null || strokeWidth >= 0
    // ChangeListener validates lazy properties, so every subsequent CSS reset is observable.
    private val changed = ChangeListener<Any?> { _, _, _ -> invalidate() }
    private val cssChanged = InvalidationListener { invalidate() }
    private fun invalidate() {
        if (!applying) {
            dirty = true
            requestLayout()
        }
    }
    val size: Int get() = entries.size

    fun register(shape: Shape): Entry = Entry(shape).also {
        entries += it
        if (tracking) it.listen(true)
    }

    fun discardFrom(index: Int) {
        while (entries.size > index) entries.removeAt(entries.lastIndex).listen(false)
    }

    fun setTheme(fill: Paint?, stroke: Paint?, strokeWidth: Double) {
        if (this.fill == fill && this.stroke == stroke && this.strokeWidth == strokeWidth) return
        this.fill = fill; this.stroke = stroke; this.strokeWidth = strokeWidth
        // CSS can still hide source defaults when the final theme is cleared.
        if (!tracking && active) {
            tracking = true
            entries.forEach { it.listen(true) }
        }
        dirty = true
        refresh()
        requestLayout()
    }

    fun refresh() {
        if (!dirty) return
        applying = true
        try {
            entries.forEach { it.apply() }
            dirty = false
        } finally { applying = false }
    }

    inner class Entry(private val shape: Shape) {
        private var baseFill: Paint? = null
        private var baseStroke: Paint? = null
        private var baseWidth = 1.0

        fun setBase(fill: Paint?, stroke: Paint?, width: Double) {
            baseFill = fill; baseStroke = stroke; baseWidth = width
            val wasApplying = applying
            applying = true
            try { apply() } finally { applying = wasApplying }
        }

        fun apply() {
            // Keep unfilled paths hollow; an explicit child -fx-fill can still enable their fill.
            setSvgBaseStyle(shape.fillProperty(), if (baseFill == null) null else fill ?: baseFill)
            setSvgBaseStyle(shape.strokeProperty(), stroke ?: baseStroke)
            setSvgBaseStyle(shape.strokeWidthProperty(), if (strokeWidth >= 0) strokeWidth else baseWidth)
        }

        fun listen(add: Boolean) {
            val properties = listOf(shape.fillProperty(), shape.strokeProperty(), shape.strokeWidthProperty())
            properties.forEach { if (add) it.addListener(changed) else it.removeListener(changed) }
            // A CSS origin can change without changing the current paint value.
            if (add) {
                shape.styleProperty().addListener(cssChanged)
                shape.styleClass.addListener(cssChanged)
                shape.pseudoClassStates.addListener(cssChanged)
            } else {
                shape.styleProperty().removeListener(cssChanged)
                shape.styleClass.removeListener(cssChanged)
                shape.pseudoClassStates.removeListener(cssChanged)
            }
        }
    }
}

internal fun <T> setSvgBaseStyle(property: Property<T>, value: T) {
    val origin = (property as? StyleableProperty<*>)?.styleOrigin
    if (!property.isBound && origin != StyleOrigin.AUTHOR && origin != StyleOrigin.INLINE) property.value = value
}
