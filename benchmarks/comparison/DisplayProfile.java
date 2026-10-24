import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Diagnostic only: allocation weights and sparse CPU samples are not exact attribution. */
public final class DisplayProfile {
    private record Span(String kind, Instant start, Instant end) {
        boolean contains(Instant time) { return !time.isBefore(start) && !time.isAfter(end); }
    }

    private static String group(RecordedThread thread) {
        if (thread == null) return "unknown";
        String name = thread.getJavaName();
        if (name.contains("JavaFX Application")) return "FX";
        return name.contains("QuantumRenderer") ? "Renderer" : name;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: DisplayProfile <display.jfr>");
        Path path = Path.of(args[0]);
        List<Span> spans = new ArrayList<>();
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getEventType().getName().equals("club.xiaojiawei.svg.RasterBenchmarkPhase")
                        && event.getBoolean("complete") && !event.getBoolean("warmup")) {
                    spans.add(new Span(event.getString("kind"), event.getStartTime(), event.getEndTime()));
                }
            }
        }
        if (spans.isEmpty()) throw new IllegalArgumentException("No completed measured raster intervals");
        Map<String, Map<String, Long>> allocations = new TreeMap<>(), samples = new TreeMap<>();
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                Span span = spans.stream().filter(s -> s.contains(event.getStartTime())).findFirst().orElse(null);
                if (span == null) continue;
                String type = event.getEventType().getName();
                boolean allocation = type.equals("jdk.ObjectAllocationSample");
                if (!allocation && !type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) continue;
                RecordedThread thread = allocation ? event.getThread() : event.getThread("sampledThread");
                String phase = span.kind + "/" + group(thread);
                var stack = event.getStackTrace();
                if (stack == null) continue;
                String owner = "framework";
                StringJoiner trace = new StringJoiner(" <- ");
                int frameIndex = 0;
                for (var frame : stack.getFrames()) {
                    String name = frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
                    if (name.startsWith("club.xiaojiawei.svg.") && owner.equals("framework")) owner = name;
                    if (frameIndex++ < 8) trace.add(name);
                }
                String key = (allocation ? event.getClass("objectClass").getName() + " | " : "") + owner + " | " + trace;
                (allocation ? allocations : samples).computeIfAbsent(phase, k -> new HashMap<>())
                        .merge(key, allocation ? event.getLong("weight") : 1L, Long::sum);
            }
        }
        System.out.println("Measured raster spans=" + spans.size()
                + "; allocation weights are sampled estimates; samples are not exact CPU time.");
        print("ALLOC", allocations, 12);
        print("CPU", samples, 10);
    }

    private static void print(String label, Map<String, Map<String, Long>> groups, int limit) {
        groups.forEach((phase, entries) -> {
            System.out.println("\n" + label + " " + phase);
            entries.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit).forEach(System.out::println);
        });
    }
}
