# Stage 6 convergence protocol

Runtime evidence is generated separately; source files alone are not validation evidence.

## Corpus and deterministic checks

`SvgRoadmapConvergenceTest` uses four unchanged real Lucide SVGs (activity, airplay, atom, cpu). `src/test/resources/svg/real-corpus/sources.json` records Git blob identities and source URLs; the full upstream ISC/MIT license is retained. These public icons are not claimed to cover all complex SVG features. Earlier original user fixtures and new stage-specific authored numeric/render tests remain part of the full suite.

The test checks Git blob hashes, nonblank output, cache-on/off and repeated-scale premultiplied pixel differences, actual contact-sheet capture, 240 document edit/reparent/resize cycles, retirement of cache flags, bounded scene nodes, parser limits, resource-cycle and filter-grid rejection, and recovery to valid loads. Heap observations are recorded without asserting leak freedom.

`compare_corpus.py` exports the unchanged files with installed Inkscape at 96×96 and compares actual JavaFX captures to that independent implementation. It records full-image premultiplied RGBA MAE, alpha MAE, maximum difference, affected-pixel count, coverage IoU, and selected fully opaque interiors. A review flag identifies gross discrepancy, not a normative conformance threshold. Antialias differences are expected; this is not browser/GPU parity.

## Sustained editing

`SvgRoadmapSustainedEdit` runs for 300 seconds by default in a separate JVM with a 256 MiB heap cap. It cycles real icons and authored convolution/transfer/radial/text/filter-animation/tile cases, replaces documents, reparents, rescales, seeks animation, snapshots, and checks strict diagnostics/cache flags/node bounds. Batches target 20 edits/second. All heap/GC observations and checksums are retained. A five-minute finite run is not evidence of unlimited stability or absence of every leak.

## Performance calibration

`SvgRoadmapCalibration` takes no arguments. `calibrate.py` launches the identical final JAR, same path, identical JVM settings and classpath under external blind labels in AB/BA order. Four pairs run serially without concurrent builds. Every process measures parse, create+snapshot, repeated snapshot, and resize+snapshot work after fixed warmups, retaining all 30 samples per workload.

The descriptive screen requires every paired workload median to differ by at most 5% relative to its pair mean. The threshold is declared before execution; failures are retained, not retuned away. A failed screen forbids using this environment's results to claim code-caused gains or regressions. A passed screen alone is not an optimization claim. No before/after optimization comparison is bundled here.

Every sample additionally records GC count change and heap occupancy before/after. `workload-noise.csv` retains median, min/max, p90, relative MAD/stdev, count above twice the median, first/last-half medians, and medians grouped by observed GC. These describe tails/drift/GC association, not causes; no observation is removed from the primary screen. JAR and full test-class/resource tree identities are checked between forks.

If the primary screen fails, first inspect raw tails, within-fork drift, GC association, host load and concurrent process evidence. At most one additional diagnostic cohort is permitted by this preparation protocol: repeat the exact four-pair primary command in a new output directory after documented removal of incidental contention, only when such contention is actually observed. Preserve the original failed cohort and label the second diagnostic, not replacement evidence. Do not alter the threshold, select successful pairs, or infer code-caused changes. If no removable contention is evidenced, report the repeatability limit rather than repeatedly seeking a pass. A larger-batch or longer-warmup protocol would require a separately documented hypothesis before measurement and is not silently substituted here.

After the final full build, capture binary/source identities and extract the exact dependency classpath offline from its Surefire XML (no dependency plugin download):

    python benchmarks/roadmap-stage6/capture_identity.py --project . --output /path/to/evidence

Use `/path/to/evidence/test-classpath.txt` in the following commands. Set `XDG_CACHE_HOME` to a writable directory consistently for all runs; the manifests record it. The colon-separated classpath excludes target/classes so application classes must come from the measured JAR:

    python benchmarks/roadmap-stage6/calibrate.py --project . --classpath-file /path/to/classpath.txt --output /path/to/calibration
    python benchmarks/roadmap-stage6/sustained_edit.py --project . --classpath-file /path/to/classpath.txt --output /path/to/sustained --seconds 300
    python benchmarks/roadmap-stage6/compare_corpus.py --project . --output /path/to/comparison

Maven builds, benchmark runs, and sustained-edit observations should be serialized to avoid the contention that previously caused unrelated editor timeouts. All validation is Linux JavaFX software rendering; Windows/GPU remains unverified unless a separately authorized environment becomes available.

## Narrow positioned-text resource correction

`FxSvgRenderer` includes one narrow resource correction: positioned glyphs within one text layout reuse the radial paint instance when their original SVG owner, computed style identity, channel and paint reference agree. The lookup exists only during `renderTextSpans`; the existing unique radial list remains responsible for disposal and render rollback. Different tspan owners, different fill/stroke channels, views and replacement documents remain isolated. No pattern or filter kernel change is included, and all existing pixel budgets remain unchanged.

`SvgTextRadialReuseTest` verifies resource/image identity and budget behavior, separate opacity/animation channels, resize-back pixels, document isolation and failed-element cleanup. This is a bounded allocation correction established by resource properties, not a measured speedup; calibration measures only the final identical binary.


## Executed results and one bounded diagnostic

See [中文结果记录](results-zh.md). Demo-final clean suite: 806 tests, no failures/errors/skips. Four unchanged Lucide corpus files were compared against Inkscape 1.4. A 300-second run completed 6,000 edits with lifecycle assertions intact; this is not leak-freedom proof.

The primary eight-fork calibration failed the unchanged 5% screen in 14/16 paired workload comparisons. A single separately preregistered diagnostic used `SvgRoadmapWarmupDiagnostic` / `diagnose_warmup.py`, fixed warmup 300 batches and 60 measured batches, four AB/BA pairs, otherwise the same workloads/JVM options. It preserved the main JAR and original primary harness classes byte-for-byte. Its screen also failed (12/16). Both raw cohorts are retained; neither supports causal speedup/regression claims. No further cohort was run.

    python benchmarks/roadmap-stage6/diagnose_warmup.py --project . --classpath-file /path/to/test-classpath.txt --output /path/to/diagnostic --pairs 4

This optional command reproduces the registered diagnostic; do not silently substitute it for the primary protocol or repeatedly run until a pass. The result files describe same-version variability, not an optimization comparison.
