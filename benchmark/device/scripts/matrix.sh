#!/bin/sh
# Request+parse matrix on the emulator: for each spec, the noop build then the real build (same
# APK signing, installed over each other), default 256 KB cap; then the real build at the 8 MB cap.
# usage: matrix.sh <out.ndjson> <noop.apk> <real.apk> <sizes> [spec ...]
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; NOOP=$2; REAL=$3; SIZES=$4; shift 4
for SPEC in "$@"; do
  for BUILD in noop real real8; do
    case $BUILD in
      noop) APK=$NOOP; CAP="";;
      real) APK=$REAL; CAP="";;
      real8) APK=$REAL; CAP="--cap 8388608";;
    esac
    if [ "$BUILD" != real8 ]; then $ADB install -r "$APK" > /dev/null; fi
    echo "== $BUILD $SPEC load $($ADB shell cat /proc/loadavg)"
    python3 "$HERE/giant.py" "$BUILD" "mb=$SIZES;runs=5;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
  done
done
