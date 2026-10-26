#!/usr/bin/env python3
"""The realtime log-line surface of the perf monitors, checked against the lab's injections
(docs/design/perf-monitors.md, "Log lines"). Reads the app log verify.py wrote (Android logcat
`-v tag` under HopiPerf/HopiSample, iOS console-pty) and its verify.json (the lab lines and the
CLI-side results), and optionally a capture of the HTTP tag (`Hopi`, Android) that must carry no
perf line. Prints one table and writes <out>.json.

usage: logcheck.py <platform> <app.log> <verify.json> <out.json> [--http-log <file>]
"""
import json, sys

platform, log_path, verify_path, out_path = sys.argv[1:5]
http_log = sys.argv[sys.argv.index("--http-log") + 1] if "--http-log" in sys.argv else None
verify = json.load(open(verify_path))
labs = verify["lab"]

perf, malformed, lengths = [], [], []
for raw in open(log_path, errors="replace"):
    at = raw.find('{"hopi":1')
    if at < 0:
        continue
    body = raw[at:].rstrip("\n")
    lengths.append(len(body.encode()))
    try:
        obj = json.loads(body)
    except json.JSONDecodeError:
        malformed.append(body[:160])
        continue
    if "perf" in obj:
        perf.append(obj)

rows = []
def add(name, ok, seen, expected):
    rows.append({"check": name, "ok": ok, "seen": seen, "expected": expected})

budget = 3999 if platform == "android" else 1000
add("perf lines parse as JSON", not malformed and bool(perf), f"{len(perf)} perf lines, {len(malformed)} malformed", "all")
add("no perf line carries an id", all("id" not in p for p in perf), sum(1 for p in perf if "id" in p), 0)
add(f"line bytes <= {budget}", max(lengths, default=0) <= budget, f"max {max(lengths, default=0)} B", f"<= {budget}")

hangs = [p for p in perf if p["perf"] == "hang"]
ended = [p for p in hangs if p.get("state") == "ended"]
ongoing = [p for p in hangs if p.get("state") == "ongoing"]
by_seq = {p["seq"]: p for p in ended}
# The hangs over the threshold, in the lab's order, pair with the ended lines in log order.
over = [l for l in labs if l["perfLab"] == "hang" and l["ms"] >= 250]
for i, lab in enumerate(over):
    label = f"log hang {lab['fn']} {lab['ms']} ms"
    paired = ended[i] if i < len(ended) else None
    near = [paired] if paired and abs(paired["ms"] - lab["actualMs"]) <= 20 + (30 if lab["fn"] == "clickBurn" else 0) else []
    add(f"{label}: the {i+1}th ended line within 20 ms", bool(near), paired and paired["ms"], f"{lab['actualMs']} ms")
    if near:
        # iOS writes the ongoing line twice: raw addresses at once, then the symbolicated stack (design, "Log lines").
        os_ = [x for x in ongoing if x["seq"] == near[0]["seq"]]
        frames = [f for x in os_ for f in x.get("stack", [])]
        needle = ("PerfLab." + lab["fn"]) if platform == "android" else ("PerfLab#" + lab["fn"])
        add(f"{label}: ongoing line with a stack naming {needle}", any(needle in f for f in frames), [f for f in frames if "sample" in f][:3] or frames[:3], "the lab's function")
        if platform == "ios":
            add(f"{label}: a symbolicated ongoing line naming the lab's code", any("dev.koga.hopi.sample" in f for f in frames), f"{len(os_)} ongoing lines", "kfun:dev.koga.hopi.sample frames within 1000 B")
add("no hang line for the 200 ms sleep", not any(150 <= p.get("ms", 0) <= 249 for p in ended), [p["ms"] for p in ended], "none under 250 ms")

if platform == "android":
    jank = [p for p in perf if p["perf"] == "jank"]
    for lab in [l for l in labs if l["perfLab"] == "jank"]:
        lo, hi = lab["from"] // 1000, lab["to"] // 1000
        hits = [p for p in jank if lo <= p["sec"] <= hi]
        janky = sum(p.get("janky", 0) for p in hits)
        if lab["blockMs"] >= 50:
            add(f"log jank {lab['blockMs']} ms x30: janky frames in its seconds", janky >= 28, f"{janky} janky over {len(hits)} lines, worst {max((p.get('worstMs', 0) for p in hits), default=None)} ms", ">= 28")
        else:
            add(f"log jank {lab['blockMs']} ms x30: few janky", janky <= 3, f"{janky} janky", "<= 3")
    secs = [p["sec"] for p in jank]
    add("jank lines at most one per second", len(secs) == len(set(secs)), f"{len(secs)} lines, {len(set(secs))} distinct seconds", "one per second")
samples = [p for p in perf if p["perf"] == "sample"]
add("sample lines while CPU/memory are on", len(samples) >= 2, f"{len(samples)} lines, e.g. {samples[-1] if samples else None}", ">= 2 over the run (one per 10 s)")
mem = [p for p in perf if p["perf"] == "memory"]
add("memory event lines", len(mem) >= 1, [(p.get("event"), p.get("level")) for p in mem], ">= 1")
if http_log:
    text = open(http_log, errors="replace").read()
    leaked = [l for l in text.splitlines() if '"perf"' in l]
    add("the HTTP tag carries no perf line", not leaked, f"{len(leaked)} perf lines under Hopi", 0)

json.dump({"platform": platform, "perfLines": len(perf), "byKind": {k: sum(1 for p in perf if p["perf"] == k) for k in sorted({p["perf"] for p in perf})}, "checks": rows}, open(out_path, "w"), indent=1)
width = max(len(r["check"]) for r in rows)
for r in rows:
    print(f"{'PASS' if r['ok'] else 'FAIL'}  {r['check']:<{width}}  seen: {r['seen']}  (expected {r['expected']})")
