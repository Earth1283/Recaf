# Decompilation allocation measurements

Measured on 2026-11-15 against decompiler integration sources at `08ca1dd0e`.

Recaf creates a fresh decompiler for every class it decompiles. Much of what each decompiler allocated was setup that
did not depend on the class: building context, then parsing again the classes the target refers to. On a large or
heavily obfuscated plugin that setup was repeated hundreds of times.

| Change | Resulting behavior |
|---|---|
| Lazy Vineflower library | Vineflower asks for classes by name as it needs them. It no longer receives a list of every workspace class, and no longer opens and walks the whole Java runtime image itself (`include-runtime=0`); runtime classes come from Recaf's cached runtime resource. Class bytes are handed over without the copy Vineflower's default stream read makes. |
| Cached Vineflower options | The option map is rebuilt only when the configuration changes, not for every class. |
| Declaration-only referenced classes for CFR | CFR parses every class the target refers to in full, mostly to read method signatures and the type hierarchy. Those classes are now given as cached stubs that keep every declaration but replace method bodies with `aconst_null; athrow`. Classes CFR reads code from keep their full bytecode: classes nested with the target, found through inner class and enclosing class attributes, and enum switch lookup classes, found through the `int[]` fields the nest refers to. Obfuscators often rename lookup classes and remove their inner class attributes. |
| Streamed "decompile all" archive | Decompiled sources are written into the output zip as each class finishes. Before, every source was kept in memory and the whole archive was built in a byte array at the end. |

The table shows the median over two JVM processes, each with one warmup pass and one measured pass. A pass decompiles
every top-level class of the input once, in name order, on one thread. Baseline/candidate order alternates between
processes. The baseline is the `services/decompile` package from `08ca1dd0e`, compiled against the same core runtime
and decompiler libraries as the candidate.

Environment: Linux x86_64 VM, AMD EPYC-Milan, 8 logical CPUs; OpenJDK 25.0.4.1; `-Xms512m -Xmx4g`. Benchmarks ran
without the test suite running alongside them.

| Decompiler / input | Classes | Allocated before (MiB) | After (MiB) | Allocation reduction | Time before (s) | After (s) |
|---|---:|---:|---:|---:|---:|---:|
| CFR / nLogin embedded `plugin.jar` | 727 | 18,803 | 14,500 | 22.9% | 18.8 | 16.0 |
| CFR / Vulcan-2.9.7.12.jar | 652 | 52,726 | 50,732 | 3.8% | 83.3 | 82.7 |
| CFR / BreweryX-3.6.3.jar | 1,618 | 9,082 | 6,866 | 24.4% | 8.8 | 8.2 |
| Vineflower / nLogin embedded `plugin.jar` | 727 | 60,340 | 41,021 | 32.0% | 51.6 | 28.6 |
| Vineflower / Vulcan-2.9.7.12.jar | 652 | 102,615 | 84,526 | 17.6% | 80.8 | 58.5 |
| Vineflower / BreweryX-3.6.3.jar | 1,618 | 118,228 | 74,080 | 37.3% | 101.5 | 48.2 |

Allocation counts bytes allocated by all threads during the pass (`getTotalThreadAllocatedBytes`), including the
per-method threads Vineflower uses for timeouts. It does not measure retained heap or peak memory. Allocation is the
number to compare across machines: it does not depend on CPU speed, while the time columns only describe this VM.
On a slower machine the same allocation costs more garbage collection time.

The smaller CFR gain on Vulcan is expected: Vulcan's cost is dominated by CFR analysing its own heavily obfuscated
method bodies, not by parsing the classes they refer to.

The stubs are cached per class and dropped with it. Stubs for every class of nLogin's plugin take 1.2 MB, against
7.6 MB of original bytecode; for BreweryX, 3.7 MB against 8.9 MB. Only classes that something refers to get a stub.

## Identical output

Every case produces identical output in both variants and in every pass. Before comparing, the benchmark normalizes
three things that vary even when the same code is decompiled twice in one process, and that only appear in CFR output:

- Source line numbers of Recaf classes in the stack traces CFR prints for methods it fails to decompile.
- CFR's synthetic `v<number>` variable names, which come from a counter shared by every decompilation in the process.
  They are renumbered in order of appearance, so a structural difference still changes the digest.
- The list of blocks in CFR's "Tried to end blocks" failure message, which follows identity hash order.

Beyond the benchmark, single passes were compared class by class against the baseline:

| Decompiler | Inputs | Classes | Differences |
|---|---|---:|---|
| Vineflower | AltarSMP-v1, LiteBans, Vulcan-2.9.7.12, RealisticSeasons-12.0.0, mcMMO, BreweryX-3.6.3, `obf-sample-zkm-26.jar`, nLogin `plugin.jar` | 5,475 | None |
| CFR | The same eight inputs | 5,475 | Only the failure-message block order above, in three classes |

An earlier version of the CFR change kept only nested classes in full. That changed enum switches in five AltarSMP
and LiteBans classes whose obfuscated lookup classes had lost their inner class attributes, which is why lookup
classes are now found through `int[]` field references. A regression test reproduces that case.

Procyon is unchanged. It names local variables after the parameter names of the methods they are passed to, which it
reads from the method bodies of the referenced classes, so stubs changed its output in hundreds of classes.

## Reproduce

Run from the repository with JDK 25:

```sh
TARGET_VERSION=25 python3 tools/performance/benchmark-decompilation.py --baseline 08ca1dd0e --forks 2 --warmup 1 --samples 1
```

Whole-jar passes take minutes each; use `--case` to select a decompiler or input, or `--limit` to decompile only the
first classes of each input. Local `real-jars` fixtures are required; missing fixtures are skipped. The runner fails
if the two variants produce different output.

Raw samples: [decompilation.json](../build/performance/decompilation.json).
Benchmark sources: [runner](../tools/performance/benchmark-decompilation.py), [Java harness](../tools/performance/DecompilationBenchmark.java).

Validation:

```sh
TARGET_VERSION=25 ./gradlew :recaf-core:test --tests 'software.coley.recaf.services.decompile.*'
```

`DecompilerContextTest` covers the stubs, the code scope, a CFR enum switch through a renamed lookup class without
inner class attributes, and Vineflower resolving runtime generics without its own runtime scan. The CFR tests fail
with the lookup-table rule disabled, and the Vineflower test fails with the runtime lookup disabled.
