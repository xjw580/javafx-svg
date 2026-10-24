package club.xiaojiawei.svg.demo

import club.xiaojiawei.svg.SvgLoader
import club.xiaojiawei.svg.SvgOptions
import club.xiaojiawei.svg.SvgView
import javafx.animation.Animation
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.text.Text
import javafx.stage.Screen
import javafx.stage.Stage
import jdk.jfr.Configuration
import jdk.jfr.Event
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
import kotlin.math.ceil
import kotlin.math.max

@Name("club.xiaojiawei.svg.AnimationBenchmarkPhase")
class AnimationBenchmarkPhase : Event() {
    @JvmField var scenario = ""
    @JvmField var icons = 0
    @JvmField var complete = false
}

internal val animationBenchmarkKinds = listOf("smil-transform", "smil-geometry", "css-transform", "css-paint", "css-fixed", "paused")

/** Manual workload: all 500 icons fit in the visible window. No external per-frame mutations. */
class SvgAnimationBenchmark : Application() {
    private val threads = ManagementFactory.getThreadMXBean()
    private val allocations = threads as? com.sun.management.ThreadMXBean
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val gc = ManagementFactory.getGarbageCollectorMXBeans()
    private data class Counters(val wall: Long, val cpu: Long, val fxCpu: Long, val allocated: Long, val fxAllocated: Long, val collections: Long, val gcMillis: Long)
    private fun counters() = Counters(System.nanoTime(), os?.processCpuTime ?: -1, if (threads.isCurrentThreadCpuTimeSupported) threads.currentThreadCpuTime else -1,
        allocations?.totalThreadAllocatedBytes ?: -1, allocations?.currentThreadAllocatedBytes ?: -1,
        gc.sumOf { max(0, it.collectionCount) }, gc.sumOf { max(0, it.collectionTime) })
    private lateinit var stage: Stage
    private lateinit var output: Path
    private lateinit var recording: Recording
    private lateinit var timer: AnimationTimer
    private lateinit var before: Counters
    private val content = Group()
    private val caption = Text(20.0, 28.0, "")
    private val detail = Text(20.0, 50.0, "")
    private var views = emptyList<SvgView>()
    private var cases = emptyList<Pair<String, Int>>()
    private var index = -1
    private var warmup = 2.0
    private var measure = 5.0
    private var caseStart = 0L
    private var previous = 0L
    private var samples = 0
    private var unfocused = 0
    private var iconified = 0
    private var complete = false
    private var phase: AnimationBenchmarkPhase? = null
    private val intervals = DoubleArray(100_000)
    private val rows = mutableListOf("scenario,icons,samples,seconds,pulse_p50_ms,pulse_p95_ms,pulse_p99_ms,pulse_max_ms,over_33ms,over_50ms,process_cpu_core_pct,fx_cpu_core_pct,fx_cpu_ms_per_pulse,total_alloc_mib_s,fx_alloc_mib_s,fx_alloc_bytes_per_pulse,gc_count,gc_time_ms,unfocused_samples,iconified_samples")

    override fun start(stage: Stage) {
        this.stage = stage
        fun argument(key: String) = parameters.raw.firstOrNull { it.startsWith("--$key=") }?.substringAfter('=')
        val kinds = argument("kinds")?.split(',') ?: animationBenchmarkKinds
        val counts = argument("counts")?.split(',')?.map(String::toInt) ?: listOf(100, 500)
        require(kinds.isNotEmpty() && kinds.distinct() == kinds && kinds.all { it in animationBenchmarkKinds })
        require(counts.isNotEmpty() && counts.distinct() == counts && counts.all { it in 1..500 })
        warmup = argument("warmup-seconds")?.toDouble() ?: warmup
        measure = argument("measure-seconds")?.toDouble() ?: measure
        require(warmup.isFinite() && warmup in .5..30.0 && measure.isFinite() && measure in 1.0..60.0)
        cases = counts.flatMap { count -> kinds.map { it to count } }
        output = Path.of(argument("output") ?: "target/animation-benchmark/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        Files.createDirectories(output)
        check(!Files.exists(output.resolve("results.csv")) && !Files.exists(output.resolve("animation.jfr"))) { "Choose a fresh output directory" }
        if (threads.isThreadCpuTimeSupported) threads.isThreadCpuTimeEnabled = true
        allocations?.takeIf { it.isThreadAllocatedMemorySupported }?.isThreadAllocatedMemoryEnabled = true
        val root = Pane(content, caption, detail).apply { style = "-fx-background-color: #edf2f8;" }
        content.layoutX = 20.0; content.layoutY = 72.0
        stage.scene = Scene(root, 740.0, 650.0)
        stage.title = "SVG animation benchmark - automatic run"
        stage.isResizable = false
        recording = Recording(Configuration.getConfiguration("profile")).apply {
            name = "SVG animation benchmark"
            enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "300/s")
            start()
        }
        stage.show()
        Files.writeString(output.resolve("environment.txt"), listOf(
            "java=" + System.getProperty("java.version"), "javafx=" + System.getProperty("javafx.runtime.version"),
            "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"),
            "processors=" + Runtime.getRuntime().availableProcessors(), "maxHeap=" + Runtime.getRuntime().maxMemory(),
            "jvmArgs=" + ManagementFactory.getRuntimeMXBean().inputArguments,
            "pulse=" + System.getProperty("javafx.animation.pulse", "default"),
            "counts=$counts; kinds=$kinds; warmup=$warmup; measure=$measure",
            "scene=740x650; columns=25; tile=28; icon=24; all icons visible; cache=off; one shared document per case",
            "screens=" + Screen.getScreens().map { it.bounds.toString() + ", dpi=" + it.dpi + ", scale=" + it.outputScaleX },
            "Pulse intervals are AnimationTimer intervals, not GPU completion or presented FPS.",
            "CPU percent is equivalent utilization of ONE core; process CPU can exceed 100%. -1 means unavailable.",
            "Allocated bytes are approximate MXBean counters; GC milliseconds are collection time, not pauses.",
            "Paused uses the CSS paint document with autoplay disabled; the benchmark observer still drives pulses.",
            "Case setup, snapshots and warmup are outside complete JFR measurement phases.",
            "Different kinds have different complexity; compare the same kind/count across runs.",
            "Keep the window visible and unobscured. Focus and iconified counts cannot detect occlusion."
        ).joinToString("\n"))
        timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                try { tick(now) } catch (error: Exception) {
                    stop()
                    Files.writeString(output.resolve("failure.txt"), error.stackTraceToString())
                    error.printStackTrace()
                    Platform.exit()
                }
            }
        }
        nextCase()
        timer.start()
    }

    private fun nextCase() {
        views.forEach { it.source = null }
        content.children.clear()
        views = emptyList()
        index++
        if (index == cases.size) {
            complete = true
            timer.stop()
            Platform.exit()
            return
        }
        val (kind, count) = cases[index]
        val document = SvgLoader.parse(animationBenchmarkSvg(kind), SvgOptions(strict = true))
        views = List(count) { n -> SvgView().apply {
            animationAutoPlay = kind != "paused"
            this.document = document
            isManaged = false
            resizeRelocate((n % 25) * 28.0, (n / 25) * 28.0, 24.0, 24.0)
            check(diagnostics.isEmpty()) { diagnostics.toString() }
        } }
        content.children.setAll(views)
        caption.text = (index + 1).toString() + "/" + cases.size + "  $kind | $count animated SVG views"
        detail.text = "Warmup " + warmup + "s, measure " + measure + "s - keep window visible"
        stage.scene.root.applyCss()
        stage.scene.root.layout()
        val snapshot = stage.scene.snapshot(null)
        val image = BufferedImage(snapshot.width.toInt(), snapshot.height.toInt(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, snapshot.pixelReader.getArgb(x, y))
        check(ImageIO.write(image, "png", output.resolve("$kind-$count.png").toFile()))
        caseStart = 0; previous = 0; samples = 0; unfocused = 0; iconified = 0; phase = null
    }

    private fun tick(now: Long) {
        if (caseStart == 0L) caseStart = now
        if (phase == null) {
            if ((now - caseStart) / 1e9 < warmup) return
            val paused = cases[index].first == "paused"
            check(views.all { it.animationStatus == if (paused) Animation.Status.STOPPED else Animation.Status.RUNNING })
            phase = AnimationBenchmarkPhase().also {
                it.scenario = cases[index].first; it.icons = cases[index].second; it.begin()
            }
            before = counters()
            previous = now
            return
        }
        check(samples < intervals.size)
        intervals[samples++] = (now - previous) / 1e6
        previous = now
        if (!stage.isFocused) unfocused++
        if (stage.isIconified) iconified++
        if ((System.nanoTime() - before.wall) / 1e9 < measure) return
        val after = counters()
        phase!!.end(); phase!!.complete = true; phase!!.commit()
        if (cases[index].first == "paused") check(views.all { it.animationTime.toMillis() == 0.0 })
        finishCase(after)
        nextCase()
    }

    private fun finishCase(after: Counters) {
        val (kind, count) = cases[index]
        check(samples > 0)
        val seconds = (after.wall - before.wall) / 1e9
        fun delta(end: Long, start: Long) = if (end < 0 || start < 0) -1.0 else (end - start).toDouble()
        fun rate(end: Long, start: Long, divisor: Double) = delta(end, start).let { if (it < 0) -1.0 else it / divisor }
        val sorted = intervals.copyOf(samples).sortedArray()
        fun percentile(fraction: Double) = sorted[(ceil(samples * fraction).toInt() - 1).coerceIn(sorted.indices)]
        val values = listOf(kind, count, samples, seconds, percentile(.5), percentile(.95), percentile(.99), sorted.last(),
            sorted.count { it > 1000.0 / 30 }, sorted.count { it > 50 },
            rate(after.cpu, before.cpu, seconds * 1e7), rate(after.fxCpu, before.fxCpu, seconds * 1e7),
            rate(after.fxCpu, before.fxCpu, samples * 1e6), rate(after.allocated, before.allocated, seconds * 1048576),
            rate(after.fxAllocated, before.fxAllocated, seconds * 1048576), rate(after.fxAllocated, before.fxAllocated, samples.toDouble()),
            after.collections - before.collections, after.gcMillis - before.gcMillis, unfocused, iconified)
        val row = values.joinToString(",") { if (it is Double) String.format(Locale.ROOT, "%.6f", it) else it.toString() }
        rows += row
        Files.write(output.resolve("results.csv"), rows)
        Files.write(output.resolve("$kind-$count-pulses.csv"), listOf("interval_ms") +
            (0 until samples).map { String.format(Locale.ROOT, "%.6f", intervals[it]) })
        println(row)
    }

    override fun stop() {
        if (::timer.isInitialized) timer.stop()
        views.forEach { it.source = null }
        if (::recording.isInitialized) {
            recording.stop()
            recording.dump(output.resolve("animation.jfr"))
            recording.close()
        }
        if (::output.isInitialized) Files.writeString(output.resolve(if (complete) "completed.txt" else "aborted.txt"),
            if (complete) "Completed " + cases.size + " scenarios; recording saved.\n" else "Incomplete run; do not use as a baseline.\n")
    }
}

internal fun animationBenchmarkSvg(kind: String): String {
    val paths = (0 until 4).joinToString("") {
        val x = (it % 2) * 30 + 8
        val y = (it / 2) * 30 + 8
        """<path id="p$it" d="M$x $y h18 v18 h-18 z"/>"""
    }
    val body = when (kind) {
        "smil-transform" -> """<g fill="#2563eb">$paths<animateTransform attributeName="transform" type="rotate" from="0 32 32" to="360 32 32" dur="1.5s" repeatCount="indefinite"/></g>"""
        "css-transform" -> """<style>@keyframes k {from {transform:rotate(0deg)} to {transform:rotate(360deg)}} .a {animation:k 1.5s linear infinite;transform-origin:32px 32px}</style><g class="a" fill="#2563eb">$paths</g>"""
        "smil-geometry" -> (0 until 4).joinToString("") {
            val x = (it % 2) * 30 + 17
            val y = (it / 2) * 30 + 17
            """<circle id="p$it" cx="$x" cy="$y" r="4" fill="#2563eb"><animate attributeName="r" values="4;10;4" dur="1.5s" repeatCount="indefinite"/></circle>"""
        }
        "css-paint", "paused" -> """<style>@keyframes k {from {fill:#2563eb} to {fill:#db2777}} path {animation:k 1.5s linear infinite alternate}</style>$paths"""
        "css-fixed" -> """<style>@keyframes k {from {transform:scale(.85)} to {transform:scale(1.15)}} .a {animation:k 1.5s linear infinite alternate;transform-origin:32px 32px}</style><g class="a"><rect id="p0" x="16" y="20" width="24" height="24" rx="4" fill="none" stroke="#2563eb" stroke-width="2" vector-effect="non-scaling-stroke"><animate attributeName="width" values="24;40;24" dur="1.5s" repeatCount="indefinite"/></rect></g>"""
        else -> error("Unknown animation kind: $kind")
    }
    return """<svg viewBox="0 0 64 64">$body</svg>"""
}
