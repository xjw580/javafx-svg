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

/** A five-minute bounded continuous-edit observation, separate from deterministic unit tests. */
object SvgRoadmapSustainedEdit {
    @JvmStatic fun main(args:Array<String>) {
        val seconds=args.singleOrNull()?.toInt() ?: 300
        require(seconds in 30..1800)
        val ready=CountDownLatch(1);Platform.startup{Platform.setImplicitExit(false);ready.countDown()};check(ready.await(30,TimeUnit.SECONDS))
        fun fx(action:()->Unit) {val task=FutureTask{action()};Platform.runLater(task);task.get(30,TimeUnit.SECONDS)}
        lateinit var view:SvgView;lateinit var left:Group;lateinit var right:Group
        val corpus=listOf("activity","airplay","atom","cpu").map {checkNotNull(javaClass.getResource("/svg/real-corpus/$it.svg")).readText()}
        val cases=corpus+listOf(
            """<svg width='32' height='32' viewBox='0 0 32 32'><defs><filter id='f' x='0' y='0' width='100%' height='100%'><feComponentTransfer><feFuncR type='gamma' exponent='1.2'/></feComponentTransfer><feConvolveMatrix order='3' kernelMatrix='0 -1 0 -1 5 -1 0 -1 0' preserveAlpha='true'/></filter></defs><rect x='4' y='4' width='24' height='24' rx='4' fill='#789abc' filter='url(#f)'/></svg>""",
            """<svg width='32' height='32' viewBox='0 0 32 32'><defs><radialGradient id='g' fr='.12' gradientTransform='translate(.05 .02) scale(.9 .8)'><stop stop-color='gold'/><stop offset='1' stop-color='navy'/></radialGradient></defs><rect width='32' height='32' fill='url(#g)'/></svg>""",
            """<svg width='32' height='32' viewBox='0 0 32 32'><text x='2 12 22' y='24' rotate='0 10 -10' font-size='12' fill='#225599'>SVG</text></svg>""",
            """<svg width='32' height='32' viewBox='0 0 32 32'><defs><filter id='f'><feGaussianBlur stdDeviation='0'><animate attributeName='stdDeviation' values='0;2;0' dur='2s' repeatCount='indefinite'/></feGaussianBlur></filter></defs><circle cx='16' cy='16' r='9' fill='teal' filter='url(#f)'/></svg>""",
            """<svg width='32' height='32' viewBox='0 0 32 32'><defs><filter id='f' filterUnits='userSpaceOnUse' x='0' y='0' width='32' height='32'><feFlood x='0' y='0' width='8' height='8' flood-color='#cc9955'/><feTile/></filter></defs><rect width='32' height='32' filter='url(#f)'/></svg>"""
        )
        val params=SnapshotParameters().apply{fill=Color.TRANSPARENT};val options=SvgOptions(strict=true,maxCachePixels=4096)
        var edits=0;var checksum=0L;var maxNodes=0
        fx {view=SvgView.parse(cases[0],options);view.isManaged=false;view.animationAutoPlay=false;left=Group(view);right=Group();Scene(Group(left,right));view.resize(48.0,48.0);view.layout()}
        val gc=ManagementFactory.getGarbageCollectorMXBeans();fun count()=gc.sumOf{it.collectionCount.coerceAtLeast(0)}
        val gcStart=count();val start=System.nanoTime();var next=start
        println("protocol,duration_seconds,$seconds,batch_edits,10,target_batch_ms,500,no_forced_gc,true")
        println("elapsed_seconds,edits,heap_used_bytes,heap_committed_bytes,gc_count,max_scene_nodes,checksum")
        try {
            while((System.nanoTime()-start)/1e9 < seconds) {
                fx {
                    repeat(10) {
                        val i=edits++
                        val retired=view.lookupAll("*").filter{it!==view&&it.isCache}
                        view.document=SvgLoader.parse(cases[i%cases.size],options)
                        check(retired.none{it.isCache}){"Retired cache flags at edit $i"}
                        val size=listOf(32.0,48.0,64.0)[i%3];view.resize(size,size)
                        if(i%5==0) {
                            (view.parent as Group).children.remove(view)
                            check(view.lookupAll("*").none{it!==view&&it.isCache}){"Detached cache flags at edit $i"}
                            (if(i%10==0)right else left).children+=view
                        }
                        view.rotate=1.0;view.rotate=0.0;view.seekAnimations(javafx.util.Duration.seconds((i%20)*.1));view.applyCss();view.layout()
                        val image=view.snapshot(params,null);checksum+=image.pixelReader.getArgb(image.width.toInt()/2,image.height.toInt()/2).toLong()
                        check(view.diagnostics.isEmpty()){view.diagnostics.toString()}
                        val nodes=view.lookupAll("*").size;check(nodes<150){"Scene grew to $nodes nodes"};maxNodes=maxOf(maxNodes,nodes)
                    }
                }
                val runtime=Runtime.getRuntime()
                println("${(System.nanoTime()-start)/1e9},$edits,${runtime.totalMemory()-runtime.freeMemory()},${runtime.totalMemory()},${count()-gcStart},$maxNodes,$checksum")
                next+=500_000_000L
                val sleep=next-System.nanoTime();if(sleep>0)TimeUnit.NANOSECONDS.sleep(sleep)
            }
            check(edits>=seconds*5){"Too few edits for observation window: $edits"}
            fx {view.source=null;(view.parent as Group).children.remove(view)}
            println("completed,$edits,elapsed_seconds,${(System.nanoTime()-start)/1e9}")
        } finally {Platform.exit()}
    }
}
