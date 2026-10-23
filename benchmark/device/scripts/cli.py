#!/usr/bin/env python3
"""Times `hopi get <id> --json` of the largest captured body while sampling the app's
`dumpsys meminfo` every 0.5 s from the host, then prints the app's own frame-gap windows
(the sample's `hold` lines) logged meanwhile.

usage: cli.py <label> <times> <out.jsonl> [--platform android|ios] [--no-mem]
--no-mem skips the meminfo sampling, which itself costs the app binder work.
The app must be running a giant spec with hold=N (its frame ticker on) when this starts.
"""
import json, os, re, subprocess, sys, threading, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "dev.koga.hopi.sample"
CLI = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))), "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")


def main():
    label, times, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    platform = sys.argv[sys.argv.index("--platform") + 1] if "--platform" in sys.argv else "android"
    base = [CLI]
    lst = subprocess.run(base + ["list", "--app", PKG, "--platform", platform, "--json"], capture_output=True, text=True)
    rows = [json.loads(l) for l in lst.stdout.splitlines() if l.startswith("{")]
    rows = [r for r in rows if r.get("type") == "transaction"]
    target = max(rows, key=lambda r: r.get("resBytes") or 0)
    peaks, stop = [], threading.Event()

    def sample():
        while not stop.is_set():
            if platform == "android" and "--no-mem" not in sys.argv:
                m = subprocess.run([ADB, "shell", "dumpsys", "meminfo", PKG], capture_output=True, text=True).stdout
                j = re.search(r"Java Heap:\s+(\d+)", m)
                p = re.search(r"TOTAL PSS:\s+(\d+)", m)
                if j and p:
                    peaks.append((int(j.group(1)), int(p.group(1))))
            time.sleep(0.5)

    results = []
    for i in range(times):
        stop.clear()
        peaks.clear()
        t = threading.Thread(target=sample, daemon=True)
        t.start()
        started = time.perf_counter()
        r = subprocess.run(base + ["get", str(target["id"]), "--app", PKG, "--platform", platform, "--json"], capture_output=True)
        elapsed = time.perf_counter() - started
        stop.set()
        t.join()
        detail = json.loads(r.stdout) if r.returncode == 0 else None
        body = (detail or {}).get("response", {}).get("body") or {}
        results.append({"label": label, "run": i + 1, "id": target["id"], "url": target.get("url"), "exit": r.returncode,
                        "seconds": round(elapsed, 3), "stdoutBytes": len(r.stdout), "capturedBytes": body.get("capturedBytes"),
                        "truncated": body.get("truncated"), "stderr": r.stderr.decode()[-300:],
                        "appJavaHeapPeakKb": max([p[0] for p in peaks] or [0]), "appPssPeakKb": max([p[1] for p in peaks] or [0]),
                        "samples": len(peaks)})
        print(json.dumps(results[-1]), flush=True)
        time.sleep(2)
    with open(out, "a") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")


main()
