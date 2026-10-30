#!/usr/bin/env python3
"""Paired A/B from abn.sh NDJSON where each round runs `nohopi` first and `nohopi2` last: per round and
cell, each arm's block median minus the mean of that round's two nohopi block medians (so drift
within a round cancels), then the median and range of those differences over the rounds, in ms and
% of the nohopi mean. `nohopi2` against `nohopi` is the noise floor. Blocks are matched to rounds by order.
usage: paired.py <metric fetchMs|totalMs> <file.ndjson> [...]
"""
import json, statistics, sys

LEGACY = {"noop": "nohopi", "noop2": "nohopi2"}  # the arms' labels until the runtime-split plan's stage 11 (2026-10-30)

metric = sys.argv[1]
rounds = []  # one dict per (file, round, spec): label -> {mb: block median}
for path in sys.argv[2:]:
    current = None
    for line in open(path):
        b = json.loads(line)
        runs = [r for r in b["runs"] if "run" in r and "totalMs" in r and r["run"] > 5]
        if not runs:
            continue
        chunked = runs[0]["chunked"]
        label = LEGACY.get(b["label"], b["label"])
        if label == "nohopi":
            current = {"chunked": chunked, "arms": {}}
            rounds.append(current)
        if current is None or current["chunked"] != chunked:
            continue
        current["arms"][label] = {mb: statistics.median(r[metric] for r in runs if r["mb"] == mb)
                                       for mb in sorted({r["mb"] for r in runs})}

diffs = {}
for rnd in rounds:
    arms = rnd["arms"]
    if "nohopi" not in arms or "nohopi2" not in arms:
        continue
    for mb in arms["nohopi"]:
        base = (arms["nohopi"][mb] + arms["nohopi2"][mb]) / 2
        for label, values in arms.items():
            if label == "nohopi" or mb not in values:
                continue
            d = values[mb] - (arms["nohopi"][mb] if label == "nohopi2" else base)
            diffs.setdefault((rnd["chunked"], mb, label), []).append((d, base))

if not diffs:
    sys.exit("no round with a nohopi and a nohopi2 block")

print("| body | MB | arm | rounds | %s diff p50 ms | p50 %% | min..max ms |" % metric)
print("|---|---|---|---|---|---|---|")
for k in sorted(diffs):
    chunked, mb, label = k
    ds = diffs[k]
    med = statistics.median(d for d, _ in ds)
    base = statistics.mean(b for _, b in ds)
    name = "nohopi2 - nohopi (noise)" if label == "nohopi2" else label + " - nohopi mean"
    print("| %s | %d | %s | %d | %+.2f | %+.1f%% | %+.1f..%+.1f |" % ("chunked" if chunked else "Content-Length", mb, name,
          len(ds), med, 100 * med / base, min(d for d, _ in ds), max(d for d, _ in ds)))
