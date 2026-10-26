#!/usr/bin/env python3
"""Device verification of the performance monitors (docs/design/perf-monitors.md, "Device
verification plan"). Launches the sample's perf lab (Android `--es perfVerify all`, iOS
`-verifyPerf`), which starts every monitor, injects known jank, hangs, a dialog, CPU burn and
allocations and logs one `{"perfLab":…}` line per injection with its wall-clock window. While it
runs, this script takes its own references (Android: `dumpsys gfxinfo framestats` after each jank
step, `/proc/<pid>/stat` around the CPU step, `dumpsys meminfo` around the allocations, and
`am send-trim-memory` at the memory step); on Android it then taps the lab's click-burn button with
`adb shell input tap` (input dispatch, the input bracket's gate). Finally it reads Hopi's record over
the bridge (`hopi perf snapshot --json`, `hopi hangs --json`) and checks it against the injections
and references. Writes everything to <out>/ and prints one table; exit 1 when a check fails.

Android: one emulator or device, the sample's debug APK installed (`--install <apk>` installs it).
iOS: one booted simulator with the Debug app installed; the click burn is not driven there (no tap
from the command line) and the memory warning is the lab's own posted notification.

usage: verify.py <out dir> [--platform android|ios] [--install <apk>] [--steps all]
"""
import json, os, queue, re, shlex, subprocess, sys, threading, time

PACKAGE = "dev.koga.hopi.sample"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TICK_MS = 10  # USER_HZ 100
CLICK_LABEL = "Click burn (600 ms in onClick)"


def run(*args, check=True):
    return subprocess.run(list(args), check=check, capture_output=True, text=True).stdout


def adb(*args, check=True):
    return run(ADB, *args, check=check)


def cli(platform, *args):
    result = subprocess.run([HOPI, *args, "--app", PACKAGE, "--platform", platform], capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr


def parse_json_lines(text):
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


def lab_line(raw):
    """The `{"perfLab":…}` object in a logcat or console line, or None."""
    start = raw.find('{"perfLab"')
    if start < 0:
        return None
    try:
        return json.loads(raw[start:].strip())
    except json.JSONDecodeError:
        return None


# ---- Android references

def ios_cpu_seconds():
    """The simulator app's CPU time (`ps -o cputime`, every thread), or None."""
    pid = run("pgrep", "-n", "-f", "HopiSample.app/HopiSample", check=False).strip()
    if not pid:
        return None
    text = run("ps", "-o", "cputime=", "-p", pid, check=False).strip()
    if not text:
        return None
    seconds = 0.0
    for part in text.split(":"):
        seconds = seconds * 60 + float(part)
    return seconds


def proc_ticks(pid):
    fields = adb("shell", "cat", f"/proc/{pid}/stat").rsplit(")", 1)[1].split()
    return int(fields[11]) + int(fields[12])


def framestats():
    """(intended vsync ns, total ms) of the frames gfxinfo keeps (the last 120), for the app's windows."""
    text = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
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
            except (KeyError, ValueError):
                continue
            if vsync <= 0 or not vsync < done < vsync + 10_000_000_000:
                continue  # a row the framework had not completed
            frames.append((vsync, (done - vsync) / 1e6))
        elif line.startswith("---PROFILEDATA---") and columns and frames:
            columns = None
    return frames


def meminfo():
    """Java heap and native heap (KB) and TOTAL RSS from `dumpsys meminfo`'s App Summary."""
    text = adb("shell", "dumpsys", "meminfo", PACKAGE)
    out = {}
    for key, pattern in (("javaHeapKb", r"Java Heap:\s+(\d+)"), ("nativeHeapKb", r"Native Heap:\s+(\d+)"), ("totalRssKb", r"TOTAL RSS:\s+(\d+)")):
        match = re.search(pattern, text)
        if match:
            out[key] = int(match.group(1))
    return out


def tap_click_burn():
    """Taps the lab's click-burn button through input dispatch, found by its text in a uiautomator dump."""
    adb("shell", "uiautomator", "dump", "/data/local/tmp/hopi-perf-ui.xml")
    xml = adb("shell", "cat", "/data/local/tmp/hopi-perf-ui.xml")
    for node in re.finditer(r"<node [^>]*>", xml):
        text = node.group(0)
        if f'text="{CLICK_LABEL}"' in text:
            bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', text)
            if bounds:
                x1, y1, x2, y2 = map(int, bounds.groups())
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
    return False


def send_trim(refs, level):
    result = subprocess.run([ADB, "shell", "am", "send-trim-memory", PACKAGE, level], capture_output=True, text=True)
    said = (result.stdout + result.stderr).strip()
    if result.returncode == 0 and "Exception" not in said:
        refs.setdefault("trimSent", []).append(level)
    else:
        refs.setdefault("trimRefused", {})[level] = said.splitlines()[1][:200] if len(said.splitlines()) > 1 else said[:200]


def labs_steps(labs):
    return {l["perfLab"] for l in labs}


# ---- checks

class Checks:
    def __init__(self):
        self.rows = []

    def add(self, name, ok, seen, expected):
        self.rows.append({"check": name, "ok": bool(ok) if ok is not None else None, "seen": seen, "expected": expected})

    def table(self):
        width = max(len(r["check"]) for r in self.rows)
        lines = []
        for r in self.rows:
            mark = "PASS" if r["ok"] else ("n/a " if r["ok"] is None else "FAIL")
            lines.append(f"{mark}  {r['check']:<{width}}  seen: {r['seen']}  (expected {r['expected']})")
        return "\n".join(lines)


def check(platform, labs, refs, snapshot, hangs_full):
    checks = Checks()
    events = [e for e in snapshot if e.get("type") == "perf.event"]
    jank = [e for e in events if e.get("kind") == "jank"]
    stalls = [e for e in events if e.get("kind") == "stall"]
    hang_events = {e["seq"]: e for e in hangs_full if e.get("type") == "perf.event" and e.get("kind") == "hang"}
    for e in events:
        if e.get("kind") == "hang" and e["seq"] not in hang_events:
            hang_events[e["seq"]] = e
    ended = [e for e in hang_events.values() if e.get("state") == "ended"]
    series = {s["name"]: s for s in snapshot if s.get("type") == "perf.series"}
    summary = next((s for s in snapshot if s.get("type") == "perf.summary"), {})

    def in_window(at, lab, before=0, after=0):
        return lab["from"] - before <= at <= lab["to"] + after

    # Frames. Android: janky frames (FrameMetrics, > 2 deadlines) per step; iOS: stalls (units > 2 deadlines).
    for lab in [l for l in labs if l["perfLab"] == "jank"]:
        block = lab["blockMs"]
        # Jank events carry their second: the step's seconds (the steps are 1.5 s apart, never sharing one).
        lo, hi = lab["from"] // 1000 * 1000, lab["to"] // 1000 * 1000
        if platform == "android":
            hits = [e for e in jank if lo <= e["at"] <= hi]
            worst = max((e["ms"] for e in hits), default=None)
            ref = refs.get(f"framestats-{block}") or {}
            over = ref.get("over2Deadlines")
            # The block runs in Compose's frame callback, so it is the frame's animation stage.
            animation = [e["stagesMs"]["animation"] for e in hits if "animation" in e.get("stagesMs", {})]
            if block >= 50:
                # gfxinfo's own count over two deadlines is the reference: every frame here is late by its own work.
                checks.add(f"jank {block} ms x30: janky frames", len(hits) >= 28 and over is not None and abs(len(hits) - over) <= 3,
                           f"{len(hits)} janky, worst {worst} ms; gfxinfo {ref}", ">= 28 (design) and gfxinfo's count +-3")
                median_animation = sorted(animation)[len(animation) // 2] if animation else None
                checks.add(f"jank {block} ms x30: the block in the frame", median_animation is not None and block - 2 <= median_animation <= block + 10,
                           f"animation stage median {median_animation} ms, max {max(animation, default=None)} ms (frame up to {worst} ms, render included)",
                           f"{block} ms -2/+10")
            else:
                # Design: slow, not janky, at 60 Hz. gfxinfo measures from the intended vsync, so on an
                # emulator whose render takes 15 to 40 ms (SwiftShader) each frame also carries the wait for
                # the previous one and it counts all of them; Hopi measures each frame's own work.
                checks.add(f"jank {block} ms x30: slow, not janky", len(hits) <= 3, f"{len(hits)} janky (gfxinfo, from the intended vsync: {over})", "<= 3 of 30")
        else:
            # Stalls carry their start in milliseconds.
            hits = [e for e in stalls if lab["from"] - 100 <= e["at"] <= lab["to"] + 100]
            if block >= 50:
                checks.add(f"jank {block} ms x30: stalls", len(hits) >= 28, f"{len(hits)} stalls, worst {max((e['ms'] for e in hits), default=None)} ms", ">= 28")
            else:
                checks.add(f"jank {block} ms x30: no stall", len(hits) <= 2, f"{len(hits)} stalls", "<= 2")

    # Hangs: one each over the threshold, duration within 20 ms, the stack naming the lab's function.
    for lab in [l for l in labs if l["perfLab"] == "hang"]:
        name, ms, actual = lab["fn"], lab["ms"], lab["actualMs"]
        hits = [e for e in ended if in_window(e["at"], lab, before=150, after=150)]
        label = f"hang {name} {ms} ms"
        if ms < 250:
            checks.add(f"{label}: none", not hits, f"{len(hits)} hangs", "0 (under the 250 ms threshold)")
            continue
        checks.add(f"{label}: one hang", len(hits) == 1, f"{len(hits)} hangs" + (f", {hits[0]['ms']} ms" if hits else ""), "1")
        if hits:
            hang = hits[0]
            checks.add(f"{label}: duration", abs(hang["ms"] - actual) <= 20 + (30 if name == "clickBurn" else 0), f"{hang['ms']} ms", f"{actual} ms measured in the unit, +-20")
            frames = [f for stack in hang.get("stacks", []) for f in stack]
            needle = ("PerfLab." + name) if platform == "android" else ("PerfLab#" + name)
            named = any(needle in f for f in frames)
            checks.add(f"{label}: stack names {needle}", named, f"{len(hang.get('stacks', []))} stacks, captureUs {hang.get('captureUs')}", "a frame with it")
    if platform == "android" and not any(l["perfLab"] == "hang" and l["fn"] == "clickBurn" for l in labs):
        checks.add("hang clickBurn: tapped", False, "no clickBurn line", "the tap reached the button")

    # Dialog: frames go on being counted for the activity's window only; no jank from showing it.
    for lab in [l for l in labs if l["perfLab"] == "dialog"]:
        hits = [e for e in jank if lab["from"] // 1000 * 1000 + 1000 <= e["at"] <= lab["to"]]
        checks.add("dialog 5 s: no fake jank", len(hits) <= 1, f"{len(hits)} janky frames inside", "<= 1")

    # CPU: two threads at 100 % for 5 s read as about 200 % of one core.
    cpu = series.get("cpu")
    for lab in [l for l in labs if l["perfLab"] == "cpu"]:
        if not cpu:
            checks.add("cpu 2 threads x 5 s", False, "no cpu series", "a series")
            continue
        col = cpu["columns"].index("process") if "process" in cpu["columns"] else 1
        inside = [p[col] for p in cpu["points"] if lab["from"] + 1200 <= p[0] <= lab["from"] + lab["ms"] and p[col] is not None]
        mean = round(sum(inside) / len(inside), 1) if inside else None
        ref = refs.get("cpuProcStatPercent")
        checks.add("cpu 2 threads x 5 s: process %", mean is not None and 170 <= mean <= 230, f"{mean} % over {len(inside)} samples; /proc {ref} % (whole step)", "200 +-15 %, of one core")

    # Memory: the heap and resident memory grow by the 64 MB the lab holds.
    memory = series.get("memory")
    for lab in [l for l in labs if l["perfLab"] == "alloc"]:
        if not memory:
            checks.add(f"alloc {lab['kind']} 64 MB", False, "no memory series", "a series")
            continue
        cols = memory["columns"]
        def growth(column):
            if column not in cols:
                return None
            i = cols.index(column)
            before = [p[i] for p in memory["points"] if p[0] < lab["from"] and p[i] is not None]
            inside = [p[i] for p in memory["points"] if lab["from"] + 1200 <= p[0] <= lab["to"] and p[i] is not None]
            if not before or not inside:
                return None
            return round((max(inside) - before[-1]) / 1024, 1)  # the columns are KB
        mb = lab["mb"]
        resident = growth("residentKb")
        if lab["kind"] == "heap":
            heap = growth("javaHeapKb") if platform == "android" else None
            if platform == "android":
                checks.add("alloc heap 64 MB: Java heap", heap is not None and abs(heap - mb) <= mb * 0.05 + 2, f"+{heap} MB; meminfo {refs.get('meminfo-heap')}", "+64 MB +-5 %")
            checks.add("alloc heap 64 MB: resident", resident is not None and abs(resident - mb) <= mb * 0.10 + 2, f"+{resident} MB", "+64 MB +-10 %")
        else:
            native = growth("nativeHeapKb") if platform == "android" else None
            if platform == "android":
                checks.add("alloc native 64 MB: native heap", native is not None and abs(native - mb) <= mb * 0.10 + 2, f"+{native} MB; meminfo {refs.get('meminfo-native')}", "+64 MB +-10 %")
            checks.add("alloc native 64 MB: resident", resident is not None and abs(resident - mb) <= mb * 0.10 + 2, f"+{resident} MB", "+64 MB +-10 %")

    # Memory events: Android one per `am send-trim-memory` level; iOS one for the posted warning.
    mem_events = [e for e in events if e.get("kind") == "memory"]
    for lab in [l for l in labs if l["perfLab"] == "memory"]:
        sent = refs.get("trimSent", [])
        expected = len(sent) if platform == "android" else 1
        hits = [e for e in mem_events if e["at"] >= lab["at"] - 1000]
        checks.add("memory events", len(hits) >= expected, f"{len(hits)}: {[(e.get('event'), e.get('level')) for e in hits]}; sent {sent}", f">= {expected}")

    checks.add("summary: hangs counted", None, summary.get("hangs"), "for the record")
    checks.add("summary: frames", None, summary.get("frames"), "for the record")
    return checks


def main():
    argv = sys.argv[1:]
    out = argv[0]
    platform = argv[argv.index("--platform") + 1] if "--platform" in argv else "android"
    steps = argv[argv.index("--steps") + 1] if "--steps" in argv else "all"
    os.makedirs(out, exist_ok=True)
    refs, labs = {}, []
    lines = queue.Queue()
    log = open(os.path.join(out, f"{platform}-app.log"), "w")

    if platform == "android":
        if "--install" in argv:
            adb("install", "-r", "-t", argv[argv.index("--install") + 1])
        adb("shell", "am", "force-stop", PACKAGE)
        adb("logcat", "-c")
        reader = subprocess.Popen([ADB, "logcat", "-v", "tag", "-s", "HopiSample:I", "HopiPerf:I", "AndroidRuntime:E"], stdout=subprocess.PIPE, text=True)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", "--es", "perfVerify", shlex.quote(steps))
        pid = None
        for _ in range(50):
            pid = adb("shell", "pidof", PACKAGE, check=False).strip()
            if pid:
                break
            time.sleep(0.2)
    else:
        run("xcrun", "simctl", "terminate", "booted", PACKAGE, check=False)
        reader = subprocess.Popen(["xcrun", "simctl", "launch", "--console-pty", "--terminate-running-process", "booted", PACKAGE, "-verifyPerf", steps],
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        pid = None

    def pump():
        for raw in reader.stdout:
            log.write(raw)
            log.flush()
            lines.put(raw)
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()

    deadline, done = time.time() + 240, False
    cpu_start = None
    while time.time() < deadline and not done:
        try:
            raw = lines.get(timeout=0.5)
        except queue.Empty:
            continue
        if raw is None:
            break
        if "FATAL EXCEPTION" in raw:
            break
        lab = lab_line(raw)
        if not lab:
            continue
        labs.append(lab)
        kind = lab["perfLab"]
        if platform == "android" and pid:
            if kind == "jank":
                # The last 120 frames: the step's 30 slow ones are its most recent.
                frames = framestats()[-30:]
                refs[f"framestats-{lab['blockMs']}"] = {
                    "frames": len(frames),
                    "over2Deadlines": sum(1 for _, ms in frames if ms > 33.4),
                    "worstMs": round(max((ms for _, ms in frames), default=0), 1),
                }
            elif kind == "dialog":
                cpu_start = (time.time(), proc_ticks(pid))
                refs["meminfo-before"] = meminfo()
            elif kind == "cpu" and cpu_start:
                ticks = proc_ticks(pid) - cpu_start[1]
                refs["cpuProcStatPercent"] = round(ticks * TICK_MS / lab["ms"] * 100, 1)
                refs["meminfo-base"] = meminfo()
            elif kind == "alloc":
                info = meminfo()
                base = refs.get("meminfo-base", {})
                key = "javaHeapKb" if lab["kind"] == "heap" else "nativeHeapKb"
                refs[f"meminfo-{lab['kind']}"] = {"growthMb": round((info.get(key, 0) - base.get(key, 0)) / 1024, 1), "totalRssMb": round(info.get("totalRssKb", 0) / 1024, 1)}
            elif kind == "memory":
                refs["trimSent"] = []
                send_trim(refs, "RUNNING_CRITICAL")
        if platform == "ios":
            if kind == "dialog":
                cpu_start = (time.time(), ios_cpu_seconds())
            elif kind == "cpu" and cpu_start and cpu_start[1] is not None:
                now = ios_cpu_seconds()
                if now is not None:
                    refs["cpuProcStatPercent"] = round((now - cpu_start[1]) / (lab["ms"] / 1000) * 100, 1)
        if kind == "done":
            done = True

    if done and platform == "android" and "clickBurn" not in steps:
        if tap_click_burn():
            end = time.time() + 10
            while time.time() < end:
                try:
                    raw = lines.get(timeout=0.5)
                except queue.Empty:
                    continue
                lab = lab_line(raw or "")
                if lab and lab.get("fn") == "clickBurn":
                    labs.append(lab)
                    break
        time.sleep(1.5)
    if done and platform == "android" and "memory" in labs_steps(labs):
        # A background level reaches only a process in the background (API 34+ still sends this one):
        # Home, the level, then the snapshot at once, before the cached-app freezer stops the app.
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(1.0)
        send_trim(refs, "BACKGROUND")
        time.sleep(1.0)

    code, snap_out, snap_err = cli(platform, "perf", "snapshot", "--json")
    snapshot = parse_json_lines(snap_out)
    code2, hangs_out, _ = cli(platform, "hangs", "--json")
    hangs_full = parse_json_lines(hangs_out)
    with open(os.path.join(out, f"{platform}-snapshot.ndjson"), "w") as f:
        f.write(snap_out)
    with open(os.path.join(out, f"{platform}-hangs.ndjson"), "w") as f:
        f.write(hangs_out)
    reader.terminate()

    checks = check(platform, labs, refs, snapshot, hangs_full)
    result = {"platform": platform, "done": done, "snapshotExit": code, "snapshotError": snap_err.strip()[:300], "hangsExit": code2,
              "lab": labs, "references": refs, "checks": checks.rows}
    with open(os.path.join(out, f"{platform}-verify.json"), "w") as f:
        json.dump(result, f, indent=1)
    print(checks.table())
    if not done:
        print("the lab logged no done line", file=sys.stderr)
    failed = [r for r in checks.rows if r["ok"] is False]
    sys.exit(1 if failed or not done else 0)


if __name__ == "__main__":
    main()
