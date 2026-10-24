package club.xiaojiawei.svg.demo

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Two streaming passes keep large recordings out of the Java heap. */
internal fun animationProfileReport(path: Path) {
    data class Phase(val name: String, val start: Instant, val end: Instant,
        val methods: MutableMap<String, Long> = linkedMapOf(), val classes: MutableMap<String, Long> = linkedMapOf(),
        var samples: Long = 0, var allocationSamples: Long = 0)
    val phases = mutableListOf<Phase>()
    RecordingFile(path).use { file ->
        while (file.hasMoreEvents()) {
            val event = file.readEvent()
            if (event.eventType.name == "club.xiaojiawei.svg.AnimationBenchmarkPhase" && event.getBoolean("complete")) {
                phases += Phase(event.getString("scenario") + "-" + event.getInt("icons"), event.startTime, event.endTime)
            }
        }
    }
    require(phases.isNotEmpty()) { "No complete animation measurement phases in $path" }
    fun fxThread(event: RecordedEvent): Boolean {
        val thread = if (event.eventType.name == "jdk.ExecutionSample") event.getThread("sampledThread") else event.thread
        return thread?.javaName == "JavaFX Application Thread"
    }
    RecordingFile(path).use { file ->
        while (file.hasMoreEvents()) {
            val event = file.readEvent()
            if (event.eventType.name !in listOf("jdk.ExecutionSample", "jdk.ObjectAllocationSample") || !fxThread(event)) continue
            val phase = phases.firstOrNull { event.startTime >= it.start && event.startTime <= it.end } ?: continue
            if (event.eventType.name == "jdk.ExecutionSample") {
                phase.samples++
                val frame = event.stackTrace?.frames?.firstOrNull { it.method.type.name.startsWith("club.xiaojiawei.svg.") }
                val method = frame?.let { it.method.type.name + "." + it.method.name } ?: "(outside SVG library)"
                phase.methods[method] = (phase.methods[method] ?: 0) + 1
            } else {
                phase.allocationSamples++
                val type = event.getClass("objectClass").name
                val frame = event.stackTrace?.frames?.firstOrNull { it.method.type.name.startsWith("club.xiaojiawei.svg.") }
                val owner = frame?.let { it.method.type.name + "." + it.method.name } ?: "(outside SVG library)"
                val key = "$type <- $owner"
                phase.classes[key] = (phase.classes[key] ?: 0) + event.getLong("weight")
            }
        }
    }
    val report = buildString {
        appendLine("JFR phase-only analysis: " + path.toAbsolutePath())
        appendLine("Execution counts and allocation weights are statistical samples, not exact CPU/byte attribution.")
        appendLine("Nearest SVG frame is a stack location, not exclusive self time. MXBean CSV provides total allocation counters.")
        for (phase in phases) {
            appendLine("\n" + phase.name + ": FX execution samples=" + phase.samples + ", allocation samples=" + phase.allocationSamples)
            appendLine("Nearest SVG frame (execution sample count):")
            phase.methods.entries.sortedByDescending { it.value }.take(12).forEach { appendLine("  " + it.value + "  " + it.key) }
            appendLine("Allocated class / nearest SVG frame (sample weight bytes):")
            phase.classes.entries.sortedByDescending { it.value }.take(15).forEach { appendLine("  " + it.value + "  " + it.key) }
        }
    }
    val output = path.resolveSibling("jfr-analysis.txt")
    Files.writeString(output, report)
    println(report)
    println("Saved " + output.toAbsolutePath())
}
