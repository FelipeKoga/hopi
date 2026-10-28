#!/bin/sh
# One inspector scenario on the emulator (the real build must be installed): launches the sample
# with a giant spec (optionally a raised capture cap and store byte cap), waits for its done line,
# then opens the inspector, the giant transaction, its Response tab, scrolls the body, copies the
# body and the cURL, opens and closes the share sheet, and goes back; each step is timed by ui.py
# into $EVIDENCE/android-ui.jsonl (default <repo>/build/evidence/giant-json).
# On Android the caps are build settings: with a cap or a store cap, giant.py --build first builds
# and installs the working tree's debug sample with them, and that build stays installed (reinstall
# the default one, `./gradlew :sample:androidApp:installDebug`, before an uncapped run).
# usage: inspector.sh <label> <spec> [cap bytes] [row text] [store bytes]
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
EVIDENCE=${EVIDENCE:-$(cd "$HERE/../../.." && pwd)/build/evidence/giant-json}
export EVIDENCE
mkdir -p "$EVIDENCE"
LABEL=$1; SPEC=$2; CAP=${3:-}; ROW=${4:-/giant}; STORE=${5:-}
CAPARG=""; [ -n "$CAP" ] && CAPARG="--cap $CAP"
STOREARG=""; [ -n "$STORE" ] && STOREARG="--store $STORE"
BUILDARG=""; [ -n "$CAP$STORE" ] && BUILDARG="--build"
python3 "$HERE/giant.py" "ui-$LABEL" "$SPEC;delay=1" "$EVIDENCE/android-ui-runs.ndjson" --timeout 300 $CAPARG $STOREARG $BUILDARG | grep -E '"runs":|PROBLEM' | cut -c1-200
sleep 3
U="python3 $HERE/ui.py"
mem() {
  echo "{\"label\": \"$LABEL meminfo $1\", \"meminfo\": \"$($ADB shell dumpsys meminfo dev.koga.hopi.sample | grep -E 'Java Heap:|Native Heap:|TOTAL PSS:' | tr -s ' ' | tr '\n' ';')\"}" | tee -a "$EVIDENCE/android-ui.jsonl"
}
mem "before opening the inspector"
$U tap "$LABEL open list" "Open Hopi"
$U tap "$LABEL open detail" "$ROW" --quiet 2 --max 60
$U tap "$LABEL response tab" "Response" --quiet 2 --max 60
$ADB exec-out screencap -p > "$EVIDENCE/android-$LABEL-response.png"
mem "with the response tab open"
$U scroll "$LABEL scroll body" 12
$U tap "$LABEL copy body" "Copy body" --quiet 2.5 --max 60
$ADB exec-out screencap -p > "$EVIDENCE/android-$LABEL-copied.png"
$U tap "$LABEL copy curl" "Copy cURL" --quiet 2.5 --max 60
$ADB exec-out screencap -p > "$EVIDENCE/android-$LABEL-curl-copied.png"
# The action row scrolls sideways on a phone: bring Share into view first (its own swipe).
$ADB shell input swipe 1000 273 100 273 300; sleep 1.5
$U tap "$LABEL share" "Share" --quiet 2.5 --max 60
$ADB exec-out screencap -p > "$EVIDENCE/android-$LABEL-share.png"
$U back "$LABEL close share sheet"
$U tap "$LABEL back to list" "Back to requests" --quiet 1.5 --max 30
$U tap "$LABEL focus search" "Search URL" --quiet 1 --max 10
$U type "$LABEL search giant" "giant"
mem "at the end"
