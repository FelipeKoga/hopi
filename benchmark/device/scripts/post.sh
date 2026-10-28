#!/bin/sh
# Copy cURL and Share of an 8 MB POST body (8 MB cap) on the emulator, three times each.
# Build <apk> with `-Psample.maxBodyBytes=8388608`: on Android the cap is a build setting, and
# giant.py's --cap only labels the run and sets the Ktor plugin's own cap.
# usage: post.sh <label> <apk>
S=$(cd "$(dirname "$0")" && pwd); A=~/Library/Android/sdk/platform-tools/adb
EVIDENCE=${EVIDENCE:-$(cd "$S/../../.." && pwd)/build/evidence/giant-json}
export EVIDENCE
U="python3 $S/ui.py"
$A install -r "$2" >/dev/null
python3 $S/giant.py "ui-$1" "mb=8;runs=1;post=1;delay=1" $EVIDENCE/android-ui-runs.ndjson --cap 8388608 --timeout 300 | grep -E '"runs":|PROBLEM' | cut -c1-120
sleep 3
$U tap "x open" "Open Hopi" >/dev/null
$U tap "$1 open detail" "/sink" --quiet 2 --max 60 | cut -c1-200
for i in 1 2 3; do
  $U tap "$1 copy curl" "Copy cURL" --quiet 2.5 --max 60 | cut -c1-330
done
$A exec-out screencap -p > $EVIDENCE/android-$1-curl-copied.png
$A shell input swipe 1000 273 100 273 300; sleep 1.5
for i in 1 2 3; do
  $U tap "$1 share" "Share" --quiet 2.5 --max 60 | cut -c1-330
  $A exec-out screencap -p > $EVIDENCE/android-$1-share.png
  $U back "$1 close share sheet" | cut -c1-200
  sleep 1
done
$A logcat -d -s AndroidRuntime:E | grep -E "FATAL|TransactionTooLarge|Exception" | head -3
