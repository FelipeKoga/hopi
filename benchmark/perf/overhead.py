#!/usr/bin/env python3
"""Overhead matrix of the perf monitors on Android, measured from outside the app (verification
harness for docs/design/perf-monitors.md; reuses benchmark/perf/idle.py's thread reader).

Per arm: launch the sample with `--es perfBench 'mode=idle;monitors=<set>'`, wait for its ready
line and SETTLE s, optionally attach a viewer (what the desktop viewer opens: `hopi tail` plus
`hopi perf tail --samples --fps`), then
  1. idle: every thread's CPU ticks and voluntary context switches over IDLE s;
  2. scroll: `dumpsys gfxinfo reset`, SWIPES identical `input swipe` flings on the screen,
     `dumpsys gfxinfo framestats`: frame total and UI-thread time per frame (p50/p90/p99), janky
     share, process CPU per second and Hopi threads' CPU and wakes over the scroll.
One JSON line per arm on stdout.

An arm: `label=monitors[@package][+viewer]`, monitors `none`, `all` or `frames/hangs/...` joined by `/`.
usage: overhead.py --arms ... [--idle 60] [--settle 10] [--swipes 20] [--blocks 1]
"""
import json, os, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import idle as idlemod  # noqa: E402

ADB = idlemod.ADB
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
TICK_US = 10_000


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


def diff(before, after):
    rows = []
    for tid, now in after.items():
        then = before.get(tid, {"ticks": 0, "voluntary": 0, "involuntary": 0})
        rows.append({"tid": tid, "name": now["name"], "cpuUs": (now["ticks"] - then["ticks"]) * TICK_US, "wakes": now["voluntary"] - then["voluntary"], "new": tid not in before})
    rows.sort(key=lambda r: (-r["cpuUs"], -r["wakes"]))
    return rows


def summary(rows, pid, seconds):
    hopi = [r for r in rows if r["name"].startswith("hopi")]
    main = next((r for r in rows if str(r["tid"]) == pid), None)
    return {"seconds": seconds, "processCpuUs": sum(r["cpuUs"] for r in rows), "processCpuPct": round(sum(r["cpuUs"] for r in rows) / (seconds * 1e6) * 100, 3),
            "processWakesPerSec": round(sum(r["wakes"] for r in rows) / seconds, 2),
            "main": main, "hopiThreads": hopi, "hopiCpuUs": sum(r["cpuUs"] for r in hopi), "hopiWakes": sum(r["wakes"] for r in hopi),
            "hopiWakesPerSec": round(sum(r["wakes"] for r in hopi) / seconds, 3), "busiest": rows[:6]}


def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))], 2)


def framestats(package):
    text = adb("shell", "dumpsys", "gfxinfo", package, "framestats")
    frames, columns = [], None
    for line in text.splitlines():
        line = line.strip().rstrip(",")
        if line.startswith("Flags,"):
            columns = line.split(",")
            continue
        if columns and line and line[0].isdigit():
            values = line.split(",")
            if len(values) < len(columns):
                continue
            row = dict(zip(columns, values))
            try:
                if int(row["Flags"]) != 0:
                    continue
                vsync, done = int(row["IntendedVsync"]), int(row["FrameCompleted"])
                ui_start, ui_end = int(row["HandleInputStart"]), int(row["SyncQueued"])
            except (KeyError, ValueError):
                continue
            if vsync <= 0 or not vsync < done < vsync + 10_000_000_000:
                continue
            frames.append({"totalMs": (done - vsync) / 1e6, "uiMs": (ui_end - ui_start) / 1e6})
        elif line.startswith("---PROFILEDATA---"):
            columns = None
    return frames


def screen_size():
    out = adb("shell", "wm", "size")
    w, h = out.strip().split(":")[-1].strip().split("x")
    return int(w), int(h)


def scroll(package, pid, swipes):
    w, h = screen_size()
    adb("shell", "dumpsys", "gfxinfo", package, "reset")
    before = idlemod.threads(package, pid)
    start = time.time()
    for i in range(swipes):
        y1, y2 = (int(h * 0.75), int(h * 0.30)) if i % 2 == 0 else (int(h * 0.30), int(h * 0.75))
        adb("shell", "input", "swipe", str(w // 2), str(y1), str(w // 2), str(y2), "150")
        time.sleep(0.6)
    seconds = time.time() - start
    after = idlemod.threads(package, pid)
    frames = framestats(package)
    total = [f["totalMs"] for f in frames]
    ui = [f["uiMs"] for f in frames]
    s = summary(diff(before, after), pid, seconds)
    return {"frames": len(frames), "totalP50": pct(total, 50), "totalP90": pct(total, 90), "totalP99": pct(total, 99),
            "uiP50": pct(ui, 50), "uiP90": pct(ui, 90), "uiP99": pct(ui, 99),
            "jankyPct": round(sum(1 for t in total if t > 33.4) / len(total) * 100, 1) if total else None,
            "processCpuPct": s["processCpuPct"], "hopiCpuUs": s["hopiCpuUs"], "hopiWakes": s["hopiWakes"], "mainCpuUs": (s["main"] or {}).get("cpuUs"),
            "hopiThreads": s["hopiThreads"]}


def arm(spec, idle_s, settle, swipes):
    label, target = spec.split("=", 1)
    viewer = target.endswith("+viewer")
    target = target.removesuffix("+viewer")
    monitors, _, package = target.partition("@")
    package = package or idlemod.PACKAGE
    monitors = monitors.replace("/", ",")
    adb("shell", "am", "force-stop", package)
    adb("logcat", "-c")
    reader = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I"], stdout=subprocess.PIPE, text=True)
    adb("shell", "am", "start", "-W", "-n", f"{package}/dev.koga.hopi.sample.MainActivity", "--es", "perfBench", f"'mode=idle;monitors={monitors}'")
    ready = None
    for raw in reader.stdout:
        at = raw.find('{"perfBench":"idle"')
        if at >= 0:
            ready = json.loads(raw[at:])
            break
    reader.terminate()
    reader.wait()
    pid = adb("shell", "pidof", package).strip()
    viewers = []
    if viewer:
        for args in (["tail", "--json"], ["perf", "tail", "--json", "--samples", "--fps"]):
            viewers.append(subprocess.Popen([HOPI, *args, "--app", package, "--platform", "android"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    time.sleep(settle)
    before = idlemod.threads(package, pid)
    time.sleep(idle_s)
    after = idlemod.threads(package, pid)
    idle = summary(diff(before, after), pid, idle_s)
    scrolled = scroll(package, pid, swipes) if swipes else None
    alive = [v.poll() is None for v in viewers]
    for v in viewers:
        v.terminate()
        v.wait()
    adb("shell", "am", "force-stop", package)
    return {"arm": label, "monitors": monitors, "package": package, "viewer": viewer, "viewersAlive": alive, "running": ready and ready.get("running"),
            "build": ready and ready.get("build"), "loadavg": adb("shell", "cat", "/proc/loadavg").split()[:3], "idle": idle, "scroll": scrolled}


def main():
    argv = sys.argv[1:]
    get = lambda name, default: argv[argv.index(name) + 1] if name in argv else default
    arms = get("--arms", "off=none").split(",")
    blocks = int(get("--blocks", "1"))
    for block in range(blocks):
        for spec in arms:
            result = arm(spec, float(get("--idle", "60")), float(get("--settle", "10")), int(get("--swipes", "20")))
            result["block"] = block
            print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
