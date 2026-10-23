package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import java.nio.file.Path

/** Example-only Java2D usage writes the smoke-test PNG; the library has no Java2D/Swing dependency. */
class SvgDemo : Application() {
    override fun start(stage: Stage) {
        val directoryIndex = parameters.raw.indexOf("--svg-dir")
        if ("--javafx-ui" in parameters.raw || directoryIndex >= 0) {
            val directory = if (directoryIndex >= 0) {
                val value = parameters.raw.getOrNull(directoryIndex + 1)
                require(value != null && !value.startsWith("--")) { "--svg-dir requires a directory path" }
                Path.of(value).toAbsolutePath().normalize()
            } else SvgGallery.defaultDirectory()
            val gallery = SvgGallery(directory)
            stage.title = "JavaFX SVG — ${directory.fileName}"
            stage.scene = Scene(gallery.root, SvgGallery.WIDTH, SvgGallery.HEIGHT).apply {
                stylesheets += checkNotNull(javaClass.getResource("/demo/gallery.css")).toExternalForm()
            }
            if ("--smoke" in parameters.raw) {
                try { gallery.export(Path.of("target", "svg-gallery")) }
                finally { Platform.exit() }
            } else {
                stage.minWidth = SvgGallery.WIDTH
                stage.minHeight = SvgGallery.HEIGHT
                stage.show()
            }
            return
        }
        val source = checkNotNull(javaClass.getResource("/demo/sample.svg"))
        val document = SvgLoader.load(source, SvgOptions(strict = true))
        fun caption(value: String, size: Double, color: String) =
            Text(value).apply { font = Font.font("System", size); fill = Color.web(color) }
        fun card(title: String, detail: String, themed: Boolean): VBox {
            val view = SvgView(document).apply {
                fitWidth = 180.0
                fitHeight = 180.0
                setMaxSize(180.0, 180.0)
                if (themed) styleClass += "themed"
            }
            return VBox(16.0, caption(title, 20.0, "#172b4d"), view,
                caption(detail, 13.0, "#53657d")).apply {
                alignment = Pos.CENTER
                padding = Insets(24.0)
                style = "-fx-background-color: white; -fx-background-radius: 16;"
            }
        }
        val cards = HBox(24.0, card("SVG original", "Gradient + path + symbol", false),
            card("JavaFX CSS", "Hover to change the path color", true)).apply { alignment = Pos.CENTER }
        val root = VBox(14.0, caption("Native SVG for JavaFX", 28.0, "#172b4d"),
            caption("One document. Independent nodes. CSS styling.", 14.0, "#53657d"), cards).apply {
            alignment = Pos.TOP_CENTER
            padding = Insets(30.0)
            style = "-fx-background-color: #edf2f8;"
        }
        val scene = Scene(root, 760.0, 440.0)
        scene.stylesheets += checkNotNull(javaClass.getResource("/demo/demo.css")).toExternalForm()
        stage.title = "JavaFX SVG"
        stage.scene = scene
        if ("--smoke" in parameters.raw) {
            root.resize(760.0, 440.0)
            root.applyCss()
            root.layout()
            val snapshot = root.snapshot(null, null)
            val output = Path.of("target", "demo-preview.png")
            writeDemoSnapshot(snapshot, output)
            println("SVG demo snapshot: ${output.toAbsolutePath()}")
            Platform.exit()
        } else {
            stage.show()
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(SvgDemo::class.java, *args)
}
