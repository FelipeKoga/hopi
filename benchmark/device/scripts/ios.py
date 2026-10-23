#!/usr/bin/env python3
"""iOS twin of giant.py: launches the sample on the booted simulator with `-giant <spec>` (and
`-maxBodyBytes N`) under `simctl launch --console-pty`, collects its `HopiSample:` JSON lines,
and reads the app's memory with the host's `footprint <pid>` during the spec's delay, after the
last run, and (with --poll) every second meanwhile.

usage: ios.py <label> <spec> <out.ndjson> [--cap BYTES] [--store BYTES] [--timeout S] [--poll]
"""
import json, queue, re, subprocess, sys, threading, time

BUNDLE = "dev.koga.hopi.sample"


def footprint(pid):
    out = subprocess.run(["footprint", str(pid)], capture_output=True, text=True).stdout
    m = re.search(r"Footprint:\s+([\d.]+)\s+(KB|MB|GB)", out)
    if not m:
        return None
    value = float(m.group(1))
    return round(value * {"KB": 1 / 1024, "MB": 1, "GB": 1024}[m.group(2)], 1)


def app_pid():
    out = subprocess.run(["pgrep", "-f", "HopiSample.app/HopiSample"], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def main():
    label, spec, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    args = sys.argv[4:]
    cap = args[args.index("--cap") + 1] if "--cap" in args else None
    store = args[args.index("--store") + 1] if "--store" in args else None
    limit = float(args[args.index("--timeout") + 1]) if "--timeout" in args else 900
    poll = "--poll" in args
    if "delay=" not in spec:
        spec += ";delay=6"
    cmd = ["xcrun", "simctl", "launch", "--console-pty", "--terminate-running-process", "booted", BUNDLE, "-giant", spec]
    if cap:
        cmd += ["-maxBodyBytes", cap]
    if store:
        cmd += ["-maxTotalBytes", store]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()

    def pump():
        for raw in proc.stdout:
            lines.put(raw)
        lines.put(None)
    threading.Thread(target=pump, daemon=True).start()
    records, before, after, peak, done, pid = [], None, None, 0, False, None
    deadline, next_poll = time.time() + limit, 0
    while time.time() < deadline and not done:
        if poll and pid and time.time() >= next_poll:
            f = footprint(pid)
            peak = max(peak, f or 0)
            next_poll = time.time() + 1
        try:
            line = lines.get(timeout=0.5)
        except queue.Empty:
            continue
        if line is None:
            break
        i = line.find('{"giant"')
        if i < 0:
            if re.search(r"(?i)crash|terminat|exception|out of memory", line):
                print("PROBLEM:", line.strip(), flush=True)
            continue
        rec = json.loads(line[i:].strip())
        rec["receivedAt"] = round(time.time(), 2)  # host time, to line hold windows up with a driver's steps
        if rec.get("waiting"):
            time.sleep(3)
            pid = app_pid()
            before = footprint(pid)
            print("before", before, "MB pid", pid, flush=True)
            continue
        if rec.get("done"):
            done = True
            continue
        if rec.get("start"):
            continue
        records.append(rec)
        print(json.dumps(rec), flush=True)
    if done:
        time.sleep(1)
        after = footprint(pid)
    alive = app_pid() is not None
    with open(out_path, "a") as f:
        f.write(json.dumps({"label": label, "spec": spec, "cap": cap, "done": done, "alive": alive,
                            "footprintBeforeMb": before, "footprintAfterMb": after, "footprintPeakMb": peak or None,
                            "problems": [], "runs": records}) + "\n")
    print(json.dumps({"label": label, "done": done, "alive": alive, "before": before, "after": after, "peak": peak}))
    proc.terminate()


main()
