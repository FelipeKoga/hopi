#!/usr/bin/env python3
"""Device check and A/B of automatic Android capture (docs/design/android-capture.md).

Needs one booted emulator or device (adb at the default SDK location or on PATH), the dev server
up on the host and logging to SERVER_LOG, `adb reverse tcp:8765 tcp:8765`, the CLI installed
(`./gradlew :hopi-cli:installDist`), and two debug APKs of the sample: CAPTURE (plain
`assembleDebug`, the dev.koga.hopi plugin on) and NOOP (`assembleDebug -Psample.hopiCapture=false`,
built as release is: noop runtime, noop twins, no instrumentation).

  run.py e2e OUT SERVER_LOG CAPTURE NOOP
      For each APK: launches the app with `--es capture all` (the fake SDK's nine requests and
      Kotlin's URL.readText()) and `--es giant mb=1;runs=1` (one Ktor request through the OkHttp
      engine), then matches what Hopi recorded (summary lines in logcat, and `hopi list --json`
      over the bridge) against the dev server's own log: with capture every tagged request exactly
      once with the expected source, the Ktor one "okhttp" and never "ktor"; with the noop build
      the same server requests and no record at all.
  run.py ab OUT CAPTURE NOOP [ROUNDS] [SPEC] [ARMS]
      Interleaved A/B (noop, capture, noop, capture, ...): each block installs one APK, starts the
      app with `--es captureBench SPEC` and keeps its `{"captureBench":...}` lines. ARMS, default
      `noop,capture`, is the order within a round; `noop,capture,noop2` adds a second noop block
      (the same NOOP APK) whose difference from the first is the noise band (abtable.py).

Subprocesses get argument arrays; nothing is sent through a shell. Waiting is event-driven: the
script reads logcat's stream until the line it needs arrives (or a timeout).
"""

import json
import os
import queue
import shlex
import subprocess
import sys
import threading
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PACKAGE = "dev.koga.hopi.sample"
ACTIVITY = PACKAGE + "/.MainActivity"
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
SDK_ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
ADB = SDK_ADB if os.path.exists(SDK_ADB) else "adb"

# The tagged requests of the capture action, by their `sdk=` tag, with the recorder expected.
EXPECTED = {
    "enqueue": "okhttp",
    "execute": "okhttp",
    "subclass": "okhttp",
    "derived": "okhttp",
    "urlconnection": "urlconnection",
    "urlconnection-post": "urlconnection",
    "urlconnection-404": "urlconnection",
    "openStream": "urlconnection",
    "methodref": "urlconnection",
    "readText": "urlconnection",
}


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


def install(apk):
    adb("install", "-r", "-t", apk)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("logcat", "-c")


def launch_and_wait(extras, until, count=1, timeout=120):
    """Starts the app with `extras` and returns logcat lines (HopiSample and Hopi tags) until `count` lines match `until`."""
    logcat = subprocess.Popen([ADB, "logcat", "-v", "raw", "-s", "HopiSample:I", "Hopi:I"], stdout=subprocess.PIPE, text=True)
    lines_in = queue.Queue()
    reader = threading.Thread(target=lambda: [lines_in.put(l) for l in logcat.stdout] + [lines_in.put(None)], daemon=True)
    reader.start()
    try:
        # adb shell joins its arguments into one device shell command: quote each (specs contain `;`).
        adb("shell", "am", "start", "-W", "-n", ACTIVITY, *(shlex.quote(e) for e in extras))
        lines, matched = [], 0
        deadline = time.monotonic() + timeout
        while matched < count:
            left = deadline - time.monotonic()
            if left <= 0:
                raise TimeoutError(f"waiting for {until!r}: {lines[-5:]}")
            try:
                line = lines_in.get(timeout=left)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("logcat ended")
            lines.append(line.rstrip("\n"))
            if until in line:
                matched += 1
        return lines
    finally:
        logcat.terminate()
        logcat.wait()


def records(lines):
    out = []
    for line in lines:
        if line.startswith("{\"hopi\":1") and "\"id\"" in line:
            out.append(json.loads(line))
    return out


def server_lines(path, start):
    with open(path) as f:
        return [json.loads(line) for line in f.readlines()[start:] if line.startswith("{")]


def e2e(out, server_log, capture_apk, noop_apk):
    os.makedirs(out, exist_ok=True)
    report = {}
    for label, apk in (("capture", capture_apk), ("noop", noop_apk)):
        install(apk)
        with open(server_log) as f:
            start = len(f.readlines())
        lines = launch_and_wait(["--es", "capture", "all"], "capture: started=")
        # The same process (a second activity), so its store and bridge hold both runs' records.
        lines += launch_and_wait(["--activity-clear-task", "--es", "giant", "mb=1;runs=1"], "\"medianTotalMs\"")
        # The last summary line can follow the action's own log line by a few ms: one more read of
        # the buffer (not a wait) catches it.
        tail = adb("logcat", "-d", "-v", "raw", "-s", "Hopi:I").splitlines()
        cli = []
        if label == "capture":
            listed = subprocess.run([HOPI, "list", "--app", PACKAGE, "--json", "--limit", "100"], capture_output=True, text=True)
            cli = [json.loads(l) for l in listed.stdout.splitlines() if l.startswith("{")]
            # The full detail of each tagged record over the bridge, compared with the server below.
            for entry in cli:
                if tag(entry.get("url", "")):
                    got = subprocess.run([HOPI, "get", str(entry["id"]), "--app", PACKAGE, "--json"], capture_output=True, text=True)
                    entry["detail"] = json.loads(got.stdout) if got.stdout.startswith("{") else {"cliError": got.stderr.strip()}
        adb("shell", "am", "force-stop", PACKAGE)
        server = server_lines(server_log, start)
        with open(os.path.join(out, f"{label}-logcat.txt"), "w") as f:
            f.write("\n".join(lines + ["--- dump ---"] + tail) + "\n")
        with open(os.path.join(out, f"{label}-server.ndjson"), "w") as f:
            f.write("".join(json.dumps(s) + "\n" for s in server))
        with open(os.path.join(out, f"{label}-cli.ndjson"), "w") as f:
            f.write("".join(json.dumps(c) + "\n" for c in cli))
        seen = {}
        for r in records(tail):
            seen.setdefault((r["id"], r["url"]), r)
        report[label] = {"server": server, "records": list(seen.values()), "cli": cli, "status": [l for l in lines if l.startswith("capture: ")]}
    problems = check(report)
    with open(os.path.join(out, "e2e.json"), "w") as f:
        json.dump({"problems": problems, "status": {k: v["status"] for k, v in report.items()},
                   "records": {k: len(v["records"]) for k, v in report.items()}}, f, indent=2)
    print("\n".join(problems) if problems else "e2e: all checks passed")
    return 1 if problems else 0


def tag(path):
    return path.split("sdk=", 1)[1].split("&", 1)[0] if "sdk=" in path else None


def check(report):
    problems = []
    cap, noop = report["capture"], report["noop"]
    server_tags = sorted(t for t in (tag(s["path"]) for s in cap["server"]) if t)
    if server_tags != sorted(EXPECTED):
        problems.append(f"capture build: the server saw tags {server_tags}, expected {sorted(EXPECTED)}")
    noop_tags = sorted(t for t in (tag(s["path"]) for s in noop["server"]) if t)
    if noop_tags != server_tags:
        problems.append(f"noop build sent {noop_tags}, the capture build {server_tags}")
    if len(noop["server"]) != len(cap["server"]):
        problems.append(f"the server saw {len(cap['server'])} requests from the capture build and {len(noop['server'])} from the noop build")
    for name, recorded in (("summary log", cap["records"]), ("hopi list", cap["cli"])):
        by_tag = {}
        for r in recorded:
            t = tag(r.get("url", ""))
            if t:
                by_tag.setdefault(t, []).append(r)
        for t, source in EXPECTED.items():
            got = by_tag.get(t, [])
            if len(got) != 1:
                problems.append(f"{name}: {len(got)} records for sdk={t}, expected exactly 1")
            elif got[0].get("source") != source:
                problems.append(f"{name}: sdk={t} recorded by {got[0].get('source')}, expected {source}")
        giant = [r for r in recorded if "/giant" in r.get("url", "")]
        if len(giant) != 1 or giant[0].get("source") != "okhttp":
            problems.append(f"{name}: the Ktor request through the OkHttp engine gave {[g.get('source') for g in giant]}, expected one okhttp record")
        if any(r.get("source") == "ktor" for r in recorded):
            problems.append(f"{name}: a record with source ktor (the Ktor plugin did not defer to OkHttp capture)")
    # What the bridge holds of each request matches what the server received: the headers the
    # app (and the SDK's own interceptor) set, and the request body.
    server_by_tag = {tag(r["path"]): r for r in cap["server"] if tag(r["path"])}
    for entry in cap["cli"]:
        t = tag(entry.get("url", ""))
        detail = entry.get("detail")
        if not t or detail is None:
            continue
        sent = server_by_tag.get(t)
        if sent is None or "cliError" in detail:
            problems.append(f"hopi get sdk={t}: {detail.get('cliError', 'no server record')}")
            continue
        request = detail.get("request") or {}
        recorded_headers = {name.lower() for name, _ in (request.get("headers") or [])}
        for name, _ in sent["headers"]:
            if name.lower().startswith("x-") and name.lower() not in recorded_headers:
                problems.append(f"hopi get sdk={t}: header {name} sent but not recorded ({sorted(recorded_headers)})")
        if sent["body"]:
            body = (request.get("body") or {}).get("text")
            if body != sent["body"]:
                problems.append(f"hopi get sdk={t}: request body {body!r}, the server received {sent['body']!r}")
    if noop["records"]:
        problems.append(f"noop build: {len(noop['records'])} Hopi records")
    if not any("started=true okHttpInstrumented=true" in s for s in cap["status"]):
        problems.append(f"capture build status: {cap['status']}")
    if not any("started=false okHttpInstrumented=false" in s for s in noop["status"]):
        problems.append(f"noop build status: {noop['status']}")
    return problems


def ab(out, capture_apk, noop_apk, rounds=3, spec="kinds=okhttp,urlconnection;path=/json;n=300;warmup=50", arms="noop,capture"):
    os.makedirs(out, exist_ok=True)
    results = []
    apks = {"noop": noop_apk, "noop2": noop_apk, "capture": capture_apk}
    for r in range(rounds):
        for label, apk in ((arm, apks[arm]) for arm in arms.split(",")):
            install(apk)
            lines = launch_and_wait(["--es", "captureBench", spec], "\"captureBench\"", count=len(spec.split("kinds=")[1].split(";")[0].split(",")), timeout=600)
            adb("shell", "am", "force-stop", PACKAGE)
            for line in lines:
                if line.startswith("{\"captureBench\""):
                    entry = json.loads(line)
                    entry.update(round=r, arm=label)
                    results.append(entry)
                    print(json.dumps(entry))
    with open(os.path.join(out, "ab.ndjson"), "a") as f:
        f.write("".join(json.dumps(e) + "\n" for e in results))
    return 0


if __name__ == "__main__":
    command, args = sys.argv[1], sys.argv[2:]
    if command == "e2e":
        sys.exit(e2e(*args))
    if command == "ab":
        out, capture_apk, noop_apk = args[:3]
        rounds = int(args[3]) if len(args) > 3 else 3
        sys.exit(ab(out, capture_apk, noop_apk, rounds, *args[4:6]))
    sys.exit(f"unknown command {command}")
