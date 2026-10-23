#!/usr/bin/env python3
"""Attributes the hold windows of one ios.py block (by label) to the steps logged by ios_hold.sh.
usage: ios_hold_table.py <label> <ios-ui-hold.ndjson> <steps.txt>"""
import json, sys

label, ndjson, steps_path = sys.argv[1:4]
steps = [(float(l.split(" ", 1)[0]), l.split(" ", 1)[1].strip()) for l in open(steps_path) if l.strip()]
block = [json.loads(l) for l in open(ndjson) if json.loads(l)["label"] == label][-1]
windows = [r for r in block["runs"] if "hold" in r]


def step_of(at):
    current = "(before the first step)"
    for started, name in steps:
        if started <= at - 0.5:
            current = name
    return current


for _, name in steps:
    mine = [w for w in windows if step_of(w["receivedAt"]) == name]
    if mine:
        print(json.dumps({"label": "%s %s" % (label, name), "windows": len(mine),
                          "gapMaxMs": max(w.get("gapMaxMs", 0) for w in mine),
                          "gapsOver100Ms": sum(w.get("gapsOver100Ms", 0) for w in mine),
                          "gapsOver33Ms": sum(w.get("gapsOver33Ms", 0) for w in mine),
                          "footprintAfterMb": block.get("footprintAfterMb"), "alive": block.get("alive")}))
