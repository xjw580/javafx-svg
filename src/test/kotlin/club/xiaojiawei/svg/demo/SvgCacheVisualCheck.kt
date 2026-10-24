package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.scene.CacheHint
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.WritableImage
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.shape.SVGPath
import javafx.scene.text.Text
import javafx.scene.transform.Scale
import javafx.stage.Stage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

/** Warm bitmap caches on a visible window, then compare output after natural pulses. */
class SvgCacheVisualCheck : Application() {
    private data class State(val name: String, val factor: Double, val alternate: Boolean = false)
    private val states = listOf(State("native", 1.0), State("enlarge", 2.0), State("shrink", .75),
        State("fractional", 1.25), State("css", 1.25, true), State("restored", 1.25))
    private val modes = listOf("off", "scale", "quality")
    private val kinds = listOf("gradient", "text")
    private val views = mutableListOf<SvgView>()
    private val scales = mutableListOf<Scale>()
    private val rows = mutableListOf("state,cache_mode,fixture,mean_absolute_rgb_difference,max_rgb_difference")
    private lateinit var output: Path
    private lateinit var root: Pane
    private lateinit var timer: AnimationTimer
    private var stateIndex = 0
    private var pulses = 0

    override fun start(stage: Stage) {
        output = Path.of(parameters.raw.firstOrNull { it.startsWith("--output=") }?.substringAfter('=')
            ?: "target/cache-visual-check")
        Files.createDirectories(output)
        check(Files.list(output).use { it.findAny().isEmpty }) { "Use an empty output directory" }
        root = Pane().apply { style = "-fx-background-color: white;" }
        val docs = kinds.associateWith { SvgLoader.parse(renderBenchmarkSvg(it), SvgOptions(strict = true)) }
        modes.forEachIndexed { column, mode ->
            root.children += Text(20.0 + 220 * column, 30.0, "cache=$mode")
            kinds.forEachIndexed { row, kind ->
                val view = SvgView(docs.getValue(kind)).apply {
                    isManaged = false
                    styleClass += kind
                    resizeRelocate(20.0 + 220 * column, 90.0 + 150 * row, 48.0, 48.0)
                    cacheHint = if (mode == "quality") CacheHint.QUALITY else CacheHint.SCALE
                    isCache = mode != "off"
                }
                val scale = Scale(1.0, 1.0, 0.0, 0.0)
                view.transforms += scale
                views += view; scales += scale; root.children += view
            }
        }
        stage.scene = Scene(root, 720.0, 430.0).apply {
            stylesheets += checkNotNull(this@SvgCacheVisualCheck.javaClass.getResource("/demo/render-benchmark.css")).toExternalForm()
        }
        stage.title = "SVG cache visual comparison"
        stage.isResizable = false
        stage.show()
        Files.writeString(output.resolve("environment.txt"),
            "outputScale=${stage.outputScaleX}x${stage.outputScaleY}; caches warm at scale=1 before enlargement\n")
        timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                try {
                    if (++pulses < 30) return
                    capture(stage, states[stateIndex])
                    stateIndex++
                    if (stateIndex == states.size) {
                        stop()
                        Files.write(output.resolve("checks.csv"), rows)
                        Files.writeString(output.resolve("completed.txt"), "Cache CSS and center-color checks passed\n")
                        Platform.exit()
                    } else {
                        val state = states[stateIndex]
                        scales.forEach { it.x = state.factor; it.y = state.factor }
                        root.pseudoClassStateChanged(PseudoClass.getPseudoClass("alternate"), state.alternate)
                        pulses = 0
                    }
                } catch (e: Exception) {
                    stop()
                    Files.writeString(output.resolve("failure.txt"), e.stackTraceToString())
                    e.printStackTrace()
                    Platform.exit()
                }
            }
        }
        timer.start()
    }

    private fun capture(stage: Stage, state: State) {
        val sx = stage.outputScaleX
        val sy = stage.outputScaleY
        val parameters = SnapshotParameters().apply {
            fill = Color.WHITE
            transform = Scale(sx, sy)
        }
        val image = root.snapshot(parameters,
            WritableImage((720 * sx).roundToInt(), (430 * sy).roundToInt()))
        val pixels = image.pixelReader
        // Verify CSS reaches the live shapes as well as the already-cached image.
        views.filterIndexed { index, _ -> index % 2 == 0 }.forEach { view ->
            val fill = (view.lookup(".svg-path") as SVGPath).fill as LinearGradient
            val expected = Color.web(if (state.alternate) "#e87924" else "#409eff")
            check(fill.stops.first().color == expected) { "Unexpected CSS fill in ${state.name}" }
        }
        for (column in 1..2) for (row in 0..1) {
            val left = (20 * sx).roundToInt()
            val right = ((20 + 220 * column) * sx).roundToInt()
            val top = ((90 + 150 * row) * sy).roundToInt()
            var total = 0.0
            var maximum = 0.0
            val width = (128 * sx).roundToInt()
            val height = (128 * sy).roundToInt()
            for (y in 0 until height) for (x in 0 until width) {
                val a = pixels.getColor(left + x, top + y)
                val b = pixels.getColor(right + x, top + y)
                val differences = doubleArrayOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
                total += differences.sum()
                maximum = maxOf(maximum, differences.max())
            }
            rows += String.format(Locale.ROOT, "%s,%s,%s,%.6f,%.6f", state.name, modes[column], kinds[row],
                total / (width * height * 3), maximum)
            if (row == 0) {
                // Interior samples exclude antialiasing edges; a stale CSS bitmap fails these checks.
                for (r in 0 until 4) for (c in 0 until 3) {
                    val x = ((c * 20 + 10) * .75 * state.factor * sx).roundToInt()
                    val y = ((r * 15 + 7) * .75 * state.factor * sy).roundToInt()
                    val a = pixels.getColor(left + x, top + y)
                    val b = pixels.getColor(right + x, top + y)
                    check(maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) < .06) {
                        "Cached gradient differs at an interior pixel: ${state.name}/${modes[column]}"
                    }
                }
            }
        }
        val png = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until png.height) for (x in 0 until png.width) png.setRGB(x, y, pixels.getArgb(x, y))
        check(ImageIO.write(png, "png", output.resolve("${state.name}.png").toFile()))
    }

    override fun stop() {
        if (::timer.isInitialized) timer.stop()
    }
}
