# Deobfuscation performance measurements

Measured on 2026-11-13 against transformer sources at `8fed3984f`.

| Change | Resulting behavior |
|---|---|
| Reuse method analysis in constant folding | The second pass reuses frames when the stack pass leaves instructions unchanged. Stack rewrites still trigger fresh analysis. |
| Remove repeated indexing from dead-code cleanup | Pruning walks an instruction snapshot, avoiding an instruction-index rebuild after every deletion. Debug-variable validation uses the existing label index instead of scanning the instruction list for every endpoint. |

The table shows median time per transformer pass, with 21 measured samples per variant: three separate JVM processes, each with five warmup passes and seven measured passes. Baseline/candidate order alternates between process pairs. Both transformer source versions are compiled with the same compiler options and run with the same dependencies.

Environment: Linux x86_64 VM, AMD EPYC-Milan, 8 logical CPUs; OpenJDK 25.0.5.0.3; `-Xms512m -Xmx2g`. Benchmarks run without the test suite running alongside them.

| Pass | Workload | Before (ms) | After (ms) | Time reduction | Allocation reduction |
|---|---|---:|---:|---:|---:|
| Dead-code cleanup | 20,000 dead instructions (synthetic) | 430.345 | 1.582 | 99.6% | 99.98% |
| Dead-code cleanup | 5,000 debug variable entries (synthetic) | 72.685 | 1.874 | 97.4% | unchanged |
| Dead-code cleanup | AltarSMP-v1.jar | 16.302 | 16.394 | -0.6% | unchanged |
| Dead-code cleanup | BreweryX-3.6.3.jar | 69.112 | 48.375 | 30.0% | unchanged |
| Dead-code cleanup | Vulcan-2.9.7.12.jar | 90.679 | 88.341 | 2.6% | 7.87% |
| Constant folding | AltarSMP-v1.jar | 209.964 | 161.433 | 23.1% | 24.01% |
| Constant folding | BreweryX-3.6.3.jar | 468.567 | 338.141 | 27.8% | 37.92% |
| Constant folding | Vulcan-2.9.7.12.jar | 3008.988 | 2941.002 | 2.3% | 5.05% |
| Constant folding | obf-sample-zkm-26.jar | 161.322 | 111.752 | 30.7% | 32.74% |

Small differences around 3% or less should be treated as measurement noise. In particular, this does not establish a dead-code gain on AltarSMP or a substantial gain on Vulcan. Stress-case gains apply to methods with unusually large dead blocks or debug tables.

These are transformer timings, not whole-application or full deobfuscation-pipeline speedups. Dead-code timing covers `prune` over all methods, with input nodes copied before timing. Constant-folding timing covers `transform` over all classes, including its normal context node handling. Input loading, initial inheritance-graph construction, and output serialization/hash comparisons are excluded. Each pass starts from fresh transformation state.

Allocation measures bytes allocated by the transforming thread, not retained heap or peak memory. The dead-block stress case drops from approximately 800.8 MB to 0.162 MB allocated per pass.

Analysis-call counts show the mechanism behind the constant-folding improvements:

| Workload | Before analyses | After analyses | Reduction | Changed classes (both variants) |
|---|---:|---:|---:|---:|
| AltarSMP-v1.jar | 2,374 | 1,311 | 44.8% | 77 |
| BreweryX-3.6.3.jar | 38,934 | 19,663 | 49.5% | 114 |
| Vulcan-2.9.7.12.jar | 21,732 | 15,564 | 28.4% | 429 |
| obf-sample-zkm-26.jar | 1,084 | 569 | 47.5% | 26 |

All nine cases produce identical SHA-256 hashes for transformed bytecode across both variants and all measured passes. All report zero transformation failures. The benchmark also checks that repeated passes produce deterministic output. The raw JSON records every timing/allocation sample, output hashes, fixture hashes, source hashes, and analysis counts.

Run from the repository with JDK 25:

```sh
python3 tools/performance/benchmark-deobfuscation.py --baseline 8fed3984f --forks 3 --warmup 5 --samples 7
```

The runner compiles the two transformer sources from the baseline revision and the working tree against the current core runtime. It does not alter the checkout. Local `real-jars` fixtures are required for the real-plugin cases; missing fixtures are reported and skipped. The ZKM fixture is included in the repository. Use `--case constant-folding` or another input/mode substring to select cases.

Raw samples: [deobfuscation.json](../build/performance/deobfuscation.json).
Benchmark sources: [runner](../tools/performance/benchmark-deobfuscation.py), [Java harness](../tools/performance/DeobfuscationBenchmark.java).

Validation:

```sh
TARGET_VERSION=25 ./gradlew :recaf-core:test --tests 'software.coley.recaf.services.deobfuscation.*' --tests 'software.coley.recaf.services.analysis.AreaAnalysisServiceTest' --offline --console=plain
```

The 327-case run completed with 325 passed, one skipped, and one timeout in the existing 10-second backtracking check. An isolated retry of that check passed in 1.331 seconds, giving 326 passing checks in total. The five new regression tests cover frame reuse, fresh analysis after stack replacement/insertion, large dead-block removal with local-variable repair, and preserved catch boundaries.

Validation logs: [suite](../build/performance/deobfuscation-tests.log), [isolated timeout retry](../build/performance/deobfuscation-timeout-retry.log).

## Bitwise folding and preview refreshes

Measured on 2026-11-14 against `1003f39e7eb52bebd05e558dcb89945430d9c958` with OpenJDK 25.0.5.0.3,
`-Xms512m -Xmx2g`. Each variant has three independent JVM processes, three warmup passes per process,
and five measured passes per process. The table reports medians over 15 samples. Tests did not run alongside the benchmark.

The bitwise pass now skips frame analysis when a method contains no supported bitwise opcode or JDK bit helper.
NOP cleanup uses a linked instruction walk, preserving the previous cleanup behavior without allocating an instruction array.

| Input | Before (ms) | After (ms) | Time reduction | Allocation reduction | Analyses before / after |
|---|---:|---:|---:|---:|---:|
| AltarSMP-v1.jar | 157.696 | 81.511 | 48.3% | 48.3% | 1,187 / 103 |
| BreweryX-3.6.3.jar | 299.280 | 104.554 | 65.1% | 69.3% | 19,467 / 119 |
| Vulcan-2.9.7.12.jar | 1,570.647 | 1,472.259 | 6.3% | 9.4% | 10,866 / 2,875 |
| obf-sample-zkm-26.jar | 135.338 | 53.261 | 60.6% | 50.6% | 542 / 64 |

All four cases produce identical transformed bytecode hashes across both variants and every measured pass.
These measurements cover one bitwise transformer pass, not the entire profile or application. Allocation counts
cover the transforming thread. Results depend on how many methods contain bitwise operations; the smaller Vulcan
gain should not be generalized to other obfuscated inputs.

Reproduce with:

```sh
TARGET_VERSION=25 python3 tools/performance/benchmark-deobfuscation.py --baseline 1003f39e7 --case bitwise-folding --forks 3 --warmup 3 --samples 5 --output build/performance/bitwise-preview-diff.json
```

Raw samples: [bitwise-preview-diff.json](../build/performance/bitwise-preview-diff.json).

Preview improvements are verified with JavaFX integration tests rather than wall-clock speed claims:

- Switching source → bytecode → source uses one transformation instead of three, and two source-render requests
  instead of four. The bytecode view and diff share the same transformed class.
- Pass-setting changes refresh only the transformed view. Package-prefix typing waits 250 ms before starting a new run.
- Each pane waits for its current decompilation before starting another. Obsolete transformations receive cancellation,
  and stale render callbacks cannot replace newer previews.
- Cached results are invalidated when the profile, selected class, workspace class contents, or decompiler settings change.
- The Diff tab compares matching preview versions, computes differences off the UI thread only when opened,
  and shows additions/removals with three context lines. Preview runs never commit transformation results.
