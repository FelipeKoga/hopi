#!/usr/bin/env python3
"""Drives the inspector on the emulator and times each step from gfxinfo framestats.

Each step resets gfxinfo, taps a node found by text or content description in a uiautomator dump
(taken before the reset, so it costs the step nothing), waits until no frame has been drawn for
`--quiet` seconds (at most `--max`), and reports: tap to the last frame (the screen settled, which
includes content that arrives later from off-main formatting), the heaviest frame and its UI-thread
time (Vsync to SyncQueued), frames over 16.7 and 100 ms. Also polls the process for an ANR or death.

framestats rows with nonzero Flags (frames HWUI marks as not representative) or an unset or
inconsistent timestamp are dropped and counted (`droppedRows`). `longestNoFrameMs` is the longest
time between one frame's end and the next frame's intended vsync, clamped at 0 (frames that
overlap, the next scheduled before the previous ended, count in `overlappingFrames`). It cannot
tell an idle main thread from a blocked one: Compose draws only when something changed. For that,
run the sample's frame ticker (`ui_hold.py`). The raw framestats of every step are saved under
<evidence>/framestats/, so the rows can be recomputed.

Output goes to $EVIDENCE (default <repo>/build/evidence/giant-json).

usage:
  ui.py tap <label> <text-or-desc> [--quiet S] [--max S]
  ui.py scroll <label> <batches>              swipes up on the body, frames.py-style summary
  ui.py type <label> <text>                   types into the focused field, one key per char
  ui.py dump                                  prints the nodes with text or a description
  ui.py back <label>                          presses back (closes a share sheet), timed like tap
"""
import json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "dev.koga.hopi.sample"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
EVIDENCE = os.environ.get("EVIDENCE", os.path.join(REPO, "build", "evidence", "giant-json"))
OUT = os.path.join(EVIDENCE, "android-ui.jsonl")


def adb(*a, check=True):
    return subprocess.run([ADB, *a], capture_output=True, text=True, check=check).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x0, y0, x1, y1 = map(int, b)
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "x": (x0 + x1) // 2, "y": (y0 + y1) // 2,
                        "bounds": (x0, y0, x1, y1)})
    return out


def find(label):
    for n in nodes():
        if n["text"] == label or n["desc"] == label:
            return n
    for n in nodes():
        if label in n["text"] or label in n["desc"]:
            return n
    raise SystemExit("not found: " + label)


TIMES = ("IntendedVsync", "Vsync", "SyncQueued", "FrameCompleted")


def rows(dump, dropped=None):
    out, header, in_data = [], None, False
    for line in dump.splitlines():
        if line.startswith("---PROFILEDATA---"):
            in_data = not in_data
            header = None
            continue
        if not in_data:
            continue
        cols = line.strip().rstrip(",").split(",")
        if header is None:
            header = cols
            continue
        try:
            r = {k: int(v) for k, v in zip(header, cols)}
        except ValueError:
            continue
        valid = (r.get("Flags", 0) == 0 and all(r.get(k, 0) > 0 for k in TIMES)
                 and r["Vsync"] <= r["SyncQueued"] and r["IntendedVsync"] < r["FrameCompleted"]
                 and r["FrameCompleted"] - r["IntendedVsync"] < 10**10)
        if valid:
            out.append(r)
        elif dropped is not None:
            dropped.append(r)
    return out


def ui_ms(r):
    return (r["SyncQueued"] - r["Vsync"]) / 1e6


def total_ms(r):
    return (r["FrameCompleted"] - r["IntendedVsync"]) / 1e6


def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return None
    k = (len(xs) - 1) * p / 100
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return round(xs[lo] + (xs[hi] - xs[lo]) * (k - lo), 1)


def wait_quiet(quiet, longest, label=None, dropped=None):
    """Waits until gfxinfo's frame count stops growing for `quiet` s; returns the framestats rows."""
    started = time.time()
    seen, last_change = -1, time.time()
    while time.time() - started < longest:
        time.sleep(0.25)
        m = re.search(r"Total frames rendered: (\d+)", adb("shell", "dumpsys", "gfxinfo", PKG))
        count = int(m.group(1)) if m else 0
        if count != seen:
            seen, last_change = count, time.time()
        elif time.time() - last_change >= quiet:
            break
    dump = adb("shell", "dumpsys", "gfxinfo", PKG, "framestats")
    if label:
        os.makedirs(os.path.join(EVIDENCE, "framestats"), exist_ok=True)
        with open(os.path.join(EVIDENCE, "framestats", re.sub(r"[^A-Za-z0-9._-]+", "_", label) + ".txt"), "a") as f:
            f.write(dump)
    return rows(dump, dropped)


def gaps(fs):
    raw = [(b["IntendedVsync"] - a["FrameCompleted"]) / 1e6 for a, b in zip(fs, fs[1:])]
    return round(max([max(0.0, g) for g in raw] or [0]), 1), sum(g < 0 for g in raw)


def problems():
    pid = adb("shell", "pidof", PKG, check=False).strip()
    log = adb("logcat", "-d", "-s", "ActivityManager:E", "AndroidRuntime:E", check=False)
    found = [l for l in log.splitlines() if re.search("ANR in|FATAL|OutOfMemory", l)]
    return pid, found


def record(entry):
    with open(OUT, "a") as f:
        f.write(json.dumps(entry) + "\n")
    print(json.dumps(entry), flush=True)


def tap(label, target, quiet, longest):
    node = find(target)
    adb("logcat", "-c")
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    adb("shell", "input", "tap", str(node["x"]), str(node["y"]))
    dropped = []
    fs = wait_quiet(quiet, longest, label, dropped)
    pid, found = problems()
    if not fs:
        record({"label": label, "frames": 0, "droppedRows": len(dropped), "alive": bool(pid), "problems": found})
        return
    start = fs[0]["IntendedVsync"]
    heaviest = max(fs, key=ui_ms)
    no_frame, overlapping = gaps(fs)
    record({
        "label": label, "frames": len(fs),
        "tapToSettledMs": round((fs[-1]["FrameCompleted"] - start) / 1e6, 1),
        "heaviestFrameMs": round(total_ms(heaviest), 1),
        "heaviestFrameUiThreadMs": round(ui_ms(heaviest), 1),
        "uiThreadP95Ms": pct([ui_ms(r) for r in fs], 95),
        "framesOver16_7": sum(total_ms(r) > 16.67 for r in fs),
        "framesOver100": sum(total_ms(r) > 100 for r in fs),
        "longestNoFrameMs": no_frame, "overlappingFrames": overlapping, "droppedRows": len(dropped),
        "tap": [node["x"], node["y"]],
        "alive": bool(pid), "problems": found,
    })


def scroll(label, batches):
    allf, dropped = [], []
    for batch in range(batches):
        adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
        y0, y1 = (1900, 700) if batch % 4 < 2 else (700, 1900)
        adb("shell", "input", "swipe", "540", str(y0), "540", str(y1), "150")
        allf.extend(wait_quiet(0.8, 6, label, dropped))
    pid, found = problems()
    tot = [total_ms(r) for r in allf]
    record({
        "label": label, "frames": len(allf),
        "jankyPct": round(100 * sum(t > 16.67 for t in tot) / max(len(tot), 1), 1),
        "p50": pct(tot, 50), "p90": pct(tot, 90), "p95": pct(tot, 95), "max": round(max(tot or [0]), 1),
        "uiThreadP50": pct([ui_ms(r) for r in allf], 50), "uiThreadP95": pct([ui_ms(r) for r in allf], 95),
        "framesOver100": sum(t > 100 for t in tot), "droppedRows": len(dropped), "alive": bool(pid), "problems": found,
    })


def back(label, quiet, longest):
    adb("logcat", "-c")
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    dropped = []
    fs = wait_quiet(quiet, longest, label, dropped)
    pid, found = problems()
    tot = [total_ms(r) for r in fs]
    record({"label": label, "frames": len(fs), "max": round(max(tot or [0]), 1),
            "uiThreadP95Ms": pct([ui_ms(r) for r in fs], 95), "droppedRows": len(dropped), "alive": bool(pid), "problems": found})


def type_text(label, text):
    adb("logcat", "-c")
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    started = time.time()
    for c in text:
        adb("shell", "input", "text", c)
    typed = time.time() - started
    dropped = []
    fs = wait_quiet(0.8, 10, label, dropped)
    pid, found = problems()
    tot = [total_ms(r) for r in fs]
    record({"label": label, "chars": len(text), "typingS": round(typed, 2), "frames": len(fs),
            "p95": pct(tot, 95), "max": round(max(tot or [0]), 1), "uiThreadP95": pct([ui_ms(r) for r in fs], 95),
            "framesOver100": sum(t > 100 for t in tot), "droppedRows": len(dropped), "alive": bool(pid), "problems": found})


def main():
    cmd = sys.argv[1]
    args = sys.argv[2:]
    opt = lambda name, default: float(args[args.index(name) + 1]) if name in args else default
    if cmd == "tap":
        tap(args[0], args[1], opt("--quiet", 1.5), opt("--max", 30))
    elif cmd == "scroll":
        scroll(args[0], int(args[1]))
    elif cmd == "type":
        type_text(args[0], args[1])
    elif cmd == "back":
        back(args[0], opt("--quiet", 1.5), opt("--max", 20))
    elif cmd == "dump":
        for n in nodes():
            if n["text"] or n["desc"]:
                print(n["text"][:60], "|", n["desc"][:60], n["x"], n["y"])


main()
