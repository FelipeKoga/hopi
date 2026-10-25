#!/bin/sh
# The Android end-to-end capture check on the one attached emulator or device.
# Needs: the dev server up and logging to $SERVER_LOG, `adb reverse tcp:8765 tcp:8765`, the CLI
# installed (:hopi-cli:installDist), and the sample's two debug APKs: $CAPTURE (plain assembleDebug)
# and $NOOP (assembleDebug -Psample.hopiCapture=false, built as release is).
# Usage: benchmark/android-capture/e2e/run.sh <out dir> <server log> <capture apk> <noop apk> [https url]
# then check.py <out dir>.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
ADB=~/Library/Android/sdk/platform-tools/adb
OUT=$1
SERVER_LOG=$2
CAPTURE=$3
NOOP=$4
HTTPS=${5:-https://example.com/}
mkdir -p "$OUT"

# One drive.py run, with the dev server's lines for exactly that run kept next to it.
step() {
    label=$1
    shift
    before=$(wc -l < "$SERVER_LOG")
    python3 "$here/drive.py" "$OUT" "$label" "$@"
    tail -n +"$((before + 1))" "$SERVER_LOG" > "$OUT/$label-server.ndjson"
}

$ADB install -r -t "$CAPTURE" > /dev/null
step capture -- --es verify all --es verifyHttps "$HTTPS"
$ADB install -r -t "$NOOP" > /dev/null
step noop --no-cli -- --es verify all --es verifyHttps "$HTTPS"
