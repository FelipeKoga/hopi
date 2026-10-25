#!/usr/bin/env python3
"""Idle CPU of the sample after a burst of captured requests: installs APK, launches it with
`--es capture all` (the fake SDK's requests, so Hopi's capture path, store and bridge have all run),
waits for the action's status line, then reads every thread's utime+stime (`/proc/<pid>/task/*/stat`,
through `run-as`, the debug build's own uid) at the start and the end of SECONDS idle seconds, with
nothing else running in the app. With --tail a `hopi tail --json` stays attached for the whole idle
window (its bridge threads then exist). Hopi's capture work runs on `Dispatchers.Default` (one at a
time) and the bridge on threads named `hopi-*`; both are reported. One JSON line on stdout.

usage: idle.py <label> <apk> <seconds> [--tail]
"""
import json, os, subprocess, sys, time

PACKAGE = "dev.koga.hopi.sample"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TICK_US = 10_000  # USER_HZ 100


def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True).stdout


def threads(pid):
    """tid -> (name, utime+stime ticks). `comm` sits in parentheses and may hold spaces."""
    out = adb("shell", "run-as", PACKAGE, "sh", "-c", f"'cat /proc/{pid}/task/*/stat'")
    result = {}
    for line in out.splitlines():
        if ")" not in line:
            continue
        head, rest = line.rsplit(")", 1)
        tid, name = head.split(" (", 1)
        fields = rest.split()
        result[int(tid)] = (name, int(fields[11]) + int(fields[12]))
    return result


def main():
    label, apk, seconds = sys.argv[1], sys.argv[2], float(sys.argv[3])
    use_tail = "--tail" in sys.argv[4:]
    adb("install", "-r", "-t", apk)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--es", "capture", "all")
    logcat = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I"], stdout=subprocess.PIPE, text=True)
    for line in logcat.stdout:
        if line.startswith("capture: started="):
            break
    logcat.terminate()
    logcat.wait()
    pid = adb("shell", "pidof", PACKAGE).strip()
    tail = None
    if use_tail:
        tail = subprocess.Popen([HOPI, "tail", "--json", "--app", PACKAGE, "--platform", "android"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # Let the launch's own work (first frame, JIT of the burst) drain before the window opens.
    time.sleep(10)
    before = threads(pid)
    time.sleep(seconds)
    after = threads(pid)
    if tail:
        tail.terminate()
        tail.wait()
    adb("shell", "am", "force-stop", PACKAGE)
    rows = []
    for tid, (name, ticks) in after.items():
        start = before.get(tid, (name, 0))[1]
        rows.append({"tid": tid, "name": name, "cpuUs": (ticks - start) * TICK_US, "new": tid not in before})
    rows.sort(key=lambda r: -r["cpuUs"])
    hopi = [r for r in rows if r["name"].startswith("hopi")]
    default = [r for r in rows if r["name"].startswith("DefaultDispatch")]
    print(json.dumps({"idle": label, "seconds": seconds, "tail": use_tail, "threads": len(after),
                      "processCpuUs": sum(r["cpuUs"] for r in rows),
                      "hopiThreads": hopi, "hopiCpuUs": sum(r["cpuUs"] for r in hopi),
                      "defaultDispatcherCpuUs": sum(r["cpuUs"] for r in default),
                      "busiest": rows[:8]}))


if __name__ == "__main__":
    main()
