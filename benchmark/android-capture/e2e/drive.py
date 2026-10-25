#!/usr/bin/env python3
"""One run of the Android end-to-end capture check (see run.sh): launches the installed sample with
intent extras, keeps its `HopiSample` and `Hopi` logcat lines, and on a capture build also records
what the CLI sees: `hopi tail --json` for the whole run, then `hopi list --json` and
`hopi get <id> --json` for every record once the app logged its `{"verify":"done"}` line.

The dev server must be up, with `adb reverse tcp:8765 tcp:8765`. Subprocesses get argument arrays;
`adb shell` joins its arguments into one device command, so each extra is quoted for the device.

usage: drive.py <out dir> <label> [--no-cli] [--wait S] -- <am start extras...>
"""
import json, os, queue, shlex, signal, subprocess, sys, threading, time

PACKAGE = "dev.koga.hopi.sample"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TARGET = ["--app", PACKAGE, "--platform", "android"]
# After the app's done line the last records end on Hopi's capture dispatcher: the run ends once
# logcat has been quiet this long.
QUIET_S = 2.0


def cli(*args):
    return subprocess.run([HOPI, *args, *TARGET], capture_output=True, text=True)


def main():
    argv = sys.argv[1:]
    split = argv.index("--")
    out, label, options, extras = argv[0], argv[1], argv[2:split], argv[split + 1:]
    use_cli = "--no-cli" not in options
    wait = float(options[options.index("--wait") + 1]) if "--wait" in options else 300
    os.makedirs(out, exist_ok=True)
    subprocess.run([ADB, "shell", "am", "force-stop", PACKAGE], check=True)
    subprocess.run([ADB, "logcat", "-c"], check=True)
    logcat = subprocess.Popen([ADB, "logcat", "-v", "tag", "-s", "HopiSample:I", "Hopi:I", "AndroidRuntime:E"],
                              stdout=subprocess.PIPE, text=True)
    app_log = open(os.path.join(out, f"{label}-app.log"), "w")
    lines = queue.Queue()

    def pump():
        for raw in logcat.stdout:
            app_log.write(raw)
            app_log.flush()
            lines.put(raw)
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()
    subprocess.run([ADB, "shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", *(shlex.quote(e) for e in extras)],
                   check=True, capture_output=True)

    tail = None
    if use_cli:
        # The bridge comes up with the first activity: retry until hello succeeds.
        deadline = time.time() + 30
        while time.time() < deadline and cli("list", "--json", "--limit", "1").returncode != 0:
            time.sleep(0.3)
        tail_out = open(os.path.join(out, f"{label}-tail.ndjson"), "w")
        tail_err = open(os.path.join(out, f"{label}-tail.err"), "w")
        tail = subprocess.Popen([HOPI, "tail", "--json", *TARGET], stdout=tail_out, stderr=tail_err)

    done, deadline, quiet_from = False, time.time() + wait, None
    while time.time() < deadline:
        try:
            line = lines.get(timeout=0.25)
        except queue.Empty:
            if done and time.time() - quiet_from >= QUIET_S:
                break
            continue
        if line is None:
            break
        quiet_from = time.time()
        if '"verify":"done"' in line or "capture: started=" in line:
            done = True
        if "FATAL EXCEPTION" in line:
            break
    if not done:
        print(f"{label}: no done line within {wait} s", file=sys.stderr)
    if use_cli:
        listed = cli("list", "--json", "--limit", "10000")
        open(os.path.join(out, f"{label}-list.ndjson"), "w").write(listed.stdout)
        details = []
        for row in listed.stdout.splitlines():
            record = json.loads(row)
            if record.get("type", "transaction") != "transaction":
                continue
            details.append(cli("get", str(record["id"]), "--json").stdout.strip())
        open(os.path.join(out, f"{label}-get.ndjson"), "w").write("\n".join(details) + "\n")
        tail.send_signal(signal.SIGINT)
        try:
            tail.wait(timeout=10)
        except subprocess.TimeoutExpired:
            tail.kill()
            tail.wait()
    subprocess.run([ADB, "shell", "am", "force-stop", PACKAGE], check=True)
    logcat.terminate()
    logcat.wait()
    print(f"{label}: done={done}")
    return 0 if done else 1


if __name__ == "__main__":
    sys.exit(main())
