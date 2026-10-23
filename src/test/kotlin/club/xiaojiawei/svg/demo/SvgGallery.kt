package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgView
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.input.KeyCode
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Shape
import javafx.scene.text.Font
import javafx.scene.text.Text
import java.nio.file.Files
import java.nio.file.Path

/** Reads source SVGs in place. Nothing is written to the source project. */
internal class SvgGallery(private val directory: Path) {
    private data class Entry(
        val path: Path,
        val original: SvgView?,
        val themed: SvgView?,
        val messages: List<String>,
        val error: String?
    )
    private val entries: List<Entry>
    private val grid = GridPane().apply { hgap = 12.0; vgap = 12.0; alignment = Pos.TOP_CENTER }
    private val pageLabel = label("", 14.0)
    private val detail = label("单击图标查看文件诊断；使用左右方向键或下方按钮翻页。", 12.0).apply {
        wrappingWidth = WIDTH - 64.0
        fill = Color.web("#53657d")
    }
    private val previous = navigation("上一页") { showPage(page - 1) }
    private val next = navigation("下一页") { showPage(page + 1) }
    private var page = 0
    private val cards: List<VBox>
    val root: VBox
    val pageCount: Int get() = (entries.size + PAGE_SIZE - 1) / PAGE_SIZE

    init {
        require(Files.isDirectory(directory)) { "SVG directory does not exist: $directory" }
        val paths = Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".svg", ignoreCase = true) }
                .sorted().toList()
        }
        require(paths.isNotEmpty()) { "No SVG files in: $directory" }
        entries = paths.map { path ->
            try {
                val document = SvgLoader.load(path)
                val original = icon(SvgView(document))
                val themed = icon(SvgView(document)).apply {
                    styleClass += "gallery-themed"
                    lookupAll(".svg-shape").filterIsInstance<Shape>().forEach { shape ->
                        val fill = shape.fill
                        val detailFill = fill is Color && (fill.opacity == 0.0 || (fill.red == 1.0 && fill.green == 1.0 && fill.blue == 1.0))
                        if (fill != null && !detailFill) shape.styleClass += "gallery-fill"
                        if (shape.stroke != null) shape.styleClass += "gallery-stroke"
                    }
                }
                Entry(path, original, themed, original.diagnostics.map { it.message }.distinct(), null)
            } catch (e: Exception) {
                Entry(path, null, null, emptyList(), e.message ?: e.javaClass.simpleName)
            }
        }
        cards = entries.map { entry ->
            val previews = HBox(22.0).apply {
                alignment = Pos.CENTER
                if (entry.original != null) children.addAll(entry.original, entry.themed)
                else children += label("加载失败", 14.0).apply { fill = Color.FIREBRICK }
            }
            val state = when {
                entry.error != null -> "加载失败"
                entry.messages.isNotEmpty() -> "${entry.messages.size} 项诊断"
                else -> "正常"
            }
            VBox(9.0, label(entry.path.fileName.toString(), 12.0), previews, label(state, 11.0).apply {
                fill = if (entry.error != null) Color.FIREBRICK else if (entry.messages.isNotEmpty()) Color.web("#946200") else Color.web("#18794e")
            }).apply {
                styleClass += "gallery-item"
                alignment = Pos.CENTER
                padding = Insets(12.0)
                setPrefSize(166.0, 124.0)
                setMinSize(166.0, 124.0)
                setMaxSize(166.0, 124.0)
                cursor = Cursor.HAND
                setOnMouseClicked {
                    detail.text = directory.relativize(entry.path).toString() + "  |  " +
                        (entry.error ?: entry.messages.joinToString("；").ifBlank { "无诊断" })
                }
            }
        }
        val failures = entries.count { it.error != null }
        val warnings = entries.count { it.messages.isNotEmpty() }
        val summary = label("共 ${entries.size} 个文件  ·  加载成功 ${entries.size - failures}  ·  失败 $failures  ·  含诊断 $warnings", 14.0)
        root = VBox(12.0,
            label(if (directory == defaultDirectory()) "javafx-ui SVG 图标预览" else "${directory.fileName} SVG 图标预览", 26.0),
            summary,
            label("左侧：原始 SVG    右侧：CSS 换色（保留白色细节，悬停再次换色）", 13.0),
            grid,
            HBox(24.0, previous, pageLabel, next).apply { alignment = Pos.CENTER },
            detail
        ).apply {
            styleClass += "gallery"
            padding = Insets(24.0)
            alignment = Pos.TOP_CENTER
            setOnKeyPressed {
                when (it.code) {
                    KeyCode.LEFT -> showPage(page - 1)
                    KeyCode.RIGHT -> showPage(page + 1)
                    else -> return@setOnKeyPressed
                }
                it.consume()
            }
        }
        showPage(0)
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        page = index
        grid.children.clear()
        cards.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { i, card ->
            grid.add(card, i % COLUMNS, i / COLUMNS)
        }
        pageLabel.text = "${page + 1} / $pageCount"
        previous.isDisable = page == 0
        next.isDisable = page + 1 == pageCount
    }

    fun export(outputDirectory: Path) {
        Files.createDirectories(outputDirectory)
        var blank = 0
        val report = mutableListOf("file\tstatus\tshapes\tvisiblePixels\tdiagnostics")
        val transparent = SnapshotParameters().apply { fill = Color.TRANSPARENT }
        for (index in 0 until pageCount) {
            showPage(index)
            root.resize(WIDTH, HEIGHT)
            root.applyCss()
            root.layout()
            val snapshot = root.snapshot(null, null)
            writeDemoSnapshot(snapshot, outputDirectory.resolve("page-${index + 1}.png"))
            entries.drop(index * PAGE_SIZE).take(PAGE_SIZE).forEach { entry ->
                val view = entry.original
                val pixels = if (view != null) {
                    val image = view.snapshot(transparent, null)
                    var count = 0
                    for (y in 0 until image.height.toInt()) for (x in 0 until image.width.toInt()) {
                        if ((image.pixelReader.getArgb(x, y) ushr 24) > 0) count++
                    }
                    count
                } else 0
                if (view != null && pixels == 0) blank++
                val state = if (entry.error != null) "ERROR" else if (pixels == 0) "BLANK" else if (entry.messages.isNotEmpty()) "WARN" else "OK"
                val messages = (entry.error ?: entry.messages.joinToString("; ")).replace(Regex("[\\t\\r\\n]+"), " ")
                report += "${directory.relativize(entry.path)}\t$state\t${view?.lookupAll(".svg-shape")?.size ?: 0}\t$pixels\t$messages"
            }
        }
        Files.write(outputDirectory.resolve("report.tsv"), report)
        val failed = entries.count { it.error != null }
        val warningCounts = entries.flatMap { it.messages }.groupingBy { it }.eachCount()
        println("SVG gallery: files=${entries.size}, loaded=${entries.size - failed}, failed=$failed, blank=$blank")
        warningCounts.forEach { (message, count) -> println("Diagnostic [$count]: $message") }
        println("SVG gallery output: ${outputDirectory.toAbsolutePath()}")
        check(failed == 0 && blank == 0) { "Gallery contains failed or blank SVG files; see report.tsv" }
    }

    companion object {
        const val WIDTH = 1120.0
        const val HEIGHT = 760.0
        private const val COLUMNS = 6
        private const val PAGE_SIZE = 24
        fun defaultDirectory(): Path = Path.of("..", "javafx-ui", "src", "main", "resources", "club", "xiaojiawei", "controls", "images").toAbsolutePath().normalize()
        private fun label(value: String, size: Double) = Text(value).apply { font = Font.font("System", size); fill = Color.web("#172b4d") }
        private fun icon(view: SvgView) = view.apply {
            fitWidth = 48.0; fitHeight = 48.0
            setMinSize(48.0, 48.0); setMaxSize(48.0, 48.0)
        }
        private fun navigation(value: String, action: () -> Unit): Node =
            StackPane(label(value, 14.0)).apply {
                styleClass += "gallery-nav"
                padding = Insets(8.0, 20.0, 8.0, 20.0)
                cursor = Cursor.HAND
                isFocusTraversable = true
                setOnMouseClicked { action() }
                setOnKeyPressed {
                    if (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE) { action(); it.consume() }
                }
            }
    }
}
