#!/bin/sh
# iOS twin of ui_hold.py: launches the sample with a giant spec plus hold=<s>;window=1 (frame
# ticker on, one line per second with the longest frame gap), then replays coordinate taps
# with agent-device (no snapshots during the hold) and logs host times, so each window can be
# attributed to its step with ios_hold_table.py. Run from the repo root (agent-device keys its
# session by working directory) after `agent-device open dev.koga.hopi.sample --platform ios`.
# usage: ios_hold.sh <label> <spec> <hold s> <cap bytes> [store bytes]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
EVIDENCE=${EVIDENCE:-$(cd "$HERE/../../.." && pwd)/build/evidence/giant-json}
LABEL=$1; SPEC=$2; HOLD=$3; CAP=$4; STORE=${5:-}
STOREARG=""; [ -n "$STORE" ] && STOREARG="--store $STORE"
LOG=$EVIDENCE/ios-$LABEL.log
python3 "$HERE/ios.py" "$LABEL" "$SPEC;hold=$HOLD;window=1;delay=2" "$EVIDENCE/ios-ui-hold.ndjson" --cap "$CAP" $STOREARG --timeout $((HOLD + 300)) > "$LOG" 2>&1 &
i=0; while [ $i -lt 150 ] && ! grep -q '"runs":' "$LOG"; do sleep 2; i=$((i+1)); done
sleep 2
STEPS=$EVIDENCE/ios-$LABEL-steps.txt
: > "$STEPS"
step() { echo "$(python3 -c 'import time; print(round(time.time(), 2))') $1" >> "$STEPS"; }
step "open inspector"; agent-device press 361 591 > /dev/null; sleep 3
step "open detail"; agent-device press 196 238 > /dev/null; sleep 4
step "response tab"; agent-device press 322 187 > /dev/null; sleep 3
# The simulator's own screenshot: proof the taps landed, without touching the app.
xcrun simctl io booted screenshot "$EVIDENCE/ios-$LABEL-response.png" > /dev/null 2>&1
step "scroll body"; agent-device swipe 196 700 196 250 --count 4 --pause-ms 300 > /dev/null; sleep 2
step "back to list"; agent-device press 28 87 > /dev/null; sleep 3
step "open detail again"; agent-device press 196 238 > /dev/null; sleep 4
step "(after the last step)"
wait
python3 "$HERE/ios_hold_table.py" "$LABEL" "$EVIDENCE/ios-ui-hold.ndjson" "$STEPS" | tee -a "$EVIDENCE/ios-ui-hold.jsonl"
