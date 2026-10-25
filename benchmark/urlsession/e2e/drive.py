#!/usr/bin/env python3
"""End-to-end URLSession capture check on the booted iOS simulator.

Launches the installed sample (dev.koga.hopi.sample) with one launch argument under
`simctl launch --console-pty`, keeps the app's stdout, and on a real build also records what the
CLI sees: `hopi tail --json` for the whole run, then `hopi list --json` and `hopi get <id> --json`
for every record once the app logged its `done` line. The dev server must be up.

With --oslog it also keeps Hopi's NDJSON log lines (os_log subsystem dev.koga.hopi) of this
process, read with `log show` afterwards (`log stream` attaches too late after a boot).

usage: drive.py <out dir> <label> <launch argument...> [--no-cli] [--oslog] [--wait S]
"""
import json, os, queue, re, signal, subprocess, sys, threading, time

BUNDLE = "dev.koga.hopi.sample"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
HOPI = os.path.join(ROOT, "hopi-cli", "build", "install", "hopi-cli", "bin", "hopi")
# --platform ios: the CLI never runs adb (which would start an adb server).
TARGET = ["--app", BUNDLE, "--platform", "ios"]


def cli(*args, **kw):
    return subprocess.run([HOPI, *args, *TARGET], capture_output=True, text=True, **kw)


def main():
    out, label, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
    use_cli = "--no-cli" not in rest
    use_oslog = "--oslog" in rest
    wait = float(rest[rest.index("--wait") + 1]) if "--wait" in rest else 180
    launch = [a for i, a in enumerate(rest) if a not in ("--no-cli", "--oslog", "--wait") and (i == 0 or rest[i - 1] != "--wait")]
    os.makedirs(out, exist_ok=True)
    app_log = open(os.path.join(out, f"{label}-app.log"), "w")
    subprocess.run(["xcrun", "simctl", "terminate", "booted", BUNDLE], capture_output=True)
    started = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - 2))
    proc = subprocess.Popen(["xcrun", "simctl", "launch", "--console-pty", "booted", BUNDLE, *launch],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()

    def pump():
        for raw in proc.stdout:
            app_log.write(raw)
            app_log.flush()
            lines.put(raw)
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()

    tail = None
    if use_cli:
        # The bridge comes up a moment after launch: retry until hello succeeds.
        deadline = time.time() + 30
        while time.time() < deadline:
            probe = cli("list", "--json", "--limit", "1")
            if probe.returncode == 0:
                break
            time.sleep(0.3)
        tail_out = open(os.path.join(out, f"{label}-tail.ndjson"), "w")
        tail_err = open(os.path.join(out, f"{label}-tail.err"), "w")
        tail = subprocess.Popen([HOPI, "tail", "--json", *TARGET], stdout=tail_out, stderr=tail_err)

    done, deadline = False, time.time() + wait
    while time.time() < deadline and not done:
        try:
            line = lines.get(timeout=0.5)
        except queue.Empty:
            continue
        if line is None:
            break
        if '"done"' in line and ('"verify"' in line or '"bench"' in line or '"idle"' in line or '"giant"' in line):
            done = True
        if "ThirdPartySDK: bytes(for:)" in line or "ThirdPartySDK: async call failed" in line:
            done = True
    if not done:
        print(f"{label}: no done line within {wait} s", file=sys.stderr)
    if use_cli:
        time.sleep(2)  # the last records end on the capture queue just after the app's line
        listed = cli("list", "--json", "--limit", "10000")
        open(os.path.join(out, f"{label}-list.ndjson"), "w").write(listed.stdout)
        details = []
        for row in listed.stdout.splitlines():
            record = json.loads(row)
            if record.get("type") != "transaction":
                continue
            got = cli("get", str(record["id"]), "--json")
            details.append(got.stdout.strip())
        open(os.path.join(out, f"{label}-get.ndjson"), "w").write("\n".join(details) + "\n")
        tail.send_signal(signal.SIGINT)
        tail.wait(timeout=10)
    subprocess.run(["xcrun", "simctl", "terminate", "booted", BUNDLE], capture_output=True)
    proc.wait(timeout=10)
    if use_oslog:
        # simctl launch prints "<bundle>: <pid>" once the process exists.
        pid = next(m.group(1) for m in (re.match(BUNDLE.replace(".", r"\.") + r": (\d+)\s*$", line)
                                         for line in open(os.path.join(out, f"{label}-app.log"))) if m)
        subprocess.run(["xcrun", "simctl", "spawn", "booted", "log", "show", "--start", started, "--style", "ndjson",
                        "--predicate", f'subsystem == "dev.koga.hopi" AND processID == {int(pid)}'],
                       stdout=open(os.path.join(out, f"{label}-oslog.ndjson"), "w"), stderr=subprocess.DEVNULL)
    print(f"{label}: done={done}")
    return 0 if done else 1


if __name__ == "__main__":
    sys.exit(main())
