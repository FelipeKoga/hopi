#!/usr/bin/env python3
"""Device checks of two fixes (docs/design/perf-monitors.md, "As built", stage 5), Android:

1. A leased hang monitor still sees a hang after an idle stretch: `hopi perf start hangs` (the
   CLI's default 600 s lease, the agent path), idle, then the lab's 600 ms click burn tapped
   through input dispatch. Also with hangs code-owned and only CPU leased (`hopi perf start cpu`):
   the lease timer is global, so it reached the hang watchdog's idle sleep too.
2. CPU started while memory keeps the sampler running reads the CPU since it started, not all
   the CPU since the sampler started: memory for IDLE s, then cpu; the first cpu points.

One JSON object on stdout with each check's verdict and its numbers.
usage: lease_check.py [--idle 20]
"""
import json, os, re, shlex, subprocess, sys, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
PACKAGE = "dev.koga.hopi.sample"


def run(*args, check=True):
    return subprocess.run(list(args), check=check, capture_output=True, text=True).stdout


def adb(*args, check=True):
    return run(ADB, *args, check=check)


def hopi(command, *args):
    """`hopi perf start --cpu`: the command words first, then the options."""
    words = command.split()
    return run(HOPI, *words, *args, "--platform", "android", "--app", PACKAGE, check=False)


def json_lines(text):
    out = []
    for line in text.splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def launch_lab():
    """Opens the lab with no step: it starts every monitor from code and reports done."""
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--es", "perfVerify", shlex.quote("none"))
    deadline = time.time() + 30
    while time.time() < deadline:
        if '"perfLab":"done"' in adb("logcat", "-d", "-s", "HopiSample:I", check=False):
            return True
        time.sleep(0.5)
    return False


def tap_click_burn():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/hopi-perf-ui.xml")
    xml = adb("shell", "cat", "/data/local/tmp/hopi-perf-ui.xml")
    node = next((n for n in re.findall(r"<node [^>]*>", xml) if 'text="Click burn (600 ms in onClick)"' in n), None)
    if node is None:
        return False
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def hangs():
    return [e for e in json_lines(hopi("hangs", "--json")) if e.get("kind") == "hang"]


def click_burn_case(label, start_args, stop_first):
    ready = launch_lab()
    if stop_first:
        hopi("perf stop")
    start = json_lines(hopi("perf start", *start_args, "--json"))
    before = {h["seq"] for h in hangs()}
    # Idle past one threshold and any doze: the watchdog is in its lease-bounded sleep.
    time.sleep(3)
    tapped = tap_click_burn()
    time.sleep(2.5)
    fresh = [h for h in hangs() if h["seq"] not in before]
    status = json_lines(hopi("perf status", "--json"))
    burn = next((h for h in fresh if "clickBurn" in json.dumps(h.get("stacks", []))), None)
    ok = ready and tapped and burn is not None and 580 <= burn.get("ms", 0) <= 660
    return {"case": label, "pass": ok, "tapped": tapped, "hangs": [{"seq": h["seq"], "ms": h.get("ms"), "state": h.get("state")} for h in fresh],
            "startReply": start[:1], "status": status[:1]}


def cpu_after_memory(idle):
    launch_lab()
    hopi("perf stop")
    hopi("perf start", "--memory", "--json")
    time.sleep(idle)
    started = time.time() * 1000
    hopi("perf start", "--cpu", "--json")
    time.sleep(4.5)
    lines = json_lines(hopi("perf snapshot", "--json"))
    series = next((l for l in lines if l.get("type") == "perf.series" and l.get("name") == "cpu"), None)
    # Points of the code-owned run before the stop stay in the ring: only those after the start.
    points = [dict(zip(series["columns"], row)) for row in series["points"]] if series else []
    first = [p for p in points if p["t"] > started][:4]
    values = [p["process"] for p in first]
    ok = bool(values) and all(v is not None and v < 100 for v in values)
    return {"case": "cpu started after memory ran %d s" % idle, "pass": ok, "startedAt": int(started), "firstPoints": first}


def main():
    idle = 20
    if "--idle" in sys.argv:
        idle = int(sys.argv[sys.argv.index("--idle") + 1])
    results = [
        click_burn_case("hangs leased by the CLI (default lease)", ["--hangs"], stop_first=True),
        click_burn_case("hangs code-owned, cpu leased by the CLI", ["--cpu"], stop_first=False),
        cpu_after_memory(idle),
    ]
    adb("shell", "am", "force-stop", PACKAGE)
    json.dump({"checks": results, "pass": all(r["pass"] for r in results)}, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
