#!/usr/bin/env python3
"""Warm-run medians (runs after the 5th) per label and size from giant.py NDJSON files."""
import json, statistics, sys
d = {}
for path in sys.argv[1:]:
    for l in open(path):
        b = json.loads(l)
        for r in b["runs"]:
            if "run" in r and "totalMs" in r:
                d.setdefault((b["label"], r["mb"]), []).append(r)
for (label, mb), rs in sorted(d.items(), key=lambda kv: (kv[0][1], kv[0][0])):
    warm = [r for r in rs if r["run"] > 5] or rs
    m = lambda k: statistics.median([r[k] for r in warm])
    print("| %s | %d | %d | %s | %s | %s | %s |" % (label, mb, len(warm), m("totalMs"), m("fetchMs"), m("parseMs"),
          m("gcTimeMs") if "gcTimeMs" in warm[0] else "-"))
