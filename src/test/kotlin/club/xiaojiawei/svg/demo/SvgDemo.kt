package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgDocument
import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.application.Application
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.fxml.FXMLLoader
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.AccessibleRole
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.text.Text
import javafx.stage.Stage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max

/** All fixtures are offline. Only the explicit network action fetches a remote SVG. */
class SvgDemo : Application() {
    private data class Preview(val view: SvgView, val width: Double, val height: Double)
    private data class Theme(val name: String, val css: String, val color: String)
    private val themes = listOf(Theme("Blue", "blue", "#2563eb"),
        Theme("Green", "green", "#059669"), Theme("Violet", "violet", "#7c3aed"))
    private val previews = mutableListOf<Preview>()
    private val themeButtons = mutableListOf<DemoAction>()
    private val scaleButtons = mutableListOf<DemoAction>()
    private val scales = listOf(.8, 1.0, 1.2)
    private lateinit var content: VBox
    private lateinit var viewport: DemoViewport
    private lateinit var themeIcon: SvgView
    private lateinit var badge: SvgView
    private lateinit var badgeStatus: Text
    private val remoteSource = "https://img.shields.io/github/release/xjw580/Hearthstone-Script.svg"

    override fun start(stage: Stage) {
        val sample = document("sample.svg")
        val original = preview(sample, 92.0, 92.0)
        themeIcon = preview(sample, 92.0, 92.0).apply { styleClass += "theme-preview" }
        check(original.document === themeIcon.document)
        check(original.lookup("#outline") !== themeIcon.lookup("#outline"))
        val localSource = resource("showcase-cache.svg")
        val cacheLeft = preview(SvgView().apply { source = localSource }, 82.0, 82.0).apply { styleClass += "cache-blue" }
        val cacheRight = preview(SvgView().apply { source = localSource }, 82.0, 82.0).apply { styleClass += "cache-green" }
        check(cacheLeft.document === cacheRight.document)
        check(cacheLeft.lookup("#house") !== cacheRight.lookup("#house"))
        val fxml = FXMLLoader(javaClass.getResource("/demo/icon.fxml")).load<SvgView>().apply { setMinSize(0.0, 0.0) }
        val fxmlView = preview(fxml, 92.0, 92.0)

        val cards = TilePane(16.0, 16.0).apply {
            prefColumns = 3
            prefTileWidth = 344.0
            children.setAll(
                card("Original SVG", "PATH + USE", original, "Source paints and gradients. This document is shared with the next icon."),
                card("Theme & path CSS", "INTERACTIVE", themeIcon, "Root theme colors the outline. The purple check has its own CSS. Hover the icon."),
                card("Shapes & strokes", "GEOMETRY", fixture("shapes"), "Curves, circles, polygons, rounded corners and dashed strokes."),
                card("Linear & radial", "GRADIENT", fixture("gradients"), "Native gradients, stops, opacity and reflected linear paint."),
                card("Geometric clipping", "CLIP PATH", fixture("clip"), "A circular window and an even-odd path expose only the selected areas."),
                card("Text, tspan & length", "TYPOGRAPHY", fixture("text"), "Nested styled spans, baseline offsets, native Text, spacing and glyph-width adjustment."),
                card("Reusable symbols", "USE + TRANSFORM", fixture("symbols"), "One symbol, independent instances, currentColor and nested transforms."),
                card("Alpha & luminance", "MASK", fixture("masks"), "Same black / gray / white mask: alpha preserves them, luminance changes visibility."),
                card("Gaussian blur", "FILTER", fixture("blur"), "Crisp and blurred shapes, using an explicit sRGB filter."),
                card("Viewport fitting", "LAYOUT", fixture("layout"), "Nested SVG viewports compare meet, slice and nonuniform stretching."),
                card("Shared resource", "CACHE", HBox(16.0, cacheLeft, cacheRight).apply { alignment = Pos.CENTER },
                    "Two source loads share a document. Their nodes and CSS remain independent."),
                card("Loaded from FXML", "FXML", fxmlView, "The existing icon.fxml resolves its SVG and stylesheet relative to the resource.")
            )
        }
        content = VBox(16.0).apply { styleClass += listOf("showcase", "blue"); padding = Insets(24.0) }
        val subtitle = text("Native JavaFX rendering · independent nodes · SVG, CSS and resource loading", "subtitle").apply {
            wrappingWidthProperty().bind(content.widthProperty().subtract(48))
        }
        val header = VBox(7.0, text("JAVAFX SVG  /  FEATURE GALLERY", "eyebrow"),
            text("A closer look at native SVG", "page-title"), subtitle)
        val hint = text("Scroll for all 12 examples and resource loading. Tab + Enter activates controls; Home / End navigates the page.", "hint").apply {
            wrappingWidthProperty().bind(content.widthProperty().subtract(48))
        }
        content.children.setAll(header, toolbar(), hint, cards, resourcePanel())
        viewport = DemoViewport(content)
        val scene = Scene(viewport, 1140.0, 960.0).apply { stylesheets += resource("demo.css") }
        stage.title = "JavaFX SVG · Feature Gallery"
        stage.minWidth = 720.0; stage.minHeight = 600.0
        stage.scene = scene
        selectTheme(0); selectScale(1.0)
        if ("--smoke" in parameters.raw) {
            viewport.resize(1140.0, 960.0)
            savePreview("demo-preview.png")
            selectTheme(1); selectScale(1.2)
            savePreview("demo-preview-themed.png")
            check((themeIcon.lookup("#outline") as Shape).stroke == Color.web(themes[1].color))
            check((themeIcon.lookup("#check") as Shape).stroke == Color.web("#7c3aed"))
            check(previews.all { it.view.diagnostics.isEmpty() }) { previews.flatMap { it.view.diagnostics }.joinToString() }
            selectTheme(2); selectScale(.8)
            viewport.resize(780.0, 700.0)
            savePreview("demo-preview-compact.png")
            viewport.scrollTo(Double.MAX_VALUE)
            check(content.layoutY < 0.0) { "Gallery should scroll at a compact window size" }
            viewport.scrollTo(0.0)
            val local = badge.document
            badge.source = null
            check(badge.document == null)
            badge.source = checkNotNull(javaClass.getResource("/svg/shields-release.svg")).toExternalForm()
            check(badge.document === local)
            check(!badge.isLoading && badge.loadException == null && badge.diagnostics.isEmpty())
            println("SVG gallery smoke checks passed: offline fixtures, shared documents, themes, scale, wrapping, scroll and source reset.")
            Platform.exit()
        } else stage.show()
    }

    private fun document(name: String): SvgDocument =
        SvgLoader.load(checkNotNull(javaClass.getResource("/demo/" + name)), SvgOptions(strict = true))
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/demo/" + name)).toExternalForm()
    private fun fixture(name: String): SvgView = preview(document("showcase-" + name + ".svg"), 252.0, 94.0)
    private fun preview(document: SvgDocument, width: Double, height: Double) = preview(SvgView(document), width, height)
    private fun preview(view: SvgView, width: Double, height: Double): SvgView = view.also {
        it.setMinSize(0.0, 0.0); it.fitWidth = width; it.fitHeight = height; it.setMaxSize(width, height)
        previews += Preview(it, width, height)
    }

    private fun card(title: String, tag: String, graphic: Node, description: String): VBox {
        val spacer = Region().apply { HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }
        val heading = HBox(8.0, text(title, "card-title"), spacer, text(tag, "card-tag")).apply { alignment = Pos.CENTER_LEFT }
        val canvas = StackPane(graphic).apply {
            styleClass += "preview-canvas"
            minHeight = 120.0; prefHeight = 120.0; maxHeight = 120.0; minWidth = 0.0
        }
        val detail = text(description, "card-detail")
        return VBox(10.0, heading, canvas, detail).apply {
            styleClass += "demo-card"; padding = Insets(14.0); prefWidth = 344.0; maxWidth = Double.MAX_VALUE
            minWidth = 0.0
            detail.wrappingWidthProperty().bind(widthProperty().subtract(30.0))
        }
    }

    private fun toolbar(): FlowPane {
        val palette = HBox(7.0, text("Theme", "toolbar-label")).apply { alignment = Pos.CENTER_LEFT }
        themes.forEachIndexed { index, theme ->
            val button = DemoAction(theme.name) { selectTheme(index) }
            themeButtons += button; palette.children += button
        }
        val sizes = HBox(7.0, text("Preview", "toolbar-label")).apply { alignment = Pos.CENTER_LEFT }
        scales.forEach { value ->
            val button = DemoAction((value * 100).toInt().toString() + "%") { selectScale(value) }
            scaleButtons += button; sizes.children += button
        }
        val reset = DemoAction("Reset") {
            selectTheme(0); selectScale(1.0)
            badge.source = checkNotNull(javaClass.getResource("/svg/shields-release.svg")).toExternalForm()
            updateBadgeStatus(); viewport.scrollTo(0.0)
        }
        return FlowPane(18.0, 10.0, palette, sizes, reset).apply { alignment = Pos.CENTER_LEFT }
    }

    private fun selectTheme(index: Int) {
        content.styleClass.removeAll(themes.map { it.css }.toSet())
        content.styleClass += themes[index].css
        themeButtons.forEachIndexed { i, button -> button.selected = i == index }
    }
    private fun selectScale(value: Double) {
        previews.forEach { (view, width, height) ->
            view.fitWidth = width * value; view.fitHeight = height * value; view.setMaxSize(width * value, height * value)
        }
        scaleButtons.forEachIndexed { i, button -> button.selected = scales[i] == value }
    }

    private fun resourcePanel(): VBox {
        badge = SvgView().apply {
            source = checkNotNull(javaClass.getResource("/svg/shields-release.svg")).toExternalForm()
            fitWidth = 218.0; fitHeight = 36.0; setMinSize(0.0, 0.0); setMaxSize(218.0, 36.0)
        }
        badgeStatus = text("", "resource-status")
        val sourceLabel = text("Local fixture by default. Network access starts only when you choose Load network.", "hint")
        val actions = FlowPane(8.0, 8.0,
            DemoAction("Load network") { badge.source = remoteSource; updateBadgeStatus() },
            DemoAction("Local fixture") {
                badge.source = checkNotNull(javaClass.getResource("/svg/shields-release.svg")).toExternalForm()
                updateBadgeStatus()
            },
            DemoAction("Clear") { badge.source = null; updateBadgeStatus() })
        badge.loadingProperty().addListener { _, _, _ -> updateBadgeStatus() }
        badge.loadExceptionProperty().addListener { _, _, _ -> updateBadgeStatus() }
        badge.documentProperty().addListener { _, _, _ -> updateBadgeStatus() }
        updateBadgeStatus()
        val row = FlowPane(20.0, 10.0,
            StackPane(badge).apply { minWidth = 240.0; prefHeight = 48.0; alignment = Pos.CENTER_LEFT },
            actions).apply { alignment = Pos.CENTER_LEFT }
        return VBox(8.0, text("Resource loading", "card-title"), row, badgeStatus, sourceLabel).apply {
            styleClass += "resource-panel"; padding = Insets(16.0)
            minWidth = 0.0
            badgeStatus.wrappingWidthProperty().bind(widthProperty().subtract(34.0))
            sourceLabel.wrappingWidthProperty().bind(widthProperty().subtract(34.0))
        }
    }
    private fun updateBadgeStatus() {
        val failure = badge.loadException
        badgeStatus.text = when {
            badge.isLoading -> "Loading in background… the previous badge stays visible."
            failure != null -> "Load failed; previous content is retained. " + failure.message?.take(140)
            badge.document == null -> "Empty view. Select a local or network resource."
            badge.source == remoteSource -> "Network SVG loaded. Assigning the same URL again starts a fresh remote load."
            else -> "Offline fixture loaded. Local SVG documents use the shared resource cache."
        }
        badgeStatus.styleClass.removeAll("loading", "error")
        if (badge.isLoading) badgeStatus.styleClass += "loading" else if (failure != null) badgeStatus.styleClass += "error"
    }

    /** Example-only Java2D usage writes the PNG; the library runtime does not depend on Java2D. */
    private fun savePreview(name: String) {
        viewport.scrollTo(0.0)
        repeat(3) { content.applyCss(); viewport.requestLayout(); viewport.layout(); content.layout() }
        val snapshot = content.snapshot(null, null)
        check(kotlin.math.abs(snapshot.width - content.width) < 1.0) { "Gallery content overflowed horizontally" }
        val image = BufferedImage(snapshot.width.toInt(), snapshot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, snapshot.pixelReader.getArgb(x, y))
        val output = Path.of("target", name)
        Files.createDirectories(output.parent)
        check(ImageIO.write(image, "png", output.toFile()))
        println("SVG demo snapshot: " + output.toAbsolutePath())
    }
    private fun text(value: String, css: String) = Text(value).apply { styleClass += css }

    private class DemoAction(label: String, private val action: () -> Unit) : StackPane() {
        var selected: Boolean = false
            set(value) { field = value; pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), value) }
        init {
            styleClass += "demo-action"; children += Text(label).apply { styleClass += "action-label" }
            padding = Insets(8.0, 12.0, 8.0, 12.0); cursor = Cursor.HAND; isFocusTraversable = true
            accessibleRole = AccessibleRole.BUTTON; accessibleText = label
            setOnMouseClicked { if (it.button == MouseButton.PRIMARY) { requestFocus(); action(); it.consume() } }
            setOnKeyPressed { if (it.code == KeyCode.ENTER || it.code == KeyCode.SPACE) { action(); it.consume() } }
        }
    }

    /** Graphics-only vertical scrolling, with keyboard navigation and no controls dependency. */
    private class DemoViewport(private val content: VBox) : Region() {
        private val viewportClip = Rectangle()
        private var offset = 0.0
        init {
            content.isManaged = false; children += content; clip = viewportClip
            style = "-fx-background-color: #eef3f9;"
            setOnScroll { scrollTo(offset - it.deltaY); it.consume() }
            setOnKeyPressed {
                val amount = when (it.code) {
                    KeyCode.PAGE_DOWN -> height * .8
                    KeyCode.PAGE_UP -> -height * .8
                    KeyCode.DOWN -> 40.0
                    KeyCode.UP -> -40.0
                    KeyCode.HOME -> -Double.MAX_VALUE
                    KeyCode.END -> Double.MAX_VALUE
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
}

fun main(args: Array<String>) {
    val benchmark = "--benchmark" in args
    val visualCheck = "--cache-visual-check" in args
    require(!benchmark || !visualCheck) { "Choose benchmark or cache visual check" }
    Application.launch(when {
        benchmark -> SvgRenderBenchmark::class.java
        visualCheck -> SvgCacheVisualCheck::class.java
        else -> SvgDemo::class.java
    }, *args.filterNot { it == "--benchmark" || it == "--cache-visual-check" }.toTypedArray())
}
