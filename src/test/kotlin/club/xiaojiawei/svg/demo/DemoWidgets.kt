package club.xiaojiawei.svg.demo

import javafx.beans.property.SimpleDoubleProperty
import javafx.css.PseudoClass
import javafx.geometry.Insets
import javafx.scene.AccessibleRole
import javafx.scene.Cursor
import javafx.scene.canvas.Canvas
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import kotlin.math.max

internal class DemoAction(label: String, private val action: () -> Unit) : StackPane() {
    var selected = false
        set(value) { field = value; pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), value) }
    init {
        styleClass += "demo-action"
        children += Text(label).apply { styleClass += "action-label" }
        padding = Insets(9.0, 13.0, 9.0, 13.0)
        cursor = Cursor.HAND; isFocusTraversable = true
        accessibleRole = AccessibleRole.BUTTON; accessibleText = label
        setOnMouseClicked { if (!isDisabled && it.button == MouseButton.PRIMARY) { requestFocus(); action(); it.consume() } }
        setOnKeyPressed { if (!isDisabled && (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE)) { action(); it.consume() } }
    }
}

/** Graphics-only scrolling for the example browser; the standalone editor uses standard controls. */
internal class DemoViewport(private val content: VBox) : Region() {
    private val viewportClip = Rectangle()
    var offset = 0.0
        private set
    init {
        content.isManaged = false; children += content; clip = viewportClip
        minWidth = 0.0; minHeight = 0.0; isFocusTraversable = true
        accessibleText = "可滚动区域，使用滚轮、方向键或 Page Up / Down"
        setOnScroll { val before = offset; scrollTo(offset - it.deltaY); if (offset != before) it.consume() }
        setOnKeyPressed {
            val amount = when (it.code) {
                KeyCode.PAGE_DOWN -> height * .8; KeyCode.PAGE_UP -> -height * .8
                KeyCode.DOWN -> 40.0; KeyCode.UP -> -40.0
                KeyCode.HOME -> -Double.MAX_VALUE; KeyCode.END -> Double.MAX_VALUE
                else -> return@setOnKeyPressed
            }
            scrollTo(offset + amount); it.consume()
        }
    }
    fun scrollTo(value: Double) {
        offset = value.coerceIn(0.0, max(0.0, content.height - height))
        content.relocate(0.0, -offset)
    }
    override fun layoutChildren() {
        viewportClip.width = width; viewportClip.height = height
        content.resize(width, max(height, content.prefHeight(width)))
        content.layout(); scrollTo(offset)
    }
}

internal class DemoBoard : StackPane() {
    private val grid = Canvas()
    var dark = false
        set(value) { field = value; draw() }
    var showGrid = true
        set(value) { field = value; draw() }
    init {
        styleClass += "demo-board"
        children += grid
        grid.isManaged = false; grid.isMouseTransparent = true
        minWidth = 0.0; minHeight = 310.0; prefHeight = 310.0
        clip = Rectangle().apply { widthProperty().bind(this@DemoBoard.widthProperty()); heightProperty().bind(this@DemoBoard.heightProperty()) }
    }
    override fun layoutChildren() {
        grid.width = width; grid.height = height
        draw()
        super.layoutChildren()
    }
    private fun draw() {
        val gc = grid.graphicsContext2D
        gc.fill = if (dark) Color.web("#111c30") else Color.web("#f8faff")
        gc.fillRect(0.0, 0.0, grid.width, grid.height)
        if (!showGrid) return
        gc.fill = if (dark) Color.web("#2a3952") else Color.web("#d4ddec")
        for (x in 16 until grid.width.toInt() step 20) for (y in 16 until grid.height.toInt() step 20) gc.fillOval(x.toDouble(), y.toDouble(), 1.7, 1.7)
    }
}

internal class DemoTimeline(
    private val beginSeek: () -> Unit,
    private val seek: (Double) -> Unit,
    private val finishSeek: () -> Unit
) : Region() {
    private val track = Rectangle()
    private val filled = Rectangle()
    private val thumb = javafx.scene.shape.Circle(5.0)
    val progress = SimpleDoubleProperty(0.0)
    init {
        styleClass += "demo-timeline"
        track.fill = Color.web("#dce4f0"); track.arcWidth = 6.0; track.arcHeight = 6.0
        filled.styleClass += "timeline-fill"; filled.arcWidth = 6.0; filled.arcHeight = 6.0
        thumb.styleClass += "timeline-thumb"
        children.setAll(track, filled, thumb)
        minWidth = 60.0; prefWidth = 200.0; prefHeight = 28.0
        maxWidth = Double.MAX_VALUE
        progress.addListener { _ -> requestLayout() }
        cursor = Cursor.HAND; isFocusTraversable = true
        accessibleRole = AccessibleRole.SLIDER; accessibleText = "动画时间轴，左右方向键调整"
        fun position(x: Double) = seek(((x - 6) / max(1.0, width - 12)).coerceIn(0.0, 1.0))
        setOnMousePressed { if (!isDisabled && it.button == MouseButton.PRIMARY) { requestFocus(); beginSeek(); position(it.x); it.consume() } }
        setOnMouseDragged { if (!isDisabled) { position(it.x); it.consume() } }
        setOnMouseReleased { if (!isDisabled) { finishSeek(); it.consume() } }
        setOnKeyPressed {
            if (isDisabled || it.code !in setOf(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.HOME, KeyCode.END)) return@setOnKeyPressed
            beginSeek()
            seek(when (it.code) { KeyCode.HOME -> 0.0; KeyCode.END -> 1.0; KeyCode.LEFT -> (progress.get() - .02).coerceAtLeast(0.0); else -> (progress.get() + .02).coerceAtMost(1.0) })
            finishSeek(); it.consume()
        }
    }
    override fun layoutChildren() {
        val available = max(0.0, width - 12)
        track.x = 6.0; track.y = height / 2 - 2; track.width = available; track.height = 4.0
        filled.x = 6.0; filled.y = track.y; filled.width = available * progress.get().coerceIn(0.0, 1.0); filled.height = 4.0
        thumb.centerX = 6 + filled.width; thumb.centerY = height / 2
    }
}
