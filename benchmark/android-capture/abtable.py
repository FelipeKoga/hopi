#!/usr/bin/env python3
"""Paired table of `run.py ab ... noop,capture,noop2` NDJSON: per round, path and kind, the capture
block minus the mean of that round's two noop blocks (so drift within a round cancels), and noop2
minus noop (the noise band); then the median and range over the rounds, for the median and p90 wall
time and the calling thread's CPU per request, in µs.
usage: abtable.py <ab.ndjson> [...]
"""
import json, statistics, sys

cells = {}  # (path, kind) -> round -> arm -> entry
for path in sys.argv[1:]:
    for line in open(path):
        e = json.loads(line)
        cells.setdefault((e["path"], e["captureBench"]), {}).setdefault((path, e["round"]), {})[e["arm"]] = e

print("| path | kind | metric | noop µs (mean) | capture − noop µs, p50 [min..max] | noop2 − noop µs (noise), p50 [min..max] | rounds |")
print("|---|---|---|---|---|---|---|")
for (path, kind), rounds in sorted(cells.items()):
    for metric in ("medianUs", "p90Us", "cpuUs"):
        diffs, noise, base = [], [], []
        for arms in rounds.values():
            if not {"noop", "noop2", "capture"} <= set(arms):
                continue
            mean = (arms["noop"][metric] + arms["noop2"][metric]) / 2
            base.append(mean)
            diffs.append(arms["capture"][metric] - mean)
            noise.append(arms["noop2"][metric] - arms["noop"][metric])
        if not diffs:
            continue
        print("| %s | %s | %s | %.0f | %+.0f [%+.0f..%+.0f] | %+.0f [%+.0f..%+.0f] | %d |" % (
            path, kind, metric, statistics.mean(base), statistics.median(diffs), min(diffs), max(diffs),
            statistics.median(noise), min(noise), max(noise), len(diffs)))
