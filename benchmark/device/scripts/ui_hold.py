#!/usr/bin/env python3
"""Tells a blocked main thread from an idle one while the sample is driven on the emulator.

Launches the sample with a giant spec plus `hold=<seconds>;window=1`, so its frame ticker draws on
every vsync and logs one line per second with the longest gap between two frames. After the runs,
replays taps (by coordinates from a dry run of ui.py, which records each tap's `tap` point: no
uiautomator dump here, since its accessibility traversal runs on the app's main thread too),
swipes and back presses with fixed waits, and attributes each 1 s window to the step that was
running when it ended. A gap in a window is then the main thread stalled, not merely idle.

usage: ui_hold.py <label> <spec> <hold s> <steps> [--cap BYTES] [--store BYTES] [--build]
steps: `name@x,y@seconds;name@swipe@seconds;name@back@seconds;...`
--cap and --store are capture's caps, passed as the Ktor plugin's `--el` extras, as giant.py does; on
Android OkHttp capture and the store take the installed APK's (`-Psample.maxBodyBytes` /
`-Psample.maxTotalBytes` at build time). --build first builds and installs the working tree's debug
sample with them, replacing the installed app.
Output: one line per step in $EVIDENCE/android-ui-hold.jsonl, every window in android-ui-hold.ndjson.
"""
import json, os, queue, re, subprocess, sys, threading, time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "dev.koga.hopi.sample"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
EVIDENCE = os.environ.get("EVIDENCE", os.path.join(REPO, "build", "evidence", "giant-json"))


def adb(*a, check=True):
    return subprocess.run([ADB, *a], capture_output=True, text=True, check=check).stdout


def main():
    label, spec, hold, steps_text = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    args = sys.argv[5:]
    steps = []
    for part in steps_text.split(";"):
        if part.strip():
            name, action, seconds = part.split("@")
            steps.append((name, action, float(seconds)))
    spec = "%s;hold=%d;window=1;delay=1" % (spec, hold)
    cap = args[args.index("--cap") + 1] if "--cap" in args else None
    store = args[args.index("--store") + 1] if "--store" in args else None
    if "--build" in args:
        command = [os.path.join(REPO, "gradlew"), ":sample:androidApp:installDebug", "--console=plain", "-q"]
        if cap:
            command.append("-Psample.maxBodyBytes=" + cap)
        if store:
            command.append("-Psample.maxTotalBytes=" + store)
        subprocess.run(command, cwd=REPO, check=True)
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    start = ["shell", "am", "start", "-W", "-n", PKG + "/.MainActivity", "--es", "giant", "'" + spec + "'"]
    if cap:
        start += ["--el", "maxBodyBytes", cap]
    if store:
        start += ["--el", "maxTotalBytes", store]
    adb(*start)
    logcat = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I", "AndroidRuntime:E", "ActivityManager:E"],
                              stdout=subprocess.PIPE, text=True)
    lines = queue.Queue()

    def pump():
        for raw in logcat.stdout:
            lines.put((time.time(), raw.strip()))
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()

    windows, problems, timeline = [], [], []
    deadline = time.time() + 300 + hold
    # The runs first: the hold starts after the last run's summary line.
    while time.time() < deadline:
        item = lines.get(timeout=300)
        if item is None:
            break
        _, line = item
        if '"runs":' in line:
            break
        if re.search(r"ANR in|OutOfMemory|FATAL EXCEPTION", line):
            problems.append(line)
    time.sleep(1.5)
    for name, action, seconds in steps:
        timeline.append((time.time(), name))
        if action == "swipe":
            for i in range(4):
                y0, y1 = (1900, 700) if i < 2 else (700, 1900)
                adb("shell", "input", "swipe", "540", str(y0), "540", str(y1), "150")
        elif action == "back":
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
        else:
            x, y = action.split(",")
            adb("shell", "input", "tap", x, y)
        time.sleep(seconds)
    timeline.append((time.time(), "(after the last step)"))
    while time.time() < deadline:
        try:
            item = lines.get(timeout=5)
        except queue.Empty:
            continue
        if item is None:
            break
        at, line = item
        if line.startswith('{"giant"'):
            record = json.loads(line)
            if record.get("done"):
                break
            if "hold" in record:
                record["receivedAt"] = at
                windows.append(record)
        elif re.search(r"ANR in|OutOfMemory|FATAL EXCEPTION", line):
            problems.append(line)
    logcat.terminate()

    def step_of(at):
        current = "(before the first step)"
        for started, name in timeline:
            if started <= at - 0.5:
                current = name
        return current

    os.makedirs(EVIDENCE, exist_ok=True)
    with open(os.path.join(EVIDENCE, "android-ui-hold.ndjson"), "a") as f:
        for w in windows:
            w["step"] = step_of(w["receivedAt"])
            w["label"] = label
            f.write(json.dumps(w) + "\n")
    pid = adb("shell", "pidof", PKG, check=False).strip()
    with open(os.path.join(EVIDENCE, "android-ui-hold.jsonl"), "a") as f:
        for _, name in timeline:
            mine = [w for w in windows if w["step"] == name]
            if not mine:
                continue
            row = {"label": "%s %s" % (label, name), "windows": len(mine),
                   "gapMaxMs": max(w.get("gapMaxMs", 0) for w in mine),
                   "gapsOver100Ms": sum(w.get("gapsOver100Ms", 0) for w in mine),
                   "gapsOver33Ms": sum(w.get("gapsOver33Ms", 0) for w in mine),
                   "frames": sum(w.get("frames", 0) for w in mine), "alive": bool(pid), "problems": problems}
            f.write(json.dumps(row) + "\n")
            print(json.dumps(row), flush=True)


main()
