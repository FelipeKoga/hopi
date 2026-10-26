#!/usr/bin/env python3
"""Overhead A/B of the performance monitors on the main thread (docs/design/perf-monitors.md,
"Performance plan", the printer's 3 µs gate). Each arm launches the sample with
`perfBench 'monitors=<set>;n=<n>;runs=<runs>'` (Android `--es perfBench`, iOS `-perfBench`): the app
starts those monitors (none: the module present, nothing started), waits 3 s, then per unit kind
posts `n` main-thread units one after the other and logs the median, p90, p99 and mean gap between
unit starts, the main thread's CPU per unit and the GC count (Android). Arms alternate in blocks so
drift hits every arm alike. Writes the raw lines to <out>/<platform>-bench.ndjson and prints, per
arm and kind, the median over runs and blocks and its difference from the first arm.

An arm is `label=monitors[:option=value…][@package]`: the monitors (`+` between several), spec
options such as `heartbeat=1`, and on Android a second install, e.g. the noop build: `./gradlew :sample:androidApp:assembleDebug
-Psample.hopiCapture=false -Psample.applicationIdSuffix=.noop` gives `dev.koga.hopi.sample.noop`.

usage: bench.py <out dir> [--platform android|ios] [--blocks 3] [--n 10000] [--runs 3]
                [--arms off=none,hangs=hangs,frames=frames,all=all]
"""
import json, os, statistics, subprocess, sys, time

PACKAGE = "dev.koga.hopi.sample"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def option(argv, name, default):
    return argv[argv.index(name) + 1] if name in argv else default


def bench_line(raw):
    start = raw.find('{"perfBench"')
    if start < 0:
        return None
    try:
        return json.loads(raw[start:].strip())
    except json.JSONDecodeError:
        return None


def run_arm(platform, package, spec, timeout=600):
    lines = []
    if platform == "android":
        subprocess.run([ADB, "shell", "am", "force-stop", package], check=True)
        subprocess.run([ADB, "logcat", "-c"], check=True)
        reader = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I"], stdout=subprocess.PIPE, text=True)
        subprocess.run([ADB, "shell", "am", "start", "-W", "-n", f"{package}/dev.koga.hopi.sample.MainActivity",
                        "--es", "perfBench", f"'{spec}'"], check=True, capture_output=True)
    else:
        reader = subprocess.Popen(["xcrun", "simctl", "launch", "--console-pty", "--terminate-running-process", "booted", package,
                                   "-perfBench", spec], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.time() + timeout
    for raw in reader.stdout:
        line = bench_line(raw)
        if line:
            lines.append(line)
            if line["perfBench"] == "done":
                break
        if time.time() > deadline:
            break
    reader.terminate()
    reader.wait()
    if platform == "android":
        subprocess.run([ADB, "shell", "am", "force-stop", package], check=True)
    else:
        subprocess.run(["xcrun", "simctl", "terminate", "booted", package], check=False, capture_output=True)
    return lines


def main():
    argv = sys.argv[1:]
    out = argv[0]
    platform = option(argv, "--platform", "android")
    blocks = int(option(argv, "--blocks", "3"))
    n = int(option(argv, "--n", "10000"))
    runs = int(option(argv, "--runs", "3"))
    arms = []
    for item in option(argv, "--arms", "off=none,hangs=hangs,frames=frames,all=all").split(","):
        label, target = item.split("=", 1)
        target, _, package = target.partition("@")
        monitors, _, extra = target.partition(":")
        arms.append((label, monitors.replace("+", ","), package or PACKAGE, extra.replace(":", ";")))
    os.makedirs(out, exist_ok=True)
    raw_path = os.path.join(out, f"{platform}-bench.ndjson")
    results = {}
    with open(raw_path, "a") as raw:
        for block in range(blocks):
            order = arms if block % 2 == 0 else list(reversed(arms))
            for label, monitors, package, extra in order:
                spec = f"monitors={monitors};n={n};runs={runs}" + (f";{extra}" if extra else "")
                lines = run_arm(platform, package, spec)
                for line in lines:
                    line["arm"], line["block"] = label, block
                    raw.write(json.dumps(line) + "\n")
                    if line["perfBench"] == "messages":
                        results.setdefault((label, line["kind"]), []).append(line)
                print(f"block {block + 1}/{blocks} {label}: {sum(1 for l in lines if l['perfBench'] == 'messages')} runs"
                      f" (running: {next((l['running'] for l in lines), '?')})", file=sys.stderr)
    kinds = sorted({kind for _, kind in results})
    base = arms[0][0]
    print(f"{'arm':<10} {'kind':<10} {'median ns':>10} {'d vs ' + base:>10} {'p99 ns':>9} {'cpu ns':>8} {'gc':>5} runs")
    for kind in kinds:
        base_rows = results.get((base, kind), [])
        base_median = statistics.median(r["medianNs"] for r in base_rows) if base_rows else None
        for label, _, _, _ in arms:
            rows = results.get((label, kind), [])
            if not rows:
                continue
            median = statistics.median(r["medianNs"] for r in rows)
            p99 = statistics.median(r["p99Ns"] for r in rows)
            cpu = statistics.median(r["mainCpuNsPerUnit"] for r in rows)
            gc = statistics.median(r["gc"] for r in rows)
            delta = f"{median - base_median:+.0f}" if base_median is not None else "-"
            print(f"{label:<10} {kind:<10} {median:>10.0f} {delta:>10} {p99:>9.0f} {cpu:>8.0f} {gc:>5.0f} {len(rows)}")


if __name__ == "__main__":
    main()
