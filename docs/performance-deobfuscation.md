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
