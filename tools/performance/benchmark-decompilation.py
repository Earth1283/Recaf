#!/usr/bin/env python3
"""Compare decompiler integrations against a Git revision, with identical JVM settings.

Run from anywhere: python3 tools/performance/benchmark-decompilation.py --baseline HEAD
Requires the project's JDK, cached/resolvable Gradle dependencies, and local real-jars fixtures.
No input classes are loaded or executed. Each pass decompiles every top-level class of the input once.

The baseline variant is the baseline revision's services/decompile package, compiled against the current core runtime
and placed ahead of it on the classpath. The decompiler libraries are the same for both variants, so differences come
from how Recaf drives them. Both variants must produce identical output, or the run fails.
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
PACKAGE = "recaf-core/src/main/java/software/coley/recaf/services/decompile/"
NLOGIN = "real-jars/nLogin.jar!com/nickuc/login/loader/plugin.jar"
CASES = [
    ("cfr", NLOGIN),
    ("cfr", "real-jars/Vulcan-2.9.7.12.jar"),
    ("cfr", "real-jars/BreweryX-3.6.3.jar"),
    ("vineflower", NLOGIN),
    ("vineflower", "real-jars/Vulcan-2.9.7.12.jar"),
    ("vineflower", "real-jars/BreweryX-3.6.3.jar"),
]


def run(command, **kwargs):
    return subprocess.run(command, cwd=ROOT, check=True, text=True, **kwargs)


def fixture_path(fixture):
    return ROOT / fixture.split("!")[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="HEAD")
    parser.add_argument("--forks", type=int, default=2)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--samples", type=int, default=2)
    parser.add_argument("--limit", type=int, default=0, help="Only decompile the first N top-level classes (0 = all)")
    parser.add_argument("--case", default="", help="Only cases whose decompiler/input contains this text")
    parser.add_argument("--output", default="build/performance/decompilation.json")
    args = parser.parse_args()
    if min(args.forks, args.samples) < 1 or args.warmup < 0 or args.limit < 0:
        parser.error("forks/samples must be positive, warmup and limit nonnegative")
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    revision = run(["git", "rev-parse", args.baseline], capture_output=True).stdout.strip()
    report = {"baseline": revision, "java": run(["java", "-version"], capture_output=True).stderr.strip(),
              "platform": platform.platform(), "cpu": platform.processor(),
              "jvm": ["-Xms512m", "-Xmx4g"], "forks": args.forks, "warmup": args.warmup,
              "samples": args.samples, "limit": args.limit, "source_sha256": {}, "cases": []}
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

        baseline_src = temp / "baseline" / "src"
        baseline_classes = temp / "baseline" / "classes"
        baseline_classes.mkdir(parents=True)
        paths = run(["git", "ls-tree", "-r", "--name-only", revision, PACKAGE], capture_output=True).stdout.split()
        sources = []
        for path in paths:
            if not path.endswith(".java"):
                continue
            content = run(["git", "show", revision + ":" + path], capture_output=True).stdout
            source = baseline_src / Path(path).relative_to(PACKAGE)
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(content)
            sources.append(str(source))
            report["source_sha256"]["baseline:" + path] = hashlib.sha256(content.encode()).hexdigest()
        for path in sorted((ROOT / PACKAGE).rglob("*.java")):
            relative = str(path.relative_to(ROOT))
            report["source_sha256"]["candidate:" + relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        run(["javac", "-nowarn", "-g", "-parameters", "-cp", classpath, "-d", str(baseline_classes), *sources])

        harness = temp / "harness"
        harness.mkdir()
        run(["javac", "-cp", classpath, "-d", str(harness), "tools/performance/DecompilationBenchmark.java"])
        variants = {"baseline": os.pathsep.join((str(harness), str(baseline_classes), classpath)),
                    "candidate": os.pathsep.join((str(harness), classpath))}
        env = {**os.environ, "LC_ALL": "C.UTF-8", "LANG": "C.UTF-8"}
        for decompiler, fixture in CASES:
            if args.case not in decompiler + ":" + fixture:
                continue
            if not fixture_path(fixture).is_file():
                print("Missing fixture, skipping: " + fixture, flush=True)
                continue
            case = {"decompiler": decompiler, "input": fixture,
                    "input_sha256": hashlib.sha256(fixture_path(fixture).read_bytes()).hexdigest(),
                    "runs": {"baseline": [], "candidate": []}}
            print(f"Benchmarking {decompiler}: {fixture}", flush=True)
            for fork in range(args.forks):
                order = ("baseline", "candidate") if fork % 2 == 0 else ("candidate", "baseline")
                for variant in order:
                    command = ["java", *report["jvm"], "-cp", variants[variant], "DecompilationBenchmark",
                               decompiler, str(ROOT / fixture), str(args.warmup), str(args.samples)]
                    if args.limit:
                        command.append(str(args.limit))
                    proc = run(command, capture_output=True, env=env)
                    lines = [line[5:] for line in proc.stdout.splitlines() if line.startswith("PERF ")]
                    if len(lines) != 1:
                        raise RuntimeError(proc.stdout + proc.stderr)
                    case["runs"][variant].append(json.loads(lines[0]))
                    print(f"  {variant} fork {fork + 1} complete", flush=True)
            signatures = {(t["digest"], t["failures"], t["chars"])
                          for runs in case["runs"].values() for r in runs for t in r["trials"]}
            if len(signatures) != 1:
                output.with_suffix(".mismatch.json").write_text(json.dumps(case, indent=2))
                raise AssertionError("Baseline/candidate outputs differ: " + decompiler + " " + fixture)
            case["classes"] = case["runs"]["candidate"][0]["classes"]
            for variant, runs in case["runs"].items():
                trials = [t for r in runs for t in r["trials"]]
                case[variant + "_ms"] = statistics.median(t["nanos"] for t in trials) / 1e6
                case[variant + "_bytes"] = statistics.median(t["allocatedBytes"] for t in trials)
            case["time_reduction_percent"] = 100 * (1 - case["candidate_ms"] / case["baseline_ms"])
            case["allocation_reduction_percent"] = 100 * (1 - case["candidate_bytes"] / case["baseline_bytes"])
            report["cases"].append(case)
            output.write_text(json.dumps(report, indent=2) + "\n")
            print(f"  {case['baseline_bytes'] / 2**20:.0f} -> {case['candidate_bytes'] / 2**20:.0f} MiB allocated "
                  f"({case['allocation_reduction_percent']:.1f}% less), identical output", flush=True)
    if not report["cases"]:
        raise RuntimeError("No matching fixtures")
    print("\n| Decompiler / input | Classes | Allocated before (MiB) | After (MiB) | Allocation reduction "
          "| Time before (s) | After (s) |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for case in report["cases"]:
        name = Path(case["input"].split("!")[-1]).name
        print(f"| {case['decompiler']} / {name} | {case['classes']} | {case['baseline_bytes'] / 2**20:,.0f} | "
              f"{case['candidate_bytes'] / 2**20:,.0f} | {case['allocation_reduction_percent']:.1f}% | "
              f"{case['baseline_ms'] / 1000:.1f} | {case['candidate_ms'] / 1000:.1f} |")
    print("\nRaw samples: " + str(output))


if __name__ == "__main__":
    main()
