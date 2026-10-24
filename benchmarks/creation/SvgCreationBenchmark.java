package svgbench;

import club.xiaojiawei.svg.SvgDocument;
import club.xiaojiawei.svg.SvgLoader;
import club.xiaojiawei.svg.SvgView;
import com.sun.management.ThreadMXBean;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Node;
import org.girod.javafx.svgimage.LoaderParameters;
import org.girod.javafx.svgimage.SVGImage;
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas;
import com.github.weisj.jsvg.parser.LoaderContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javafx.scene.layout.Pane;
import javafx.scene.transform.Rotate;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** One operation is a retained batch, never an individual icon microbenchmark. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 15, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
public class SvgCreationBenchmark {
    @State(Scope.Benchmark)
    public static class Input {
        @Param({"path", "gradient", "smil-transform"}) public String kind;
        @Param({"500"}) public int count;
        @Param({"native"}) public String provider;
        String xml;
        SvgDocument shared;
        volatile Object[] retained;
        Pane grid;
        Scene scene;
        boolean measurement;
        int iteration;
        long invocation;
        final List<String> rows = new ArrayList<>();
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

        @Setup(Level.Trial) public void trial(BenchmarkParams params) throws Exception {
            if (count < 1 || count > 500) throw new IllegalArgumentException("count must be 1..500");
            if (!provider.equals("native") && !params.getBenchmark().endsWith(".totalMounted"))
                throw new IllegalArgumentException("Cross-library creation compares totalMounted only");
            Platform.startup(() -> Platform.setImplicitExit(false));
            threads.setThreadCpuTimeEnabled(true);
            threads.setThreadAllocatedMemoryEnabled(true);
            if (!threads.isThreadAllocatedMemoryEnabled() || !threads.isThreadCpuTimeEnabled())
                throw new IllegalStateException("FX counters unavailable");
            xml = source(kind);
            if (provider.equals("native")) shared = fx(() -> SvgLoader.parse(xml));
            rows.add("provider,phase,kind,count,measurement,iteration,invocation,fx_wall_ns,fx_cpu_ns,fx_alloc_bytes,gc_count,gc_ms");
        }

        @Setup(Level.Iteration) public void iteration(IterationParams params) {
            measurement = params.getType() == org.openjdk.jmh.runner.IterationType.MEASUREMENT;
            iteration++;
        }

        long run(String phase) throws Exception {
            return fx(() -> {
                long id = Thread.currentThread().threadId();
                // Counters are on FX, excluding dispatch/queue wait. JMH's primary score includes dispatch.
                long gc = gcCount(), gcMillis = gcMillis();
                long allocated = threads.getThreadAllocatedBytes(id), cpu = threads.getThreadCpuTime(id);
                long start = System.nanoTime();
                Object[] values = new Object[count];
                if (phase.equals("totalMounted")) {
                    grid = new Pane();
                    scene = new Scene(grid, 740, 650);
                }
                for (int i = 0; i < count; i++) {
                    if (phase.equals("parse")) {
                        values[i] = SvgLoader.parse(xml);
                    } else {
                        Node view = phase.equals("buildShared") ? new SvgView(shared) : createNode(provider, xml, 24);
                        if (phase.equals("buildShared") && view instanceof SvgView nativeView) {
                            nativeView.setAnimationAutoPlay(false);
                            if (!nativeView.getDiagnostics().isEmpty()) throw new IllegalStateException(nativeView.getDiagnostics().toString());
                        }
                        values[i] = view;
                        if (phase.equals("totalMounted")) {
                            Pane host = new Pane(view);
                            host.setManaged(false);
                            host.resize(24, 24);
                            host.relocate((i % 25) * 28, (i / 25) * 28);
                            host.getTransforms().add(new Rotate(0, 12, 12));
                            grid.getChildren().add(host);
                        }
                    }
                }
                retained = values; // Escape the full graph until Invocation teardown, preventing elimination.
                long wall = System.nanoTime() - start;
                long usedCpu = threads.getThreadCpuTime(id) - cpu;
                long usedBytes = threads.getThreadAllocatedBytes(id) - allocated;
                long collections = gcCount() - gc, collectionMillis = gcMillis() - gcMillis;
                rows.add(String.join(",", provider, phase, kind, ""+count, ""+measurement, ""+iteration,
                    ""+(++invocation), ""+wall, ""+usedCpu, ""+usedBytes, ""+collections, ""+collectionMillis));
                return count;
            });
        }

        @TearDown(Level.Invocation) public void release() throws Exception {
            fx(() -> {
                if (retained != null) {
                    if (retained.length != count) throw new IllegalStateException("Incomplete batch");
                    for (Object value : retained) {
                        if (value instanceof SvgView view) view.setDocument(null);
                        else if (value instanceof SVGImage image) image.stopAnimations();
                        else if (value instanceof FXSVGCanvas canvas) {
                            canvas.stopAnimation(); canvas.setDocument(null); canvas.setSkin(null);
                        }
                        else if (!(value instanceof SvgDocument)) throw new IllegalStateException("Missing document");
                    }
                }
                if (grid != null) grid.getChildren().clear();
                grid = null;
                scene = null;
                retained = null;
                return null;
            });
        }

        @TearDown(Level.Trial) public void save(BenchmarkParams params) throws Exception {
            Path dir = Path.of(System.getProperty("svg.create.output"));
            Files.createDirectories(dir);
            String phase = params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.')+1);
            String name = phase + "-" + kind + "-" + ProcessHandle.current().pid();
            Files.write(dir.resolve(name + ".csv"), rows);
            Files.writeString(dir.resolve(name + ".txt"), String.join("\n",
                "label="+System.getProperty("svg.create.label"), "provider="+provider,
                "benchmark="+params.getBenchmark(), "kind="+kind, "count="+count,
                "java="+System.getProperty("java.version"), "javafx="+System.getProperty("javafx.runtime.version"),
                "args="+ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "threads=1; FX dispatch included in JMH score; FX counters exclude dispatch.",
                "No Stage/window, no autoplay, no CSS/layout/first paint. Scene creation is INCLUDED in totalMounted.",
                "buildShared reuses one pre-parsed document after warmup; parse/totalMounted parse fresh XML.",
                "All providers use the same runtime/classpath and disabled autoplay. JSVG skin/Canvas drawing is deferred; creation is not full first-paint cost.",
                "Invocation teardown clears views outside timed benchmark; FX allocation excludes teardown/row bookkeeping.",
                "No forced GC; batch retained until teardown; JFR disabled in all modes/versions.",
                "Warmup AND measurement rows retained; not a cold-start benchmark.")+"\n");
            Files.writeString(dir.resolve(kind + ".svg"), xml);
            Platform.exit();
        }
    }

    @Benchmark public long parse(Input input) throws Exception { return input.run("parse"); }
    @Benchmark public long buildShared(Input input) throws Exception { return input.run("buildShared"); }
    @Benchmark public long totalMounted(Input input) throws Exception { return input.run("totalMounted"); }

    private static Node createNode(String provider, String xml, double size) throws Exception {
        Node node;
        switch (provider) {
            case "native" -> {
                SvgView view = SvgView.parse(xml);
                view.setAnimationAutoPlay(false);
                if (!view.getDiagnostics().isEmpty()) throw new IllegalStateException(view.getDiagnostics().toString());
                node = view;
            }
            case "fxsvgimage" -> {
                LoaderParameters parameters = LoaderParameters.createWidthParameters(size);
                parameters.autoStartAnimations = false;
                node = org.girod.javafx.svgimage.SVGLoader.load(xml, parameters);
            }
            case "jsvg-fx", "jsvg-awt" -> {
                var document = new com.github.weisj.jsvg.parser.SVGLoader().load(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), null, LoaderContext.createDefault());
                if (document == null) throw new IllegalStateException("JSVG returned no document");
                FXSVGCanvas canvas = new FXSVGCanvas();
                canvas.setRenderBackend(provider.equals("jsvg-fx") ? FXSVGCanvas.RenderBackend.JavaFX : FXSVGCanvas.RenderBackend.AWT);
                canvas.setAnimated(false);
                canvas.setDocument(document);
                canvas.setStyle("-fx-padding:0");
                node = canvas;
            }
            default -> throw new IllegalArgumentException("Unknown provider: "+provider);
        }
        if (node == null) throw new IllegalStateException("Missing view");
        node.setManaged(false);
        if (node.isResizable()) node.resize(size, size);
        return node;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(180, TimeUnit.SECONDS);
    }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionCount())).sum();
    }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum();
    }
    private static String source(String kind) {
        String paths = "<path d=\"M8 8h18v18H8z\"/><path d=\"M38 8h18v18H38z\"/><path d=\"M8 38h18v18H8z\"/><path d=\"M38 38h10v18H38z\"/>";
        String body = switch (kind) {
            case "path" -> "<g fill=\"#2563eb\">"+paths+"</g>";
            case "gradient" -> "<defs><linearGradient id=\"g\"><stop stop-color=\"red\"/><stop offset=\"1\" stop-color=\"blue\"/></linearGradient></defs><g fill=\"url(#g)\">"+paths+"</g>";
            case "smil-transform" -> "<g fill=\"#2563eb\">"+paths+"<animateTransform attributeName=\"transform\" type=\"rotate\" from=\"0 32 32\" to=\"360 32 32\" dur=\"2s\" repeatCount=\"indefinite\"/></g>";
            default -> throw new IllegalArgumentException(kind);
        };
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\" viewBox=\"0 0 64 64\">"+body+"</svg>";
    }
}
