#!/usr/bin/env python3
"""Runs one giant-JSON measurement on the emulator: force-stops the sample, launches it with
`--es giant <spec>` (and `--el maxBodyBytes N`), waits for the run's `done` line, and appends every
`HopiSample` JSON line to an NDJSON file, with `dumpsys meminfo` read before the first run
(during the spec's `delay`) and after the last, plus any ANR / OOM / crash lines from logcat.

usage: giant.py <label> <spec> <out.ndjson> [--cap BYTES] [--store BYTES] [--timeout S] [--poll]
--store sets Hopi's store byte cap (`--el maxTotalBytes`); the plugin clamps the cap to half.
--poll samples dumpsys meminfo every second while it runs (for the peak; it perturbs timing).
"""
import json, os, re, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "dev.koga.hopi.sample"


def adb(*a, check=True):
    return subprocess.run([ADB, *a], capture_output=True, text=True, check=check).stdout


def meminfo():
    out = adb("shell", "dumpsys", "meminfo", PKG, check=False)
    fields = {}
    for key, pattern in (("javaHeapKb", r"Java Heap:\s+(\d+)"), ("nativeHeapKb", r"Native Heap:\s+(\d+)"),
                         ("totalPssKb", r"TOTAL PSS:\s+(\d+)"), ("totalRssKb", r"TOTAL RSS:\s+(\d+)"),
                         ("graphicsKb", r"Graphics:\s+(\d+)")):
        m = re.search(pattern, out)
        if m:
            fields[key] = int(m.group(1))
    return fields


def main():
    label, spec, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    args = sys.argv[4:]
    cap = args[args.index("--cap") + 1] if "--cap" in args else None
    store = args[args.index("--store") + 1] if "--store" in args else None
    limit = float(args[args.index("--timeout") + 1]) if "--timeout" in args else 900
    poll = "--poll" in args
    if "delay=" not in spec:
        spec += ";delay=6"
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    start = ["shell", "am", "start", "-W", "-n", PKG + "/.MainActivity", "--es", "giant", "'" + spec + "'"]
    if cap:
        start += ["--el", "maxBodyBytes", cap]
    if store:
        start += ["--el", "maxTotalBytes", store]
    adb(*start)
    logcat = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I", "AndroidRuntime:E",
                               "ActivityManager:I", "art:I", "lowmemorykiller:I"],
                              stdout=subprocess.PIPE, text=True)
    records, before, after, peak, problems = [], None, None, {}, []
    deadline = time.time() + limit
    done = False
    import queue, threading
    lines = queue.Queue()

    def pump():
        for raw in logcat.stdout:
            lines.put(raw)
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()
    next_poll = 0
    while time.time() < deadline and not done:
        if poll and time.time() >= next_poll and before is not None:
            m = meminfo()
            for k, v in m.items():
                peak[k] = max(peak.get(k, 0), v)
            next_poll = time.time() + 1
        try:
            line = lines.get(timeout=0.5)
        except queue.Empty:
            continue
        if line is None:
            break
        line = line.strip()
        if line.startswith("{\"giant\""):
            rec = json.loads(line)
            if rec.get("waiting"):
                time.sleep(3)
                before = meminfo()
                print("before", json.dumps(before), flush=True)
                continue
            if rec.get("done"):
                done = True
                continue
            if rec.get("start"):
                continue
            records.append(rec)
            print(json.dumps(rec), flush=True)
        elif re.search(r"ANR in|OutOfMemory|FATAL EXCEPTION|has died|Process " + re.escape(PKG) + r".*(died|crash)|lowmemorykiller", line):
            problems.append(line)
            print("PROBLEM:", line, flush=True)
            if "FATAL" in line or "has died" in line:
                break
    logcat.terminate()
    after = meminfo() if done else None
    pid = adb("shell", "pidof", PKG, check=False).strip()
    with open(out_path, "a") as f:
        f.write(json.dumps({"label": label, "spec": spec, "cap": cap, "store": store, "done": done, "alive": bool(pid),
                            "meminfoBefore": before, "meminfoAfter": after, "meminfoPeak": peak or None,
                            "problems": problems, "runs": records}) + "\n")
    print(json.dumps({"label": label, "done": done, "alive": bool(pid), "before": before, "after": after, "peak": peak, "problems": len(problems)}))


main()
