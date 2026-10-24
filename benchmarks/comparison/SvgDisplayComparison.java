package svgbench;

import club.xiaojiawei.svg.SvgView;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.*;
import javafx.scene.image.*;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.girod.javafx.svgimage.LoaderParameters;
import org.girod.javafx.svgimage.SVGImage;
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas;
import com.github.weisj.jsvg.parser.LoaderContext;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import jdk.jfr.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Complete offscreen raster readiness and timer-free idle measurement. Not presentation latency. */
public final class SvgDisplayComparison extends Application {
    @Name("club.xiaojiawei.svg.RasterBenchmarkPhase")
    public static final class RasterPhase extends Event {
        public String provider, kind;
        public int batch, icons;
        public boolean warmup, complete;
    }
    private Recording recording;
    private RasterPhase rasterPhase;
    private final com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "svg-idle-observer"); t.setDaemon(true); return t;
    });
    private final List<Node> nodes = new ArrayList<>();
    private final List<String> ready = new ArrayList<>(List.of(
        "provider,kind,batch,warmup,icons,wall_ms,fx_cpu_ms,process_cpu_ms,fx_alloc_mib,total_alloc_mib,gc_count,gc_ms"));
    private final List<String> idle = new ArrayList<>(List.of(
        "provider,kind,icons,seconds,fx_cpu_ms_s,process_cpu_core_pct,fx_alloc_kib_s,total_alloc_kib_s,gc_count,gc_ms,focus_start,focus_end,focus_losses,iconified_start,iconified_end"));
    private final List<String> steps = new ArrayList<>(List.of(
        "provider,kind,batch,warmup,build_ms,css_layout_ms,snapshot_ready_ms,build_fx_cpu_ms,css_fx_cpu_ms,snapshot_fx_cpu_ms,build_fx_mib,css_fx_mib,snapshot_fx_mib"));
    private String provider;
    private List<String> kinds;
    private Path output;
    private Stage stage;
    private Pane root;
    private Scene scene;
    private int count, warmups, measurements, kindIndex, batch, measuredBatches, focusLosses;
    private double warmupSeconds, measureSeconds, rasterWarmupSeconds;
    private long rasterWarmupStarted;
    private boolean warmupBatch;
    private long fxId;
    private boolean measuringIdle, completed;
    private Counters start;
    private record Counters(long wall,long cpu,long fxCpu,long allocated,long fxAllocated,long gc,long gcMs) {}
    private Counters counters() {
        var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        return new Counters(System.nanoTime(),os.getProcessCpuTime(),threads.getThreadCpuTime(fxId),
            threads.getTotalThreadAllocatedBytes(),threads.getThreadAllocatedBytes(fxId),
            collectors.stream().mapToLong(g -> Math.max(0,g.getCollectionCount())).sum(),
            collectors.stream().mapToLong(g -> Math.max(0,g.getCollectionTime())).sum());
    }
    private String arg(String key,String fallback) {
        return getParameters().getRaw().stream().filter(s -> s.startsWith("--"+key+"="))
            .map(s -> s.substring(s.indexOf('=')+1)).findFirst().orElse(fallback);
    }
    private static String n(double value) { return String.format(Locale.ROOT,"%.6f",value); }
    @Override public void start(Stage stage) throws Exception {
        Platform.setImplicitExit(false);
        this.stage=stage; fxId=Thread.currentThread().threadId();
        provider=arg("provider","native");
        if(!List.of("native","native-before","fxsvgimage","jsvg-fx").contains(provider))throw new IllegalArgumentException("Provider");
        kinds=List.of(arg("kinds","path,gradient").split(","));
        if(kinds.stream().anyMatch(k -> !List.of("path","gradient").contains(k)))throw new IllegalArgumentException("Kinds");
        count=Integer.parseInt(arg("count","500")); warmups=Integer.parseInt(arg("warmups","5"));
        measurements=Integer.parseInt(arg("measurements","5"));
        warmupSeconds=Double.parseDouble(arg("idle-warmup","3")); measureSeconds=Double.parseDouble(arg("idle-measure","5"));
        rasterWarmupSeconds=Double.parseDouble(arg("raster-warmup","10"));
        if(count<1||count>500||warmups<1||measurements<1||warmupSeconds<1||measureSeconds<1||
           !Double.isFinite(warmupSeconds)||!Double.isFinite(measureSeconds)||
           !Double.isFinite(rasterWarmupSeconds)||rasterWarmupSeconds<0)throw new IllegalArgumentException("Protocol");
        output=Path.of(arg("output","target/display-comparison"));
        Files.createDirectories(output);
        if(Files.exists(output.resolve("raster.csv")))throw new IllegalArgumentException("Choose a fresh output");
        threads.setThreadCpuTimeEnabled(true);threads.setThreadAllocatedMemoryEnabled(true);
        stage.setTitle("SVG static display - "+provider);stage.setResizable(false);
        if(Boolean.parseBoolean(arg("jfr","false"))){
            recording=new Recording(Configuration.getConfiguration("profile"));
            recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle","300/s");
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2));
            recording.enable("jdk.NativeMethodSample").withPeriod(java.time.Duration.ofMillis(2));
            recording.start();
        }
        stage.focusedProperty().addListener((o,a,b) -> {if(measuringIdle&&!b)focusLosses++;});
        Files.writeString(output.resolve("environment.txt"),String.join("\n",
            "provider="+provider,"native.api.source="+SvgView.class.getProtectionDomain().getCodeSource().getLocation(),"java="+System.getProperty("java.version"),
            "javafx="+System.getProperty("javafx.runtime.version"),"jvm="+ManagementFactory.getRuntimeMXBean().getInputArguments(),
            "icons="+count+"; raster minimum warmup batches="+warmups+"; minimum warmup seconds="+rasterWarmupSeconds+"; measured batches="+measurements,
            "idle warmup="+warmupSeconds+"; idle measure="+measureSeconds,
            "Independent documents, source-identical XML; autoplay=false; application Node.cache=off; native internal cache=default.",
            "Raster: new Scene + parse/build/CSS/layout + asynchronous 740x650 logical-pixel snapshot/readback, no visible Stage.",
            "Raster is NOT screen presentation or pure GPU time. Validation and PNG writing are outside the timed interval.",
            "Idle: same last scene shown, screen output scale="+javafx.stage.Screen.getPrimary().getOutputScaleX(),
            "JFR="+(recording!=null)+"; no AnimationTimer or FX polling during idle; worker samples counters at the end.",
            "Focus transitions are counted; counters cannot detect occlusion. No forced GC; allocated bytes are not retained memory."));
        worker.schedule(() -> {if(!completed){System.err.println("Display comparison timeout");System.exit(2);}},180,TimeUnit.SECONDS);
        nextBatch();
    }
    private Node create(String xml) throws Exception {
        Node node;
        switch(provider) {
            case "native", "native-before" -> {
                var view=SvgView.parse(xml);view.setAnimationAutoPlay(false);
                if(!view.getDiagnostics().isEmpty())throw new IllegalStateException(view.getDiagnostics().toString());
                view.resize(24,24);node=view;
            }
            case "fxsvgimage" -> {
                var p=LoaderParameters.createWidthParameters(24);p.autoStartAnimations=false;
                node=org.girod.javafx.svgimage.SVGLoader.load(xml,p);
            }
            default -> {
                var doc=new com.github.weisj.jsvg.parser.SVGLoader().load(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),null,LoaderContext.createDefault());
                if(doc==null)throw new IllegalStateException("Null document");
                var canvas=new FXSVGCanvas();canvas.setAnimated(false);canvas.setDocument(doc);
                canvas.setRenderBackend(FXSVGCanvas.RenderBackend.JavaFX);canvas.setStyle("-fx-padding:0");
                canvas.resize(24,24);node=canvas;
            }
        }
        if(node==null)throw new IllegalStateException("Null node");
        node.setManaged(false);return node;
    }
    private void release() {
        for(Node node:nodes) {
            if(node instanceof SvgView view)view.setSource(null);
            else if(node instanceof SVGImage image)image.stopAnimations();
            else if(node instanceof FXSVGCanvas canvas){canvas.stopAnimation();canvas.setDocument(null);canvas.setSkin(null);}
        }
        nodes.clear();
    }
    private void nextBatch() {
        try {
            if(kindIndex>=kinds.size()){
                completed=true;Files.writeString(output.resolve("completed.txt"),"All batches, pixel checks and idle periods completed.\n");
                Platform.exit();return;
            }
            release();
            String kind=kinds.get(kindIndex),xml=xml(kind);
            Files.writeString(output.resolve(kind+".svg"),xml);
            if(rasterWarmupStarted==0)rasterWarmupStarted=System.nanoTime();
            warmupBatch=batch<warmups || (System.nanoTime()-rasterWarmupStarted)/1e9<rasterWarmupSeconds;
            if(recording!=null){
                rasterPhase=new RasterPhase();rasterPhase.provider=provider;rasterPhase.kind=kind;
                rasterPhase.batch=batch;rasterPhase.icons=count;rasterPhase.warmup=warmupBatch;rasterPhase.begin();
            }
            start=counters();
            root=new Pane();root.resize(740,650);
            scene=new Scene(root,740,650,Color.TRANSPARENT);
            for(int i=0;i<count;i++){
                Node node=create(xml);nodes.add(node);
                var host=new Pane(node);host.setManaged(false);host.resize(24,24);
                host.relocate(20+(i%25)*28,72+(i/25)*28);root.getChildren().add(host);
            }
            Counters built=recording==null?null:counters();
            root.applyCss();root.layout();
            Counters laidOut=recording==null?null:counters();
            var parameters=new SnapshotParameters();parameters.setFill(Color.TRANSPARENT);
            parameters.setViewport(new Rectangle2D(0,0,740,650));
            root.snapshot(result -> {
                try {
                    Counters end=counters();
                    if(rasterPhase!=null){rasterPhase.end();rasterPhase.complete=true;rasterPhase.commit();rasterPhase=null;}
                    validate(result.getImage());
                    if(recording!=null){
                        steps.add(String.join(",",provider,kind,""+batch,""+warmupBatch,
                            n((built.wall-start.wall)/1e6),n((laidOut.wall-built.wall)/1e6),n((end.wall-laidOut.wall)/1e6),
                            n((built.fxCpu-start.fxCpu)/1e6),n((laidOut.fxCpu-built.fxCpu)/1e6),n((end.fxCpu-laidOut.fxCpu)/1e6),
                            n((built.fxAllocated-start.fxAllocated)/1048576.0),n((laidOut.fxAllocated-built.fxAllocated)/1048576.0),n((end.fxAllocated-laidOut.fxAllocated)/1048576.0)));
                        Files.write(output.resolve("steps.csv"),steps);
                    }
                    ready.add(String.join(",",provider,kind,""+batch,""+warmupBatch,""+count,
                        n((end.wall-start.wall)/1e6),n((end.fxCpu-start.fxCpu)/1e6),n((end.cpu-start.cpu)/1e6),
                        n((end.fxAllocated-start.fxAllocated)/1048576.0),n((end.allocated-start.allocated)/1048576.0),
                        ""+(end.gc-start.gc),""+(end.gcMs-start.gcMs)));
                    Files.write(output.resolve("raster.csv"),ready);
                    batch++;
                    if(!warmupBatch)measuredBatches++;
                    if(measuredBatches<measurements)Platform.runLater(this::nextBatch);
                    else {save(result.getImage(),kind+".png");beginIdle();}
                }catch(Exception ex){fail(ex);}
                return null;
            },parameters,new WritableImage(740,650));
        }catch(Exception ex){fail(ex);}
    }
    private void validate(Image image) {
        for(int i=0;i<count;i++){
            var c=image.getPixelReader().getColor(26+(i%25)*28,78+(i/25)*28);
            if(c.getOpacity()<.8 || (c.getRed()<.1&&c.getBlue()<.1))throw new IllegalStateException("Missing raster icon "+i);
        }
    }
    private void beginIdle() {
        stage.setScene(scene);stage.show();stage.toFront();stage.requestFocus();
        var focus=new PauseTransition(Duration.seconds(warmupSeconds/2));
        focus.setOnFinished(e -> {stage.toFront();stage.requestFocus();});focus.play();
        var warm=new PauseTransition(Duration.seconds(warmupSeconds));
        warm.setOnFinished(e -> {
            measuringIdle=true;focusLosses=0;
            boolean focused=stage.isFocused(),iconified=stage.isIconified();
            Counters before=counters();
            worker.schedule(() -> {
                Counters after=counters();
                Platform.runLater(() -> {
                    try {
                        measuringIdle=false;
                        double seconds=(after.wall-before.wall)/1e9;
                        idle.add(String.join(",",provider,kinds.get(kindIndex),""+count,n(seconds),
                            n((after.fxCpu-before.fxCpu)/1e6/seconds),n((after.cpu-before.cpu)/1e7/seconds),
                            n((after.fxAllocated-before.fxAllocated)/1024.0/seconds),n((after.allocated-before.allocated)/1024.0/seconds),
                            ""+(after.gc-before.gc),""+(after.gcMs-before.gcMs),""+focused,""+stage.isFocused(),""+focusLosses,
                            ""+iconified,""+stage.isIconified()));
                        Files.write(output.resolve("idle.csv"),idle);
                        stage.hide();stage.setScene(null);release();batch=0;measuredBatches=0;rasterWarmupStarted=0;kindIndex++;
                        Platform.runLater(this::nextBatch);
                    }catch(Exception ex){fail(ex);}
                });
            },(long)(measureSeconds*1e9),TimeUnit.NANOSECONDS);
        });warm.play();
    }
    private void fail(Exception failure) {
        failure.printStackTrace();
        try{Files.writeString(output.resolve("failure.txt"),failure.toString());}catch(Exception ignored){}
        Platform.exit();
    }
    private void save(Image image,String name)throws Exception {
        var out=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<out.getHeight();y++)for(int x=0;x<out.getWidth();x++)out.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(out,"png",output.resolve(name).toFile());
    }
    private static String xml(String kind) {
        String paths="<path d=\"M8 8h18v18H8z\"/><path d=\"M38 8h18v18H38z\"/><path d=\"M8 38h18v18H8z\"/><path d=\"M38 38h10v18H38z\"/>";
        String body=kind.equals("gradient")?"<defs><linearGradient id=\"g\"><stop stop-color=\"red\"/><stop offset=\"1\" stop-color=\"blue\"/></linearGradient></defs><g fill=\"url(#g)\">"+paths+"</g>":"<g fill=\"#2563eb\">"+paths+"</g>";
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\" viewBox=\"0 0 64 64\">"+body+"</svg>";
    }
    @Override public void stop() throws Exception {
        measuringIdle=false;worker.shutdownNow();release();stage.setScene(null);
        if(recording!=null){recording.stop();recording.dump(output.resolve("display.jfr"));recording.close();}
    }
}
