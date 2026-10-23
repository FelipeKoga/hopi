#!/usr/bin/env python3
"""Warm A/B table from abn.sh / ios.py NDJSON: per (chunked, MB, label) the median fetch and total
of the runs after the 5th of each block, the % over the baseline label, and the spread of the
per-block medians (min..max), so the noise floor is visible next to each difference.
usage: abtable.py <baseline label> <file.ndjson> [...]
"""
import json, statistics, sys

base = sys.argv[1]
d = {}
for path in sys.argv[2:]:
    for line in open(path):
        b = json.loads(line)
        block = [r for r in b["runs"] if "run" in r and "totalMs" in r and r["run"] > 5]
        errors = sum(1 for r in b["runs"] if "run" in r and "error" in r)
        for r in block:
            k = (r["chunked"], r["mb"], b["label"])
            e = d.setdefault(k, {"runs": [], "blocks": {}, "errors": 0})
            e["runs"].append(r)
        for r in b["runs"]:
            if "run" in r and "error" in r:
                d.setdefault((r["chunked"], r["mb"], b["label"]), {"runs": [], "blocks": {}, "errors": 0})["errors"] += 1
        for mb in {r["mb"] for r in block}:
            rs = [r for r in block if r["mb"] == mb]
            k = (rs[0]["chunked"], mb, b["label"])
            d[k]["blocks"].setdefault("fetchMs", []).append(statistics.median(x["fetchMs"] for x in rs))
            d[k]["blocks"].setdefault("totalMs", []).append(statistics.median(x["totalMs"] for x in rs))

med = lambda rs, key: statistics.median(r[key] for r in rs)
print("| body | MB | build | warm runs | fetch p50 ms | fetch vs %s | total p50 ms | total vs %s | block p50 fetch (min..max) | block p50 total (min..max) | errors |" % (base, base))
print("|---|---|---|---|---|---|---|---|---|---|---|")
for k in sorted(d, key=lambda k: (k[0], k[1], k[2] != base, k[2])):
    chunked, mb, label = k
    e = d[k]
    if not e["runs"]:
        print("| %s | %d | %s | 0 | | | | | | | %d |" % ("chunked" if chunked else "Content-Length", mb, label, e["errors"]))
        continue
    b = d.get((chunked, mb, base))
    f, t = med(e["runs"], "fetchMs"), med(e["runs"], "totalMs")
    pct = lambda v, key: ("%+.1f%%" % (100.0 * (v / med(b["runs"], key) - 1))) if b and b["runs"] and label != base else ""
    sp = lambda key: "%s..%s" % (min(e["blocks"][key]), max(e["blocks"][key]))
    print("| %s | %d | %s | %d | %s | %s | %s | %s | %s | %s | %d |" % ("chunked" if chunked else "Content-Length", mb, label, len(e["runs"]),
          f, pct(f, "fetchMs"), t, pct(t, "totalMs"), sp("fetchMs"), sp("totalMs"), e["errors"]))
