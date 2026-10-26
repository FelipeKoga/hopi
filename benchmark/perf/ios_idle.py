#!/usr/bin/env python3
"""Idle cost of the perf monitors on the iOS simulator, measured from outside the app (the
simulator app is a host process). Per arm: `simctl launch --console-pty ... -perfBench
'mode=idle;monitors=<set>'`, wait for its ready line and SETTLE s, then over IDLE s read at both ends
  - CPU: `ps -o cputime` (every thread);
  - wakes: `top -l 1 -pid P -stats pid,idlew` (IDLEW, the process's cumulative idle wake-ups);
  - threads: `ps -M` thread count.
Optionally with a viewer attached (`+viewer`: `hopi tail` + `hopi perf tail --samples`, as the desktop; `+viewerfps` adds `--fps`). Per-thread names
are not readable without a debugger, so Hopi's own share is the difference between arms.
One JSON line per arm.

usage: ios_idle.py --arms off=none,all=all[+viewer] [--idle 60] [--settle 10] [--blocks 1]
"""
import json, os, re, subprocess, sys, time

PACKAGE = "dev.koga.hopi.sample"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")


def run(*args, check=False):
    return subprocess.run(list(args), check=check, capture_output=True, text=True).stdout


def cpu_seconds(pid):
    text = run("ps", "-o", "cputime=", "-p", pid).strip()
    seconds = 0.0
    for part in text.split(":"):
        seconds = seconds * 60 + float(part)
    return seconds


def idle_wakes(pid):
    """The process's idle wake-ups and context switches so far (top's IDLEW and CSW, cumulative in its default mode)."""
    rows = [l.split() for l in run("top", "-l", "1", "-pid", pid, "-stats", "pid,idlew,csw").splitlines() if l.strip().startswith(pid + " ")]
    return int(re.sub(r"\D", "", rows[-1][1])), int(re.sub(r"\D", "", rows[-1][2]))


def arm(spec, idle_s, settle):
    label, target = spec.split("=", 1)
    # +viewer: what the desktop viewer opens (`hopi tail` and a perf tail with samples, no fps);
    # +viewerfps: the perf tail also asks for fps, which on iOS runs the tier-2 display-link cadence.
    fps = target.endswith("+viewerfps")
    viewer = fps or target.endswith("+viewer")
    monitors = target.removesuffix("+viewerfps").removesuffix("+viewer").replace("/", ",")
    run("xcrun", "simctl", "terminate", "booted", PACKAGE)
    reader = subprocess.Popen(["xcrun", "simctl", "launch", "--console-pty", "--terminate-running-process", "booted", PACKAGE, "-perfBench", f"mode=idle;monitors={monitors}"],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    ready = None
    deadline = time.time() + 60
    for raw in reader.stdout:
        at = raw.find('{"perfBench":"idle"')
        if at >= 0:
            ready = json.loads(raw[at:].strip())
            break
        if time.time() > deadline:
            break
    pid = run("pgrep", "-n", "-f", "HopiSample.app/HopiSample").strip()
    viewers = []
    if viewer:
        for args in (["tail", "--json"], ["perf", "tail", "--json", "--samples"] + (["--fps"] if fps else [])):
            viewers.append(subprocess.Popen([HOPI, *args, "--app", PACKAGE, "--platform", "ios"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    time.sleep(settle)
    w0, s0 = idle_wakes(pid)
    c0 = cpu_seconds(pid)
    time.sleep(idle_s)
    c1 = cpu_seconds(pid)
    w1, s1 = idle_wakes(pid)
    threads = len(run("ps", "-M", "-p", pid).splitlines()) - 1
    alive = [v.poll() is None for v in viewers]
    for v in viewers:
        v.terminate()
        v.wait()
    reader.terminate()
    run("xcrun", "simctl", "terminate", "booted", PACKAGE)
    return {"arm": label, "monitors": monitors, "viewer": viewer, "viewersAlive": alive, "build": ready and ready.get("build"), "running": ready and ready.get("running"),
            "seconds": idle_s, "cpuMs": round((c1 - c0) * 1000), "cpuPct": round((c1 - c0) / idle_s * 100, 3),
            "fps": fps, "contextSwitches": s1 - s0, "contextSwitchesPerSec": round((s1 - s0) / idle_s, 2), "idleWakes": w1 - w0, "idleWakesPerSec": round((w1 - w0) / idle_s, 2),
            "threads": threads, "hostLoad": run("sysctl", "-n", "vm.loadavg").strip()}


def main():
    argv = sys.argv[1:]
    get = lambda name, default: argv[argv.index(name) + 1] if name in argv else default
    for block in range(int(get("--blocks", "1"))):
        for spec in get("--arms", "off=none").split(","):
            r = arm(spec, float(get("--idle", "60")), float(get("--settle", "10")))
            r["block"] = block
            print(json.dumps(r), flush=True)


if __name__ == "__main__":
    main()
