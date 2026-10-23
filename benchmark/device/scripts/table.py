#!/usr/bin/env python3
"""Markdown table of giant.py results: median per (build, cap, shape, chunked, post, mb) over runs."""
import json, statistics, sys
from collections import defaultdict

groups = defaultdict(list)
mem = {}
for path in sys.argv[1:]:
    for line in open(path):
        block = json.loads(line)
        for r in block["runs"]:
            if "run" not in r:
                continue
            key = (block["label"], r["capBytes"], r["shape"], r["chunked"], r.get("post", False), r["mb"])
            groups[key].append(r)
        mem[(block["label"], block["spec"])] = (block.get("meminfoBefore"), block.get("meminfoAfter"), block["problems"])

def med(rs, k):
    xs = [r[k] for r in rs if k in r]
    return statistics.median(xs) if xs else None

print("| build | cap | shape | chunked | MB | ok | total p50 ms | fetch p50 | parse p50 | min..max total | GC ms p50 | blocking GC ms p50 | frame gap max ms (worst run) | gaps >100 ms (all runs) | heap after KB p50 |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for key in sorted(groups, key=lambda k: (k[2], k[3], k[4], k[5], k[0], k[1])):
    rs = groups[key]
    ok = [r for r in rs if "totalMs" in r]
    label, cap, shape, chunked, post, mb = key
    tot = [r["totalMs"] for r in ok]
    print("| %s | %s | %s%s | %s | %d | %d/%d | %s | %s | %s | %s | %s | %s | %s | %d | %s |" % (
        label, "default" if cap == -1 else "%d MB" % (cap // 1048576), shape, " POST" if post else "", "yes" if chunked else "no", mb,
        len(ok), len(rs), med(ok, "totalMs"), med(ok, "fetchMs"), med(ok, "parseMs"),
        "%s..%s" % (min(tot), max(tot)) if tot else "-", med(ok, "gcTimeMs"), med(ok, "blockingGcTimeMs"),
        max([r.get("gapMaxMs", 0) for r in rs]), sum(r.get("gapsOver100Ms", 0) for r in rs), med(ok, "javaHeapUsedKbAfter")))
    errs = {r["error"][:90] for r in rs if "error" in r}
    for e in errs:
        print("|  |  |  |  |  | error: %s |" % e)
print()
print("| block | spec | Java heap KB before → after | native KB before → after | PSS KB before → after | problems |")
print("|---|---|---|---|---|---|")
for (label, spec), (b, a, p) in mem.items():
    g = lambda d, k: d.get(k) if d else None
    print("| %s | %s | %s → %s | %s → %s | %s → %s | %d |" % (label, spec, g(b, "javaHeapKb"), g(a, "javaHeapKb"), g(b, "nativeHeapKb"), g(a, "nativeHeapKb"), g(b, "totalPssKb"), g(a, "totalPssKb"), len(p)))
