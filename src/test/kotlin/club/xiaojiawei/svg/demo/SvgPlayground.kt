package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgDocument
import club.xiaojiawei.svg.SvgException
import club.xiaojiawei.svg.SvgView
import javafx.animation.Animation
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlin.math.max

/** Editable SVG playground, independent of the example browser's read-only source tabs. */
internal class SvgPlayground(private val original: String) : BorderPane(), AutoCloseable {
    val codeArea = TextArea()
    val statusLabel = Label("准备输入 SVG")
    private val host = StackPane()
    private val board = DemoBoard()
    private val ratio = CheckBox("保持比例").apply { isSelected = true }
    private val play = Button("播放")
    private val pause = Button("暂停")
    private val stop = Button("回到起点")
    private var preview = SvgView().apply { setMinSize(0.0, 0.0) }
    private val session = SvgEditSession(apply = ::install, report = { message, error ->
        statusLabel.styleClass.remove("editor-error")
        if (error) statusLabel.styleClass += "editor-error"
        codeArea.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), error)
        statusLabel.text = message
    })
    val currentPreview: SvgView get() = preview

    init {
        styleClass += listOf("showcase", "blue", "svg-playground")
        padding = Insets(18.0)
        codeArea.apply {
            id = "live-svg-source"
            styleClass += "live-svg-source"
            promptText = """<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="35" fill="blue"/></svg>"""
            isWrapText = false
            setMinSize(0.0, 0.0)
            accessibleText = "SVG 源代码输入框"
            setOnKeyPressed {
                if (it.isShortcutDown && it.code == KeyCode.ENTER) { session.flush(); it.consume() }
            }
        }
        val render = Button("立即刷新 · Ctrl+Enter").apply { setOnAction { session.flush() } }
        val reset = Button("恢复载入示例").apply { setOnAction { codeArea.text = original; session.flush() } }
        val clear = Button("清空代码").apply { setOnAction { codeArea.clear() } }
        val copy = Button("复制 SVG").apply {
            setOnAction { Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(codeArea.text) }) }
        }
        val wrap = CheckBox("代码换行")
        codeArea.wrapTextProperty().bind(wrap.selectedProperty())
        val heading = Label("SVG 代码实时预览").apply { styleClass += "editor-title" }
        val hint = Label("输入或粘贴 SVG，停止输入 300 ms 后自动刷新；解析错误时保留上一帧。").apply { isWrapText = true }
        top = VBox(8.0, heading, hint, FlowPane(8.0, 8.0, render, reset, clear, copy, wrap)).apply { padding = Insets(0.0, 0.0, 14.0, 0.0) }

        host.children.setAll(preview)
        host.setMinSize(0.0, 0.0)
        board.children += host
        board.prefHeight = 500.0
        board.setMinSize(0.0, 120.0)
        val dark = CheckBox("深色背景").apply { selectedProperty().addListener { _, _, value -> board.dark = value } }
        val grid = CheckBox("网格").apply {
            isSelected = true
            selectedProperty().addListener { _, _, value -> board.showGrid = value }
        }
        ratio.selectedProperty().addListener { _, _, value -> preview.preserveRatio = value }
        play.setOnAction { preview.playAnimations(); transport() }
        pause.setOnAction { preview.pauseAnimations(); transport() }
        stop.setOnAction { preview.stopAnimations(); transport() }
        val options = FlowPane(8.0, 8.0, ratio, dark, grid, play, pause, stop).apply { alignment = Pos.CENTER_LEFT }
        val source = VBox(8.0, Label("SVG 源代码"), codeArea).apply {
            setMinSize(0.0, 0.0); padding = Insets(10.0)
            VBox.setVgrow(codeArea, Priority.ALWAYS)
        }
        val result = VBox(8.0, Label("实时预览"), options, board).apply {
            setMinSize(0.0, 0.0); padding = Insets(10.0)
            VBox.setVgrow(board, Priority.ALWAYS)
        }
        center = SplitPane(source, result).apply {
            orientation = Orientation.HORIZONTAL; setDividerPositions(.52)
            styleClass += "editor-split"
        }
        statusLabel.apply { isWrapText = true; styleClass += "editor-status"; id = "live-svg-status" }
        bottom = statusLabel
        BorderPane.setMargin(statusLabel, Insets(12.0, 0.0, 0.0, 0.0))
        codeArea.textProperty().addListener { _, _, value -> session.edit(value) }
        codeArea.text = original
        session.flush()
        transport()
    }

    fun refreshNow() = session.flush()

    private fun install(document: SvgDocument): String {
        val candidate = SvgView(document).apply {
            animationAutoPlay = false
            setMinSize(0.0, 0.0)
            preserveRatio = ratio.isSelected
        }
        val old = preview
        try {
            host.children.setAll(candidate)
            host.applyCss()
            host.layout()
            candidate.resize(max(1.0, host.width), max(1.0, host.height))
            candidate.layout()
            if (candidate.diagnostics.isNotEmpty())
                throw SvgException(candidate.diagnostics.take(3).joinToString("; ") { it.message })
        } catch (failure: Exception) {
            host.children.setAll(old)
            candidate.source = null
            throw failure
        }
        preview = candidate
        old.source = null
        candidate.animationAutoPlay = true
        candidate.animationStatusProperty().addListener { _, _, _ -> transport() }
        transport()
        return "已更新 · " + count(candidate) + " 个原生节点 · " + codeArea.length + " 字符" +
            if (candidate.hasAnimations) " · 动画自动播放" else ""
    }

    private fun transport() {
        play.isDisable = !preview.hasAnimations || preview.animationStatus == Animation.Status.RUNNING
        pause.isDisable = !preview.hasAnimations || preview.animationStatus != Animation.Status.RUNNING
        stop.isDisable = !preview.hasAnimations
    }

    override fun close() {
        session.close()
        preview.source = null
    }

    private fun count(node: Node): Int = 1 + if (node is Parent) node.childrenUnmodifiable.sumOf(::count) else 0
}
