#!/bin/sh
# Request+parse matrix on the emulator: for each spec, the nohopi build (no runtime) then the real
# build (same APK signing, installed over each other), default 256 KB cap; then the real build at
# the 8 MB cap, <real8.apk>. On Android the cap is a build setting, so build that one from the same tree with
# `./gradlew :sample:androidApp:assembleDebug -Psample.maxBodyBytes=8388608` and copy it out of
# build/outputs (the next build overwrites it); giant.py's --cap 8388608 labels the arm.
# usage: matrix.sh <out.ndjson> <nohopi.apk> <real.apk> <real8.apk> <sizes> [spec ...]
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; NOHOPI=$2; REAL=$3; REAL8=$4; SIZES=$5; shift 5
for SPEC in "$@"; do
  for BUILD in nohopi real real8; do
    case $BUILD in
      nohopi) APK=$NOHOPI; CAP="";;
      real) APK=$REAL; CAP="";;
      real8) APK=$REAL8; CAP="--cap 8388608";;
    esac
    $ADB install -r "$APK" > /dev/null
    echo "== $BUILD $SPEC load $($ADB shell cat /proc/loadavg)"
    python3 "$HERE/giant.py" "$BUILD" "mb=$SIZES;runs=5;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
  done
done
