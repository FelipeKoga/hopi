#!/bin/sh
# iOS request+parse matrix on the booted simulator: Release (no runtime, label nohopi) then Debug
# (the runtimes, default cap) then Debug with the 8 MB cap, for ROUNDS rounds.
# usage: ios-matrix.sh <out.ndjson> <sizes> <rounds> [spec ...]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PRODUCTS=$(cd "$HERE/../../.." && pwd)/build/giant-json-ios
OUT=$1; SIZES=$2; ROUNDS=$3; shift 3
r=1
while [ $r -le "$ROUNDS" ]; do
  for SPEC in "$@"; do
    for BUILD in nohopi real real8; do
      case $BUILD in
        nohopi) APP=$PRODUCTS/Release/Build/Products/Release-iphonesimulator/HopiSample.app; CAP="";;
        real) APP=$PRODUCTS/Debug/Build/Products/Debug-iphonesimulator/HopiSample.app; CAP="";;
        real8) APP=""; CAP="--cap 8388608";;
      esac
      if [ -n "$APP" ]; then xcrun simctl terminate booted dev.koga.hopi.sample 2>/dev/null; xcrun simctl install booted "$APP"; fi
      echo "== round $r $BUILD $SPEC"
      python3 "$HERE/ios.py" "ios-$BUILD" "mb=$SIZES;runs=5;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
    done
  done
  r=$((r+1))
done
