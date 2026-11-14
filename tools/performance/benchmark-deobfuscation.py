#!/usr/bin/env python3
"""Compare deobfuscation algorithms against a Git revision, with identical JVM settings.

Run from anywhere: python3 tools/performance/benchmark-deobfuscation.py --baseline 8fed3984f
Requires the project's JDK, cached/resolvable Gradle dependencies, and local real-jars fixtures.
No input classes are loaded or executed. Timing excludes input loading and output hashing.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "recaf-core/src/main/java/software/coley/recaf/services/deobfuscation/transform/generic/"
SOURCES = [PACKAGE + name + ".java" for name in
           ("DeadCodeRemovingTransformer", "OpaqueConstantFoldingTransformer")]
CASES = [
    ("dead-code", "synthetic:dead:20000"),
    ("dead-code", "synthetic:locals:5000"),
    ("dead-code", "real-jars/AltarSMP-v1.jar"),
    ("dead-code", "real-jars/BreweryX-3.6.3.jar"),
    ("dead-code", "real-jars/Vulcan-2.9.7.12.jar"),
    ("constant-folding", "real-jars/AltarSMP-v1.jar"),
    ("constant-folding", "real-jars/BreweryX-3.6.3.jar"),
    ("constant-folding", "real-jars/Vulcan-2.9.7.12.jar"),
    ("constant-folding", "recaf-core/src/testFixtures/resources/samples/obf-sample-zkm-26.jar"),
    ("bitwise-folding", "real-jars/AltarSMP-v1.jar"),
    ("bitwise-folding", "real-jars/BreweryX-3.6.3.jar"),
    ("bitwise-folding", "real-jars/Vulcan-2.9.7.12.jar"),
    ("bitwise-folding", "recaf-core/src/testFixtures/resources/samples/obf-sample-zkm-26.jar"),
]


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="HEAD")
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--samples", type=int, default=7)
    parser.add_argument("--case", default="", help="Only cases whose mode/input contains this text")
    parser.add_argument("--output", default="build/performance/deobfuscation.json")
    args = parser.parse_args()
    if min(args.forks, args.samples) < 1 or args.warmup < 0:
        parser.error("forks/samples must be positive and warmup nonnegative")
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    revision = run(["git", "rev-parse", args.baseline], capture_output=True).stdout.strip()
    bitwise_supported = subprocess.run(
        ["git", "cat-file", "-e", revision + ":" + PACKAGE + "BitwiseFoldingTransformer.java"],
        cwd=ROOT, capture_output=True).returncode == 0
    report = {"baseline": revision, "java": run(["java", "-version"], capture_output=True).stderr.strip(),
              "platform": platform.platform(), "cpu": platform.processor(),
              "jvm": ["-Xms512m", "-Xmx2g"], "forks": args.forks, "warmup": args.warmup,
              "samples": args.samples, "source_sha256": {}, "cases": []}
    with tempfile.TemporaryDirectory(prefix="recaf-perf-") as temp:
        temp = Path(temp)
        init = temp / "performance.gradle"
        cp_file = temp / "classpath.txt"
        init.write_text("""
allprojects {
    afterEvaluate { p ->
        if (p.name == 'recaf-core') {
            tasks.register('performanceClasspath') {
                dependsOn tasks.named('classes')
                doLast { file(project.property('performanceClasspathFile')).text = sourceSets.main.runtimeClasspath.asPath }
            }
        }
    }
}
""")
        run(["./gradlew", ":recaf-core:performanceClasspath", "--console=plain", "-I", str(init),
             "-PperformanceClasspathFile=" + str(cp_file)],
            env={**os.environ, "TARGET_VERSION": os.environ.get("TARGET_VERSION", "25")})
        classpath = cp_file.read_text()
        classes = {}
        for variant in ("baseline", "candidate"):
            source_dir = temp / variant / "src"
            classes[variant] = temp / variant / "classes"
            source_dir.mkdir(parents=True)
            classes[variant].mkdir()
            sources = []
            for path in SOURCES:
                content = (run(["git", "show", revision + ":" + path], capture_output=True).stdout
                           if variant == "baseline" else (ROOT / path).read_text())
                source = source_dir / Path(path).name
                source.write_text(content)
                sources.append(str(source))
                report["source_sha256"][variant + ":" + path] = hashlib.sha256(content.encode()).hexdigest()
            # Compile both variants identically; overlay only the changed classes on the current runtime.
            run(["javac", "-g", "-parameters", "-cp", classpath, "-d", str(classes[variant]), *sources])
        harness = temp / "harness"
        harness.mkdir()
        run(["javac", "-cp", classpath, "-d", str(harness),
             "tools/performance/DeobfuscationBenchmark.java"])
        for mode, fixture in CASES:
            if args.case not in mode + ":" + fixture:
                continue
            if mode == "bitwise-folding" and not bitwise_supported:
                print("Baseline predates the bitwise transformer, skipping: " + fixture, flush=True)
                continue
            if not fixture.startswith("synthetic:") and not (ROOT / fixture).is_file():
                print("Missing fixture, skipping: " + fixture, flush=True)
                continue
            case = {"mode": mode, "input": fixture, "runs": {"baseline": [], "candidate": []}}
            if not fixture.startswith("synthetic:"):
                case["input_sha256"] = hashlib.sha256((ROOT / fixture).read_bytes()).hexdigest()
            print(f"Benchmarking {mode}: {fixture}", flush=True)
            for fork in range(args.forks):
                order = ("baseline", "candidate") if fork % 2 == 0 else ("candidate", "baseline")
                for variant in order:
                    cp = os.pathsep.join((str(harness), str(classes[variant]), classpath))
                    proc = run(["java", *report["jvm"], "-cp", cp, "DeobfuscationBenchmark", mode,
                                fixture, str(args.warmup), str(args.samples)], capture_output=True)
                    lines = [line[5:] for line in proc.stdout.splitlines() if line.startswith("PERF ")]
                    if len(lines) != 1:
                        raise RuntimeError(proc.stdout + proc.stderr)
                    case["runs"][variant].append(json.loads(lines[0]))
                    print(f"  {variant} fork {fork + 1} complete", flush=True)
            signatures = {(t["digest"], t["failures"], t["changed"])
                          for runs in case["runs"].values() for r in runs for t in r["trials"]}
            if len(signatures) != 1:
                output.with_suffix(".mismatch.json").write_text(json.dumps(case, indent=2))
                raise AssertionError("Baseline/candidate outputs differ: " + fixture)
            for variant, runs in case["runs"].items():
                trials = [t for r in runs for t in r["trials"]]
                case[variant + "_ms"] = statistics.median(t["nanos"] for t in trials) / 1e6
                case[variant + "_bytes"] = statistics.median(t["allocatedBytes"] for t in trials)
            case["time_reduction_percent"] = 100 * (1 - case["candidate_ms"] / case["baseline_ms"])
            case["allocation_reduction_percent"] = 100 * (1 - case["candidate_bytes"] / case["baseline_bytes"])
            report["cases"].append(case)
            output.write_text(json.dumps(report, indent=2) + "\n")
            print(f"  {case['baseline_ms']:.3f} -> {case['candidate_ms']:.3f} ms "
                  f"({case['time_reduction_percent']:.1f}% less time), identical output", flush=True)
    if not report["cases"]:
        raise RuntimeError("No matching fixtures")
    print("\n| Operation / input | Before (ms) | After (ms) | Time reduction | Allocation reduction |")
    print("|---|---:|---:|---:|---:|")
    for case in report["cases"]:
        print(f"| {case['mode']} / {Path(case['input']).name} | {case['baseline_ms']:.3f} | "
              f"{case['candidate_ms']:.3f} | {case['time_reduction_percent']:.1f}% | "
              f"{case['allocation_reduction_percent']:.1f}% |")
    print("\nRaw samples: " + str(output))


if __name__ == "__main__":
    main()
