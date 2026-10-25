#!/bin/sh
# The end-to-end URLSession capture check on the booted simulator (iPhone 17, iOS 27.0).
# Needs: the dev server up and logging to $SERVER_LOG, the CLI installed (:hopi-cli:installDist),
# and the two Debug apps built (Hopi: `xcodebuild ... -configuration Debug`, noop: the same with
# HOPI_ENABLED=false) at $APPS/hopi.app and $APPS/noop.app.
# Usage: benchmark/urlsession/e2e/run.sh <out dir> <server log> <apps dir>; then check.py <out dir>.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
OUT=$1
SERVER_LOG=$2
APPS=$3
mkdir -p "$OUT"

# One drive.py run, with the dev server's lines for exactly that run kept next to it.
step() {
    label=$1
    shift
    before=$(wc -l < "$SERVER_LOG")
    python3 "$here/drive.py" "$OUT" "$label" "$@"
    tail -n +"$((before + 1))" "$SERVER_LOG" > "$OUT/$label-server.ndjson"
}

xcrun simctl install booted "$APPS/hopi.app"
step hopi-swift -verifyCapture --oslog
step hopi-ktor -ktorVerify --oslog
step hopi-sdk -thirdPartySDK --wait 60
xcrun simctl install booted "$APPS/noop.app"
step noop-swift -verifyCapture --no-cli
step noop-ktor -ktorVerify --no-cli
