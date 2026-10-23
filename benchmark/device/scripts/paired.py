#!/usr/bin/env python3
"""Paired A/B from abn.sh NDJSON where each round runs `noop` first and `noop2` last: per round and
cell, each arm's block median minus the mean of that round's two noop block medians (so drift
within a round cancels), then the median and range of those differences over the rounds, in ms and
% of the noop mean. `noop2` against `noop` is the noise floor. Blocks are matched to rounds by order.
usage: paired.py <metric fetchMs|totalMs> <file.ndjson> [...]
"""
import json, statistics, sys

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
        if b["label"] == "noop":
            current = {"chunked": chunked, "arms": {}}
            rounds.append(current)
        if current is None or current["chunked"] != chunked:
            continue
        current["arms"][b["label"]] = {mb: statistics.median(r[metric] for r in runs if r["mb"] == mb)
                                       for mb in sorted({r["mb"] for r in runs})}

diffs = {}
for rnd in rounds:
    arms = rnd["arms"]
    if "noop" not in arms or "noop2" not in arms:
        continue
    for mb in arms["noop"]:
        base = (arms["noop"][mb] + arms["noop2"][mb]) / 2
        for label, values in arms.items():
            if label == "noop" or mb not in values:
                continue
            d = values[mb] - (arms["noop"][mb] if label == "noop2" else base)
            diffs.setdefault((rnd["chunked"], mb, label), []).append((d, base))

print("| body | MB | arm | rounds | %s diff p50 ms | p50 %% | min..max ms |" % metric)
print("|---|---|---|---|---|---|---|")
for k in sorted(diffs):
    chunked, mb, label = k
    ds = diffs[k]
    med = statistics.median(d for d, _ in ds)
    base = statistics.mean(b for _, b in ds)
    name = "noop2 - noop (noise)" if label == "noop2" else label + " - noop mean"
    print("| %s | %d | %s | %d | %+.2f | %+.1f%% | %+.1f..%+.1f |" % ("chunked" if chunked else "Content-Length", mb, name,
          len(ds), med, 100 * med / base, min(d for d, _ in ds), max(d for d, _ in ds)))
