#!/usr/bin/env python3
"""Paired table of `run.py ab ... nohopi,capture,nohopi2` NDJSON: per round, path and kind, the capture
block minus the mean of that round's two nohopi blocks (so drift within a round cancels), and nohopi2
minus nohopi (the noise band); then the median and range over the rounds, for the median and p90 wall
time and the calling thread's CPU per request, in µs.
usage: abtable.py <ab.ndjson> [...]
"""
import json, statistics, sys

LEGACY = {"noop": "nohopi", "noop2": "nohopi2"}  # the arms' labels until the runtime-split plan's stage 11 (2026-10-30)

cells = {}  # (path, kind) -> round -> arm -> entry
for path in sys.argv[1:]:
    for line in open(path):
        e = json.loads(line)
        cells.setdefault((e["path"], e["captureBench"]), {}).setdefault((path, e["round"]), {})[LEGACY.get(e["arm"], e["arm"])] = e

if not any({"nohopi", "nohopi2", "capture"} <= set(arms) for rounds in cells.values() for arms in rounds.values()):
    sys.exit("no round with a nohopi, a capture and a nohopi2 block")

print("| path | kind | metric | nohopi µs (mean) | capture − nohopi µs, p50 [min..max] | nohopi2 − nohopi µs (noise), p50 [min..max] | rounds |")
print("|---|---|---|---|---|---|---|")
for (path, kind), rounds in sorted(cells.items()):
    for metric in ("medianUs", "p90Us", "cpuUs"):
        diffs, noise, base = [], [], []
        for arms in rounds.values():
            if not {"nohopi", "nohopi2", "capture"} <= set(arms):
                continue
            mean = (arms["nohopi"][metric] + arms["nohopi2"][metric]) / 2
            base.append(mean)
            diffs.append(arms["capture"][metric] - mean)
            noise.append(arms["nohopi2"][metric] - arms["nohopi"][metric])
        if not diffs:
            continue
        print("| %s | %s | %s | %.0f | %+.0f [%+.0f..%+.0f] | %+.0f [%+.0f..%+.0f] | %d |" % (
            path, kind, metric, statistics.mean(base), statistics.median(diffs), min(diffs), max(diffs),
            statistics.median(noise), min(noise), max(noise), len(diffs)))
