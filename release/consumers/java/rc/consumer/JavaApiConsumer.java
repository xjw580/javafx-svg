package rc.consumer;

import club.xiaojiawei.svg.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Compile against the packaged library JAR, never target/classes or test-classes. */
public final class JavaApiConsumer {
    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='16' height='16'><rect id='box' width='16' height='16' fill='red'/></svg>";
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        SvgOptions options = new SvgOptions(true);
        SvgDocument document = SvgLoader.parse(SVG, URI.create("file:/icons/example.svg"), options);
        check(document.getElementsById().get("box").getName().equals("rect"));
        check(document.getBaseUri().equals(URI.create("file:/icons/example.svg")));
        try { document.getElementsById().clear(); throw new AssertionError(); }
        catch (UnsupportedOperationException expected) { }
        final boolean[] closed = {false};
        var input = new ByteArrayInputStream(SVG.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public void close() { closed[0] = true; }
        };
        check(SvgLoader.load(input).getRoot().getName().equals("svg"));
        check(!closed[0]); input.close(); check(closed[0]);
        check(new SvgDiagnostic("diagnostic").getElementId() == null);
        check(new SvgException("failure").getCause() == null);
        SvgResourceResolver deny = request -> null;
        check(deny.open(new SvgResourceRequest(URI.create("https://example.invalid/image.png"), SvgResourceKind.IMAGE)) == null);
        Path file = Files.createTempFile("javafx-svg-consumer-", ".svg");
        try {
            Files.writeString(file, SVG);
            SvgResourceCache cache = new SvgResourceCache();
            SvgDocument first = cache.load(file);
            check(first == cache.load(file));
            cache.invalidate(file); check(first != cache.load(file));
            cache.clear(); check(cache.getSize() == 0);
        } finally { Files.deleteIfExists(file); }
        if (args.length > 0 && args[0].equals("--fx")) render(document);
        System.out.println("JAVA_CONSUMER_OK");
    }
    private static void render(SvgDocument document) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> { try {
            SvgView view = new SvgView(document);
            view.setAnimationAutoPlay(false);
            view.setRenderCacheEnabled(false);
            StackPane root = new StackPane(view);
            new Scene(root, 16, 16);
            root.resize(16, 16); root.applyCss(); root.layout();
            WritableImage image = view.snapshot(null, null);
            Color center = image.getPixelReader().getColor(8, 8);
            check(center.getRed() > .9 && center.getGreen() < .1 && center.getOpacity() > .9);
            view.setDocument(null);
            check(view.getDocument() == null && !view.isLoading());
        } catch (Throwable t) { failure.set(t); } finally { done.countDown(); } });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("FX consumer timed out");
            if (failure.get() != null) throw new AssertionError("FX consumer failed", failure.get());
        } finally { Platform.exit(); }
    }
}
