#!/usr/bin/env python3
"""Tables for ab.sh: per round and build, the URLSession per-request median and the 1/5/20 MB
request-plus-read medians, and the Ktor Darwin 1/5/20 MB fetch-plus-parse medians; then, per
metric, the median of the round medians for each build, Hopi minus noop, and the noop-vs-noop
noise band (the spread of the noop rounds' medians, and the largest difference between two
consecutive noop rounds).

usage: abtable.py <ab dir>   (writes <ab dir>/summary.json and prints a Markdown table)
"""
import glob, json, os, re, statistics, sys


def lines(path, prefix):
    out = []
    for line in open(path, errors="replace"):
        i = line.find(prefix)
        if i >= 0:
            try:
                out.append(json.loads(line[i + len(prefix):].strip()))
            except ValueError:
                pass
    return out


def main():
    root = sys.argv[1]
    rounds = {}
    for path in sorted(glob.glob(os.path.join(root, "*-r*-bench-app.log")) + glob.glob(os.path.join(root, "*-r*-ktor-app.log"))):
        build, rnd, kind = re.match(r"(\w+)-r(\d+)-(bench|ktor)-app.log", os.path.basename(path)).groups()
        metrics = rounds.setdefault((build, int(rnd)), {})
        if kind == "bench":
            for rec in lines(path, "HopiVerify: "):
                if rec.get("bench") == "small":
                    metrics["small request median (ms)"] = rec["medianMs"]
                    metrics["small request p90 (ms)"] = rec["p90Ms"]
                    metrics["process CPU per small request (us)"] = rec["processCpuUsPerRequest"]
                elif rec.get("bench") == "giant":
                    metrics[f"URLSession {rec['mb']} MB request+read (ms)"] = rec["medianMs"]
                    metrics[f"URLSession {rec['mb']} MB process CPU per run (ms)"] = rec["processCpuMsPerRun"]
        else:
            for rec in lines(path, "HopiSample: "):
                if "medianTotalMs" in rec:
                    metrics[f"Ktor {rec['mb']} MB fetch+parse (ms)"] = rec["medianTotalMs"]
    names = []
    for m in rounds.values():
        for k in m:
            if k not in names:
                names.append(k)
    summary = {"rounds": {f"{b}-r{r}": m for (b, r), m in sorted(rounds.items(), key=lambda x: (x[0][1], x[0][0]))}, "metrics": {}}
    print("| metric | noop rounds | Hopi rounds | noop median | Hopi median | Hopi - noop | noop-vs-noop band |")
    print("|---|---|---|---|---|---|---|")
    for name in names:
        noop = [rounds[k][name] for k in sorted(rounds) if k[0] == "noop" and name in rounds[k]]
        hopi = [rounds[k][name] for k in sorted(rounds) if k[0] == "hopi" and name in rounds[k]]
        if not noop or not hopi:
            continue
        mn, mh = statistics.median(noop), statistics.median(hopi)
        consecutive = max((abs(a - b) for a, b in zip(noop, noop[1:])), default=0)
        band = {"spread": round(max(noop) - min(noop), 3), "maxConsecutiveDiff": round(consecutive, 3)}
        summary["metrics"][name] = {"noop": noop, "hopi": hopi, "noopMedian": mn, "hopiMedian": mh,
                                    "delta": round(mh - mn, 3), "noise": band}
        fmt = lambda xs: ", ".join(f"{x:g}" for x in xs)
        print(f"| {name} | {fmt(noop)} | {fmt(hopi)} | {mn:g} | {mh:g} | {mh - mn:+.3g} | {band['spread']:g} (consecutive {band['maxConsecutiveDiff']:g}) |")
    json.dump(summary, open(os.path.join(root, "summary.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
