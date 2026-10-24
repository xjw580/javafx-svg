package svgbench;

import club.xiaojiawei.svg.SvgView;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.*;
import javafx.scene.image.*;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.Screen;
import javafx.stage.Stage;
import jdk.jfr.*;
import org.girod.javafx.svgimage.LoaderParameters;
import org.girod.javafx.svgimage.SVGImage;
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas;
import com.github.weisj.jsvg.parser.LoaderContext;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Optional manual comparison only. Dependencies never enter the library build or JAR. */
public final class SvgLibraryComparison extends Application {
    @Name("club.xiaojiawei.svg.AnimationBenchmarkPhase")
    public static final class Phase extends Event {
        public String scenario;
        public int icons;
        public boolean complete;
    }
    record Case(String kind, int count) {}
    private Stage stage;
    private Path output;
    private String provider;
    private boolean probes, completed;
    private final Pane grid = new Pane();
    private final Text caption = new Text(20, 30, "");
    private final List<Node> nodes = new ArrayList<>();
    private final List<Rotate> rotations = new ArrayList<>();
    private final List<Case> cases = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private final List<String> loads = new ArrayList<>(List.of("provider,scenario,icons,create_ms,create_alloc_mib,node_count"));
    private final List<String> checks = new ArrayList<>(List.of("provider,feature,result,detail"));
    private final double[] intervals = new double[100_000];
    private int index = -1, samples, unfocused, iconified;
    private long started, previous;
    private double warmup = 2, measure = 5;
    private AnimationTimer observer;
    private Recording recording;
    private Phase phase;
    private Counters before;
    private WritableImage first;
    private long probeStarted;
    private double nextProbeSample;
    private WritableImage bestProbeFrame;
    private int probeChanges;
    private static final List<String> FEATURES = List.of("path", "gradient", "clip", "mask-alpha", "blur", "tspan",
        "marker", "non-scaling-stroke", "css-style", "smil-transform", "smil-geometry", "smil-ms", "smil-decimal", "css-animation", "pattern", "drop-shadow", "text-path", "animate-motion");
    private final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    record Counters(long wall, long cpu, long fxCpu, long allocated, long fxAllocated, long gcCount, long gcMillis) {}
    private Counters counters() {
        var gc = ManagementFactory.getGarbageCollectorMXBeans();
        return new Counters(System.nanoTime(), os.getProcessCpuTime(), threads.getCurrentThreadCpuTime(),
            threads.getTotalThreadAllocatedBytes(), threads.getCurrentThreadAllocatedBytes(),
            gc.stream().mapToLong(g -> Math.max(0, g.getCollectionCount())).sum(),
            gc.stream().mapToLong(g -> Math.max(0, g.getCollectionTime())).sum());
    }
    private String argument(String name, String fallback) {
        return getParameters().getRaw().stream().filter(s -> s.startsWith("--" + name + "="))
            .map(s -> s.substring(s.indexOf('=') + 1)).findFirst().orElse(fallback);
    }
    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        provider = argument("provider", "native");
        if (!List.of("native", "fxsvgimage", "jsvg-fx", "jsvg-awt").contains(provider)) throw new IllegalArgumentException("Unknown provider");
        probes = getParameters().getRaw().contains("--probes");
        output = Path.of(argument("output", "target/library-comparison/" + provider));
        Files.createDirectories(output);
        if (Files.exists(output.resolve("results.csv")) || Files.exists(output.resolve("features.csv")))
            throw new IllegalArgumentException("Choose a fresh output directory");
        warmup = Double.parseDouble(argument("warmup-seconds", "2"));
        measure = Double.parseDouble(argument("measure-seconds", "5"));
        if (!Double.isFinite(warmup) || warmup < .5 || warmup > 30 || !Double.isFinite(measure) || measure < 1 || measure > 60)
            throw new IllegalArgumentException("Invalid duration");
        if (!probes) {
            for (String countText : argument("counts", "100,500").split(",")) {
                int count = Integer.parseInt(countText);
                if (count < 1 || count > 500) throw new IllegalArgumentException("Count must be 1..500");
                for (String kind : argument("kinds", "path,gradient,smil-transform").split(",")) {
                    if (!List.of("path", "gradient", "smil-transform").contains(kind)) throw new IllegalArgumentException("Unknown kind");
                    cases.add(new Case(kind, count));
                }
            }
        }
        threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        grid.setManaged(false); grid.resizeRelocate(20, 72, 700, 560);
        var root = new Pane(grid, caption);
        root.setStyle("-fx-background-color:#edf2f8");
        stage.setScene(new Scene(root, 740, 650)); stage.setResizable(false);
        stage.setTitle("SVG library comparison - " + provider); stage.show();
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "300/s");
        recording.start();
        rows.add("provider,scenario,icons,samples,seconds,pulse_p50_ms,pulse_p95_ms,pulse_p99_ms,pulse_max_ms,over_33ms,process_cpu_core_pct,fx_cpu_ms_per_pulse,total_alloc_mib_s,fx_alloc_mib_s,fx_alloc_bytes_per_pulse,gc_count,gc_time_ms,unfocused_samples,iconified_samples");
        Files.writeString(output.resolve("environment.txt"), String.join("\n",
            "provider=" + provider + "; probes=" + probes, "java=" + System.getProperty("java.version"),
            "javafx=" + System.getProperty("javafx.runtime.version"), "os=" + System.getProperty("os.name"),
            "jvmArgs=" + ManagementFactory.getRuntimeMXBean().getInputArguments(),
            "screens=" + Screen.getScreens().stream().map(s -> s.getBounds() + ";scale=" + s.getOutputScaleX()).toList(),
            "warmup=" + warmup + "; measure=" + measure + "; scene=740x650; columns=25; tile=28; icon=24; application Node.cache=off; native internal render cache=" + System.getProperty("svg.nativeRenderCache", "default") + "",
            "Every view freshly parses the SAME in-memory XML; document/resource caches are not reused in any provider.",
            "Creation records parse+node construction only, excluding applyCss/layout/first paint. It is NOT cold JVM startup.",
            "Path/gradient use identical application Rotate transforms on the 24px host. Canvas backends can retain a raster surface.",
            "SMIL uses each library's own autoplay. A before/after frame check is required; failing scenarios are skipped.",
            "Pulse intervals are not GPU timings or presented FPS. CPU% is equivalent utilization of one core.",
            "Allocation MXBean totals are approximate; GC milliseconds are collection time, not pauses.",
            "All icons fit on screen. Focus/iconified counters cannot detect occlusion. JFR is enabled in every run."));
        observer = new AnimationTimer() {
            @Override public void handle(long now) {
                try { if (probes) probeTick(now); else tick(now); }
                catch (Exception e) {
                    stop();
                    try { Files.writeString(output.resolve("failure.txt"), e.toString()); } catch (Exception ignored) {}
                    e.printStackTrace(); Platform.exit();
                }
            }
        };
        if (probes) nextProbe(); else nextCase();
        observer.start();
    }

    private Node create(String svg, double size) throws Exception {
        Node node;
        switch (provider) {
            case "native" -> {
                var view = SvgView.parse(svg);
                String cache = System.getProperty("svg.nativeRenderCache", "default");
                if (!cache.equals("default")) {
                    try { SvgView.class.getMethod("setRenderCacheEnabled", boolean.class).invoke(view, cache.equals("on")); }
                    catch (NoSuchMethodException oldVersion) {
                        if (cache.equals("on")) throw new IllegalStateException("Native jar has no internal render cache control");
                    }
                }
                if (!view.getDiagnostics().isEmpty()) throw new IllegalStateException(view.getDiagnostics().toString());
                view.resize(size, size); node = view;
            }
            case "fxsvgimage" -> {
                var params = LoaderParameters.createWidthParameters(size);
                params.autoStartAnimations = true;
                node = org.girod.javafx.svgimage.SVGLoader.load(svg, params);
            }
            default -> {
                var document = new com.github.weisj.jsvg.parser.SVGLoader().load(
                    new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), null, LoaderContext.createDefault());
                if (document == null) throw new IllegalStateException("JSVG parse returned null");
                var canvas = new FXSVGCanvas();
                canvas.setRenderBackend(provider.equals("jsvg-fx") ? FXSVGCanvas.RenderBackend.JavaFX : FXSVGCanvas.RenderBackend.AWT);
                canvas.setDocument(document); canvas.setStyle("-fx-padding:0"); canvas.resize(size, size);
                node = canvas;
            }
        }
        if (node == null) throw new IllegalStateException("Null render node");
        node.setManaged(false);
        return node;
    }
    private void release() {
        for (Node node : nodes) {
            if (node instanceof SvgView view) view.setSource(null);
            else if (node instanceof SVGImage image) image.stopAnimations();
            else if (node instanceof FXSVGCanvas canvas) {
                canvas.stopAnimation(); canvas.setDocument(null);
                var skin = canvas.getSkin();
                canvas.setSkin(null);
                if (skin != null) skin.dispose();
            }
        }
        nodes.clear(); rotations.clear(); grid.getChildren().clear();
    }
    private Pane host(Node node, double size) {
        var host = new Pane(node);
        host.setManaged(false); host.resize(size, size);
        return host;
    }
    private void nextCase() throws Exception {
        release(); index++;
        if (index >= cases.size()) { completed = true; observer.stop(); Platform.exit(); return; }
        var c = cases.get(index);
        String svg = svg(c.kind);
        Files.writeString(output.resolve(c.kind + ".svg"), svg);
        // Warm loader classes and ordinary parsing paths before the bulk creation counter.
        Node warm = create(svg, 24); nodes.add(warm); release();
        var before = counters();
        for (int n = 0; n < c.count; n++) {
            Node node = create(svg, 24); nodes.add(node);
            var host = host(node, 24);
            host.relocate((n % 25) * 28, (n / 25) * 28);
            var rotation = new Rotate(0, 12, 12);
            host.getTransforms().add(rotation); rotations.add(rotation); grid.getChildren().add(host);
        }
        var after = counters();
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        long nodeCount = nodes.stream().mapToLong(SvgLibraryComparison::nodeCount).sum();
        loads.add(String.join(",", provider, c.kind, "" + c.count, number((after.wall - before.wall) / 1e6),
            number((after.fxAllocated - before.fxAllocated) / 1048576.0), "" + nodeCount));
        Files.write(output.resolve("creation.csv"), loads);
        caption.setText(provider + " / " + c.kind + " / " + c.count);
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        stage.toFront(); stage.requestFocus();
        started = 0; previous = 0; phase = null; samples = 0; unfocused = 0; iconified = 0; first = null;
    }
    private static long nodeCount(Node node) {
        return 1 + (node instanceof Parent parent ? parent.getChildrenUnmodifiable().stream().mapToLong(SvgLibraryComparison::nodeCount).sum() : 0);
    }
    private void tick(long now) throws Exception {
        var c = cases.get(index);
        if (started == 0) started = now;
        double elapsed = (now - started) / 1e9;
        boolean smil = c.kind.equals("smil-transform");
        if (!smil) for (Rotate rotation : rotations) rotation.setAngle(elapsed * 240);
        if (phase == null) {
            if (elapsed < warmup * .5) return;
            if (first == null) { first = snapshot((Pane) grid.getChildren().getFirst(), 24); return; }
            if (elapsed < warmup) return;
            var next = snapshot((Pane) grid.getChildren().getFirst(), 24);
            boolean changed = differences(first, next) > 5;
            save(first, c.kind + "-" + c.count + "-a.png"); save(next, c.kind + "-" + c.count + "-b.png");
            checks.add(provider + "," + c.kind + "-" + c.count + "," + (changed ? "PASS" : "FAIL") + ",frame-change");
            Files.write(output.resolve("features.csv"), checks);
            if (!changed) { System.out.println("SKIP unchanged workload " + c); nextCase(); return; }
            phase = new Phase(); phase.scenario = provider + "-" + c.kind; phase.icons = c.count; phase.begin();
            before = counters(); previous = 0; return;
        }
        if (previous == 0) { previous = now; return; }
        if (samples >= intervals.length) throw new IllegalStateException("Too many pulses");
        intervals[samples++] = (now - previous) / 1e6; previous = now;
        if (!stage.isFocused()) unfocused++;
        if (stage.isIconified()) iconified++;
        if ((System.nanoTime() - before.wall) / 1e9 < measure) return;
        var after = counters();
        phase.end(); phase.complete = true; phase.commit();
        double seconds = (after.wall - before.wall) / 1e9;
        var sorted = Arrays.copyOf(intervals, samples); Arrays.sort(sorted);
        String row = String.join(",", provider, c.kind, "" + c.count, "" + samples, number(seconds),
            number(percentile(sorted, .5)), number(percentile(sorted, .95)), number(percentile(sorted, .99)), number(sorted[sorted.length - 1]),
            "" + Arrays.stream(sorted).filter(v -> v > 1000.0 / 30).count(),
            number((after.cpu - before.cpu) / seconds / 1e7), number((after.fxCpu - before.fxCpu) / samples / 1e6),
            number((after.allocated - before.allocated) / seconds / 1048576),
            number((after.fxAllocated - before.fxAllocated) / seconds / 1048576),
            number((double) (after.fxAllocated - before.fxAllocated) / samples),
            "" + (after.gcCount - before.gcCount), "" + (after.gcMillis - before.gcMillis), "" + unfocused, "" + iconified);
        rows.add(row); Files.write(output.resolve("results.csv"), rows); System.out.println(row);
        var pulses = new ArrayList<String>(); pulses.add("interval_ms");
        for (int n = 0; n < samples; n++) pulses.add(number(intervals[n]));
        Files.write(output.resolve(c.kind + "-" + c.count + "-pulses.csv"), pulses);
        nextCase();
    }
    private void nextProbe() throws Exception {
        release(); index++;
        if (index >= FEATURES.size()) { completed = true; observer.stop(); Platform.exit(); return; }
        String feature = FEATURES.get(index);
        caption.setText(provider + " / feature / " + feature);
        String svg = svg(feature); Files.writeString(output.resolve(feature + ".svg"), svg);
        try {
            var node = create(svg, 64); nodes.add(node); grid.getChildren().add(host(node, 64));
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            probeStarted = 0; first = null; bestProbeFrame = null; probeChanges = 0; nextProbeSample = .2;
        } catch (Exception error) {
            Files.writeString(output.resolve(feature + "-error.txt"), error.toString());
            checks.add(provider + "," + feature + ",ERROR," + error.getClass().getSimpleName());
            Files.write(output.resolve("features.csv"), checks);
            nextProbe();
        }
    }
    private void probeTick(long now) throws Exception {
        if (probeStarted == 0) probeStarted = now;
        double seconds = (now - probeStarted) / 1e9;
        if (seconds < nextProbeSample) return;
        nextProbeSample = seconds + .15;
        var image = snapshot((Pane) grid.getChildren().getFirst(), 64);
        if (first == null) { first = image; return; }
        String feature = FEATURES.get(index);
        int changes = differences(first, image);
        boolean animated = feature.startsWith("smil-") || feature.equals("css-animation") || feature.equals("animate-motion");
        // Sample several phases so equal-looking endpoints do not misclassify a playing animation.
        if (!animated || bestProbeFrame == null || changes > probeChanges) {
            bestProbeFrame = image;
            probeChanges = changes;
        }
        if (seconds < .8) return;
        image = bestProbeFrame;
        save(first, feature + "-a.png"); save(image, feature + "-b.png");
        boolean pass = probe(feature, first, image);
        checks.add(provider + "," + feature + "," + (pass ? "PASS" : "FAIL") + ",pixel-probe");
        Files.write(output.resolve("features.csv"), checks);
        System.out.println(provider + " " + feature + ": " + pass);
        nextProbe();
    }
    private static boolean probe(String feature, WritableImage a, WritableImage b) {
        var p = b.getPixelReader();
        return switch (feature) {
            case "clip", "mask-alpha" -> p.getColor(16, 32).getOpacity() > .8 && p.getColor(48, 32).getOpacity() < .1;
            case "blur" -> p.getColor(18, 32).getOpacity() > .01 && p.getColor(18, 32).getOpacity() < .45 && p.getColor(32, 32).getOpacity() > .8;
            case "gradient" -> p.getColor(12, 12).getRed() > p.getColor(12, 12).getBlue() && p.getColor(52, 12).getBlue() > p.getColor(52, 12).getRed();
            case "marker" -> p.getColor(49, 32).getOpacity() > .5;
            case "non-scaling-stroke" -> p.getColor(14, 32).getOpacity() < .2 && p.getColor(15, 32).getOpacity() > .5;
            case "smil-transform", "smil-geometry", "smil-ms", "smil-decimal", "css-animation", "animate-motion" -> differences(a, b) > 40;
            case "css-style", "path" -> p.getColor(16, 16).getBlue() > .7 && p.getColor(16, 16).getRed() < .3;
            case "tspan" -> colourCount(b, true) > 10 && colourCount(b, false) > 10;
            case "pattern" -> p.getColor(4, 32).getBlue() > .7 && p.getColor(12, 32).getOpacity() < .1;
            case "drop-shadow" -> p.getColor(20, 32).getRed() > .8 && p.getColor(40, 32).getBlue() > .6 && p.getColor(40, 32).getOpacity() > .5;
            case "text-path" -> colourCount(b, true) > 20 && p.getColor(32, 60).getOpacity() < .1;
            default -> false;
        };
    }
    private static int colourCount(WritableImage image, boolean blue) {
        int result = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            Color c = image.getPixelReader().getColor(x, y);
            if (c.getOpacity() > .5 && (blue ? c.getBlue() > .7 && c.getRed() < .3 : c.getRed() > .7 && c.getBlue() < .3)) result++;
        }
        return result;
    }
    private static WritableImage snapshot(Pane node, int size) {
        var params = new SnapshotParameters(); params.setFill(Color.TRANSPARENT);
        params.setViewport(new Rectangle2D(0, 0, size, size));
        return node.snapshot(params, new WritableImage(size, size));
    }
    private static int differences(WritableImage a, WritableImage b) {
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int one = a.getPixelReader().getArgb(x, y), two = b.getPixelReader().getArgb(x, y);
            if (Math.abs((one >>> 24) - (two >>> 24)) > 12 || Math.abs((one & 255) - (two & 255)) > 12 ||
                Math.abs(((one >>> 16) & 255) - ((two >>> 16) & 255)) > 12) changed++;
        }
        return changed;
    }
    private void save(WritableImage image, String name) throws Exception {
        var result = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < result.getHeight(); y++) for (int x = 0; x < result.getWidth(); x++) result.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        if (!ImageIO.write(result, "png", output.resolve(name).toFile())) throw new IllegalStateException("PNG export failed");
    }
    private static double percentile(double[] sorted, double p) { return sorted[Math.max(0, (int) Math.ceil(sorted.length * p) - 1)]; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    @Override public void stop() throws Exception {
        if (observer != null) observer.stop();
        release();
        if (recording != null) { recording.stop(); recording.dump(output.resolve("comparison.jfr")); recording.close(); }
        if (output != null) Files.writeString(output.resolve(completed ? "completed.txt" : "aborted.txt"), completed ? "All cases processed; check skipped workloads and probe failures.\n" : "Incomplete run.\n");
    }
    private static String svg(String kind) {
        String paths = "<path d=\"M8 8h18v18H8z\"/><path d=\"M38 8h18v18H38z\"/><path d=\"M8 38h18v18H8z\"/><path d=\"M38 38h10v18H38z\"/>";
        String body = switch (kind) {
            case "path" -> "<g fill=\"#2563eb\">" + paths + "</g>";
            case "gradient" -> "<defs><linearGradient id=\"g\"><stop stop-color=\"red\"/><stop offset=\"1\" stop-color=\"blue\"/></linearGradient></defs><g fill=\"url(#g)\">" + paths + "</g>";
            case "clip" -> "<defs><clipPath id=\"c\"><rect width=\"32\" height=\"64\"/></clipPath></defs><rect width=\"64\" height=\"64\" fill=\"red\" clip-path=\"url(#c)\"/>";
            case "mask-alpha" -> "<defs><mask id=\"m\" mask-type=\"alpha\"><rect width=\"32\" height=\"64\" fill=\"white\"/></mask></defs><rect width=\"64\" height=\"64\" fill=\"red\" mask=\"url(#m)\"/>";
            case "blur" -> "<defs><filter id=\"f\" color-interpolation-filters=\"sRGB\"><feGaussianBlur stdDeviation=\"2\"/></filter></defs><rect x=\"20\" y=\"20\" width=\"24\" height=\"24\" fill=\"red\" filter=\"url(#f)\"/>";
            case "tspan" -> "<text x=\"3\" y=\"40\" font-size=\"28\" fill=\"blue\">A<tspan fill=\"red\">B</tspan></text>";
            case "marker" -> "<defs><marker id=\"m\" markerUnits=\"userSpaceOnUse\" markerWidth=\"10\" markerHeight=\"10\" refY=\"5\" orient=\"auto\"><path d=\"M0 0L10 5L0 10z\" fill=\"blue\"/></marker></defs><path d=\"M12 32H44\" stroke=\"blue\" stroke-width=\"2\" marker-end=\"url(#m)\"/>";
            case "non-scaling-stroke" -> "<g transform=\"scale(2)\"><rect x=\"8\" y=\"8\" width=\"16\" height=\"16\" fill=\"none\" stroke=\"blue\" stroke-width=\"2\" vector-effect=\"non-scaling-stroke\"/></g>";
            case "css-style" -> "<style>.a {fill:#2563eb}</style><rect class=\"a\" x=\"8\" y=\"8\" width=\"48\" height=\"48\"/>";
            case "smil-transform" -> "<g fill=\"#2563eb\">" + paths + "<animateTransform attributeName=\"transform\" type=\"rotate\" from=\"0 32 32\" to=\"360 32 32\" dur=\"2s\" repeatCount=\"indefinite\"/></g>";
            case "smil-geometry" -> "<circle cx=\"32\" cy=\"32\" r=\"4\" fill=\"blue\"><animate attributeName=\"r\" from=\"4\" to=\"24\" dur=\"2s\" repeatCount=\"indefinite\"/></circle>";
            case "smil-ms" -> "<circle cx=\"32\" cy=\"32\" r=\"4\" fill=\"blue\"><animate attributeName=\"r\" from=\"4\" to=\"24\" dur=\"1500ms\" repeatCount=\"indefinite\"/></circle>";
            case "smil-decimal" -> "<circle cx=\"32\" cy=\"32\" r=\"4\" fill=\"blue\"><animate attributeName=\"r\" from=\"4\" to=\"24\" dur=\"1.5s\" repeatCount=\"indefinite\"/></circle>";
            case "pattern" -> "<defs><pattern id=\"p\" patternUnits=\"userSpaceOnUse\" width=\"16\" height=\"16\"><rect width=\"8\" height=\"16\" fill=\"blue\"/></pattern></defs><rect width=\"64\" height=\"64\" fill=\"url(#p)\"/>";
            case "drop-shadow" -> "<defs><filter id=\"f\" x=\"-50%\" y=\"-50%\" width=\"200%\" height=\"200%\" color-interpolation-filters=\"sRGB\"><feDropShadow dx=\"10\" dy=\"0\" stdDeviation=\"1\" flood-color=\"blue\"/></filter></defs><rect x=\"12\" y=\"20\" width=\"20\" height=\"24\" fill=\"red\" filter=\"url(#f)\"/>";
            case "text-path" -> "<defs><path id=\"p\" d=\"M4 40H60\"/></defs><text font-size=\"24\" fill=\"blue\"><textPath href=\"#p\">SVG</textPath></text>";
            case "animate-motion" -> "<circle r=\"5\" fill=\"blue\"><animateMotion path=\"M12 32H52\" dur=\"2s\" repeatCount=\"indefinite\"/></circle>";
            case "css-animation" -> "<style>@keyframes k {from {fill:blue} to {fill:red}} rect {animation:k 1500ms linear infinite alternate}</style><rect x=\"8\" y=\"8\" width=\"48\" height=\"48\"/>";
            default -> throw new IllegalArgumentException(kind);
        };
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"64\" viewBox=\"0 0 64 64\">" + body + "</svg>";
    }
}
