package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text
import javafx.stage.Screen
import javafx.stage.Stage
import jdk.jfr.Configuration
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

@Name("club.xiaojiawei.svg.RenderBenchmarkPhase")
@Label("SVG render benchmark measurement")
class RenderBenchmarkPhase : Event() {
    @JvmField var scenario: String = ""
    @JvmField var icons: Int = 0
}

/** Manual visible-window workload. Pulse intervals are not GPU timings or presented-frame measurements. */
class SvgRenderBenchmark : Application() {
    private val cases = listOf("path", "gradient", "text", "blur").flatMap { kind ->
        listOf("scroll", "scale", "css").map { motion -> kind to motion }
    }
    private val alternate = PseudoClass.getPseudoClass("alternate")
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val intervals = DoubleArray(100_000)
    private val work = DoubleArray(100_000)
    private val rows = mutableListOf("scenario,icons,samples,pulse_p50_ms,pulse_p95_ms,pulse_p99_ms,pulse_max_ms,over_33ms,over_50ms,work_p95_ms,gc_count,gc_time_ms,unfocused_samples,iconified_samples")
    private lateinit var output: Path
    private lateinit var stage: Stage
    private lateinit var recording: Recording
    private lateinit var timer: AnimationTimer
    private val content = Group()
    private val viewport = Pane(content).apply {
        isManaged = false
        resizeRelocate(20.0, 70.0, 680.0, 480.0)
        clip = Rectangle(680.0, 480.0)
    }
    private val caption = Text(20.0, 28.0, "SVG rendering benchmark")
    private val detail = Text(20.0, 50.0, "")
    private var icons = 200
    private var warmupSeconds = 2.0
    private var measureSeconds = 5.0
    private var caseIndex = -1
    private var caseStart = 0L
    private var measureStart = 0L
    private var previous = 0L
    private var samples = 0
    private var unfocused = 0
    private var iconified = 0
    private var previousWork = 0.0
    private var cssAlternate = false
    private var gcCount = 0L
    private var gcTime = 0L
    private var phase: RenderBenchmarkPhase? = null
    private var views = emptyList<SvgView>()

    override fun start(stage: Stage) {
        this.stage = stage
        fun argument(name: String): String? = parameters.raw.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
        icons = argument("count")?.toInt() ?: icons
        warmupSeconds = argument("warmup-seconds")?.toDouble() ?: warmupSeconds
        measureSeconds = argument("measure-seconds")?.toDouble() ?: measureSeconds
        require(icons in 10..2000 && icons % 10 == 0) { "count must be a multiple of 10, from 10 to 2000" }
        require(warmupSeconds.isFinite() && warmupSeconds in .5..60.0)
        require(measureSeconds.isFinite() && measureSeconds in 1.0..60.0)
        output = Path.of(argument("output") ?: "target/render-benchmark/" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        Files.createDirectories(output)
        // Refuse to overwrite an earlier measurement accidentally.
        check(!Files.exists(output.resolve("results.csv")) && !Files.exists(output.resolve("render.jfr")))
        val root = Pane(viewport, caption, detail).apply { style = "-fx-background-color: #edf2f8;" }
        stage.scene = Scene(root, 720.0, 570.0)
        stage.scene.stylesheets += checkNotNull(javaClass.getResource("/demo/render-benchmark.css")).toExternalForm()
        stage.title = "JavaFX SVG render benchmark — automatic run"
        stage.isResizable = false
        recording = Recording(Configuration.getConfiguration("profile")).apply { name = "SVG render baseline"; start() }
        stage.show()
        Files.writeString(output.resolve("environment.txt"), """
            java=${System.getProperty("java.version")}
            javafx=${System.getProperty("javafx.runtime.version")}
            os=${System.getProperty("os.name")} ${System.getProperty("os.version")}
            processors=${Runtime.getRuntime().availableProcessors()}
            maxHeap=${Runtime.getRuntime().maxMemory()}
            count=$icons; columns=10; tile=64; viewport=680x480; scene=720x570
            warmupSeconds=$warmupSeconds; measureSeconds=$measureSeconds
            screens=${Screen.getScreens().map { "${it.bounds}, dpi=${it.dpi}, scale=${it.outputScaleX}x${it.outputScaleY}" }}
            Pulse interval measures AnimationTimer callbacks, not GPU completion or display presentation.
            Work duration measures only the preceding callback workload. GC time is MXBean collection time, not pause time.
            Scroll translates a clipped grid (no ListView virtualization). Scale resizes every SvgView. CSS toggles the entire grid every pulse.
            path/gradient/blur: 12 paths per icon; text: 4 native Text nodes per icon. All views share one document per case.
            Cases are not equal-complexity comparisons. Keep the window visible and unobscured; focus counts do not detect occlusion.
        """.trimIndent())
        nextCase()
        timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                try { tick(now) } catch (e: Exception) {
                    stop()
                    Files.writeString(output.resolve("failure.txt"), e.stackTraceToString())
                    e.printStackTrace()
                    Platform.exit()
                }
            }
        }
        timer.start()
    }

    private fun nextCase() {
        caseIndex++
        if (caseIndex == cases.size) {
            timer.stop()
            Files.write(output.resolve("results.csv"), rows)
            Files.writeString(output.resolve("completed.txt"), "Completed ${cases.size} scenarios\n")
            println("Render benchmark complete: ${output.toAbsolutePath()}")
            Platform.exit()
            return
        }
        val (kind, motion) = cases[caseIndex]
        val document = SvgLoader.parse(svg(kind), SvgOptions(strict = true))
        views = List(icons) { index -> SvgView(document).apply {
            isManaged = false
            styleClass += kind
            resizeRelocate((index % 10) * 64.0, (index / 10) * 64.0, 48.0, 48.0)
            check(diagnostics.isEmpty()) { diagnostics.toString() }
        } }
        content.translateY = 0.0
        cssAlternate = false
        content.pseudoClassStateChanged(alternate, false)
        content.children.setAll(views)
        caption.text = "${caseIndex + 1}/${cases.size}  $kind / $motion  |  $icons SVG views"
        detail.text = "Warmup ${warmupSeconds}s, measurement ${measureSeconds}s — keep window visible"
        stage.scene.root.applyCss()
        views.forEach { it.layout() }
        if (motion == "scroll") preview(kind)
        samples = 0; unfocused = 0; iconified = 0
        previous = 0; measureStart = 0; caseStart = 0
        phase = null
    }

    private fun tick(now: Long) {
        if (caseStart == 0L) caseStart = now
        val elapsed = (now - caseStart) / 1e9
        if (measureStart == 0L && elapsed >= warmupSeconds) {
            measureStart = now
            gcCount = gcBeans.sumOf { max(0, it.collectionCount) }
            gcTime = gcBeans.sumOf { max(0, it.collectionTime) }
            phase = RenderBenchmarkPhase().also { it.scenario = cases[caseIndex].let { (k, m) -> "$k-$m" }; it.icons = icons; it.begin() }
        }
        if (measureStart != 0L && previous != 0L) {
            check(samples < intervals.size) { "Too many pulse samples" }
            intervals[samples] = (now - previous) / 1e6
            work[samples] = previousWork
            samples++
            if (!stage.isFocused) unfocused++
            if (stage.isIconified) iconified++
        }
        if (measureStart != 0L && (now - measureStart) / 1e9 >= measureSeconds) {
            phase?.end(); phase?.commit()
            finishCase()
            nextCase()
            return
        }
        val started = System.nanoTime()
        when (cases[caseIndex].second) {
            "scroll" -> content.translateY = -max(0.0, (icons / 10) * 64.0 - 480.0) * (1 + sin(elapsed * PI)) / 2
            "scale" -> views.forEach { it.resize(48.0 + 12 * sin(elapsed * PI), 48.0 + 12 * sin(elapsed * PI)) }
            "css" -> {
                cssAlternate = !cssAlternate
                content.pseudoClassStateChanged(alternate, cssAlternate)
            }
        }
        previousWork = (System.nanoTime() - started) / 1e6
        previous = if (measureStart != 0L) now else 0
    }

    private fun finishCase() {
        val name = cases[caseIndex].let { (kind, motion) -> "$kind-$motion" }
        check(samples > 0)
        val sorted = intervals.copyOf(samples).sortedArray()
        val sortedWork = work.copyOf(samples).sortedArray()
        fun percentile(values: DoubleArray, fraction: Double) = values[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceIn(values.indices)]
        val row = String.format(Locale.ROOT, "%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%d,%d,%d,%d",
            name, icons, samples, percentile(sorted, .5), percentile(sorted, .95), percentile(sorted, .99), sorted.last(),
            sorted.count { it > 1000.0 / 30 }, sorted.count { it > 50 }, percentile(sortedWork, .95),
            gcBeans.sumOf { max(0, it.collectionCount) } - gcCount, gcBeans.sumOf { max(0, it.collectionTime) } - gcTime,
            unfocused, iconified)
        rows += row
        Files.write(output.resolve("results.csv"), rows)
        Files.write(output.resolve("$name-pulses.csv"), listOf("interval_ms,preceding_callback_work_ms") +
            (0 until samples).map { String.format(Locale.ROOT, "%.6f,%.6f", intervals[it], work[it]) })
        println(row)
    }

    private fun preview(kind: String) {
        val snapshot = stage.scene.snapshot(null)
        val image = BufferedImage(snapshot.width.toInt(), snapshot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, snapshot.pixelReader.getArgb(x, y))
        check(ImageIO.write(image, "png", output.resolve("$kind-preview.png").toFile()))
    }

    override fun stop() {
        if (::timer.isInitialized) timer.stop()
        if (::recording.isInitialized) {
            recording.stop()
            recording.dump(output.resolve("render.jfr"))
            recording.close()
        }
    }

    private fun svg(kind: String): String {
        val paths = (0 until 12).joinToString("") { index ->
            val x = (index % 3) * 20 + 2
            val y = (index / 3) * 15 + 2
            """<path d="M$x $y h16 v10 h-16 z"/>"""
        }
        val gradient = """<defs><linearGradient id="g"><stop stop-color="#409eff"/><stop offset="1" stop-color="#a855f7"/></linearGradient></defs>"""
        val blur = """<defs><filter id="f" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB"><feGaussianBlur stdDeviation="1.5"/></filter></defs>"""
        val body = when (kind) {
            "gradient" -> "$gradient<g fill=\"url(#g)\">$paths</g>"
            "blur" -> "$blur<g fill=\"#409eff\" filter=\"url(#f)\">$paths</g>"
            "text" -> (0 until 4).joinToString("") { """<text x="2" y="${it * 15 + 12}" font-size="11" fill="#409eff">SVG 123</text>""" }
            else -> "<g fill=\"#409eff\">$paths</g>"
        }
        return "<svg viewBox=\"0 0 64 64\">$body</svg>"
    }
}
