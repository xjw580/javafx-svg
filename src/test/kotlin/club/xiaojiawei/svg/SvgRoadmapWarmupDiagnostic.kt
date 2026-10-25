package club.xiaojiawei.svg

import javafx.application.Platform
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.paint.Color
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Preregistered long-warmup diagnostic only; does not replace primary failed calibration. Labels live in the caller, never affect arguments or code paths.
 * Measures software API work, not GPU, display latency, or a performance improvement. */
object SvgRoadmapWarmupDiagnostic {
    @Volatile private var sink = 0L
    private const val WARMUP = 300
    private const val SAMPLES = 60
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty()) { "Calibration takes no arguments; blind labels must not alter the process" }
        val ready=CountDownLatch(1)
        Platform.startup {Platform.setImplicitExit(false);ready.countDown()}
        check(ready.await(30,TimeUnit.SECONDS))
        val task=FutureTask {runOnFxThread()}
        Platform.runLater(task)
        try {task.get(180,TimeUnit.SECONDS)} finally {Platform.exit()}
    }
    private fun runOnFxThread() {
        val icons=listOf("activity","airplay","atom","cpu").map {checkNotNull(javaClass.getResource("/svg/real-corpus/$it.svg")).readText()}
        val filter="""<svg width='64' height='64' viewBox='0 0 64 64'><defs><filter id='f' color-interpolation-filters='sRGB'><feComponentTransfer><feFuncR type='gamma' exponent='1.3'/></feComponentTransfer><feColorMatrix type='saturate' values='.7'/></filter></defs><rect x='8' y='8' width='48' height='48' rx='8' fill='#7ab3cf' filter='url(#f)'/></svg>"""
        val xmls=icons+filter
        val host=Group();Scene(host)
        val params=SnapshotParameters().apply{fill=Color.TRANSPARENT}
        val options=SvgOptions(strict=true)
        val gc=ManagementFactory.getGarbageCollectorMXBeans()
        fun gcCount()=gc.sumOf{it.collectionCount.coerceAtLeast(0)}
        fun used()=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
        var sequence=0
        fun measure(name:String,batch:Int,action:()->Unit) {
            repeat(WARMUP){repeat(batch){action()}}
            val before=gcCount()
            repeat(SAMPLES){sample->
                val sampleGc=gcCount();val heapBefore=used()
                val start=System.nanoTime();repeat(batch){action()};val elapsed=System.nanoTime()-start
                println("sample,$name,$sample,$batch,$elapsed,${elapsed.toDouble()/batch}")
                println("sample_observation,$name,$sample,${gcCount()-sampleGc},$heapBefore,${used()}")
            }
            println("observation,$name,gc_delta,${gcCount()-before},heap_used,${used()}")
        }
        println("protocol,warmup_batches,$WARMUP,sample_batches,$SAMPLES,renderer,software")
        println("environment,java,${System.getProperty("java.version")},os,${System.getProperty("os.name")},arch,${System.getProperty("os.arch")},processors,${Runtime.getRuntime().availableProcessors()}")
        measure("parse_corpus",32) {
            val doc=SvgLoader.parse(icons[sequence++%icons.size],options)
            sink+=doc.root.children.size
        }
        measure("create_snapshot",8) {
            val v=SvgView.parse(xmls[sequence++%xmls.size],options)
            v.isManaged=false;v.animationAutoPlay=false;host.children.setAll(v);v.resize(64.0,64.0);v.applyCss();v.layout()
            val image=v.snapshot(params,null);sink+=image.pixelReader.getArgb(32,32).toLong()
            v.source=null;host.children.clear()
        }
        val cached=SvgView.parse(icons[0],options).apply {isManaged=false;animationAutoPlay=false}
        host.children.setAll(cached);cached.resize(64.0,64.0);cached.rotate=1.0;cached.rotate=0.0;cached.applyCss();cached.layout()
        measure("repeat_snapshot",16) {sink+=cached.snapshot(params,null).pixelReader.getArgb(32,32).toLong()}
        measure("resize_snapshot",8) {
            val size=if(sequence++%2==0)64.0 else 72.0
            cached.resize(size,size);cached.applyCss();cached.layout()
            sink+=cached.snapshot(params,null).pixelReader.getArgb(32,32).toLong()
        }
        check(cached.diagnostics.isEmpty())
        cached.source=null;host.children.clear()
        println("checksum,$sink")
        println("load_average,${ManagementFactory.getOperatingSystemMXBean().systemLoadAverage}")
    }
}
