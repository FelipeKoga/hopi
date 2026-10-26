#!/usr/bin/env python3
"""Idle cost of the performance monitors on Android (docs/design/perf-monitors.md, "Performance
plan", the idle arm). Per arm, launches the sample with `--es perfBench 'mode=idle;monitors=<set>'`
(the app starts those monitors and then does nothing), waits for its ready line and SETTLE seconds,
then reads every thread's CPU ticks (`/proc/<pid>/task/*/stat`, utime+stime) and voluntary and
involuntary context switches (`/proc/<pid>/task/*/status`) at the start and the end of SECONDS idle
seconds, through `run-as` (the debug build's own uid). Hopi's threads are the `hopi-*` ones. A wake
is a voluntary context switch: a thread that blocked and was woken. One JSON line per arm on stdout.

An arm is `label=monitors[:option=value…][@package]` (see bench.py; `heartbeat=1`, the noop install).

usage: idle.py [--seconds 60] [--settle 10] [--arms noop=none@dev.koga.hopi.sample.noop,off=none,all=all]
"""
import json, os, subprocess, sys, time

PACKAGE = "dev.koga.hopi.sample"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TICK_US = 10_000  # USER_HZ 100


def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True).stdout


def threads(package, pid):
    """tid -> {name, ticks, voluntary, involuntary}. `comm` sits in parentheses and may hold spaces."""
    stat = adb("shell", "run-as", package, "sh", "-c", f"'cat /proc/{pid}/task/*/stat'")
    result = {}
    for line in stat.splitlines():
        if ")" not in line:
            continue
        head, rest = line.rsplit(")", 1)
        tid, name = head.split(" (", 1)
        fields = rest.split()
        result[int(tid)] = {"name": name, "ticks": int(fields[11]) + int(fields[12]), "voluntary": 0, "involuntary": 0}
    status = adb("shell", "run-as", package, "sh", "-c",
                 f"'for t in /proc/{pid}/task/*; do echo \"tid ${{t##*/}}\"; grep ctxt_switches $t/status; done'")
    tid = None
    for line in status.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0] == "tid":
            tid = int(parts[1])
        elif tid in result and line.startswith("voluntary_ctxt_switches"):
            result[tid]["voluntary"] = int(parts[1])
        elif tid in result and line.startswith("nonvoluntary_ctxt_switches"):
            result[tid]["involuntary"] = int(parts[1])
    return result


def idle_arm(label, monitors, package, seconds, settle, extra=""):
    adb("shell", "am", "force-stop", package)
    adb("logcat", "-c")
    reader = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I"], stdout=subprocess.PIPE, text=True)
    adb("shell", "am", "start", "-W", "-n", f"{package}/dev.koga.hopi.sample.MainActivity", "--es", "perfBench", f"'mode=idle;monitors={monitors}" + (f";{extra}" if extra else "") + "'")
    ready = None
    for raw in reader.stdout:
        start = raw.find('{"perfBench":"idle"')
        if start >= 0:
            ready = json.loads(raw[start:])
            break
    reader.terminate()
    reader.wait()
    pid = adb("shell", "pidof", package).strip()
    time.sleep(settle)
    before = threads(package, pid)
    time.sleep(seconds)
    after = threads(package, pid)
    adb("shell", "am", "force-stop", package)
    rows = []
    for tid, now in after.items():
        then = before.get(tid, {"ticks": 0, "voluntary": 0, "involuntary": 0})
        rows.append({"tid": tid, "name": now["name"], "cpuUs": (now["ticks"] - then["ticks"]) * TICK_US,
                     "wakes": now["voluntary"] - then["voluntary"], "preempted": now["involuntary"] - then["involuntary"], "new": tid not in before})
    rows.sort(key=lambda r: (-r["cpuUs"], -r["wakes"]))
    hopi = [r for r in rows if r["name"].startswith("hopi")]
    main_thread = next((r for r in rows if str(r["tid"]) == pid), None)
    return {"idle": label, "monitors": monitors, "options": extra, "package": package, "running": ready and ready.get("running"), "seconds": seconds,
            "threads": len(after), "processCpuUs": sum(r["cpuUs"] for r in rows), "processWakes": sum(r["wakes"] for r in rows),
            "main": main_thread, "hopiThreads": hopi, "hopiCpuUs": sum(r["cpuUs"] for r in hopi), "hopiWakes": sum(r["wakes"] for r in hopi),
            "busiest": rows[:8]}


def main():
    argv = sys.argv[1:]
    seconds = float(argv[argv.index("--seconds") + 1]) if "--seconds" in argv else 60
    settle = float(argv[argv.index("--settle") + 1]) if "--settle" in argv else 10
    arms = argv[argv.index("--arms") + 1] if "--arms" in argv else "off=none,all=all"
    for item in arms.split(","):
        label, target = item.split("=", 1)
        target, _, package = target.partition("@")
        monitors, _, extra = target.partition(":")
        print(json.dumps(idle_arm(label, monitors.replace("+", ","), package or PACKAGE, seconds, settle, extra.replace(":", ";"))), flush=True)


if __name__ == "__main__":
    main()
