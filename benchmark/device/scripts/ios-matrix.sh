#!/bin/sh
# iOS request+parse matrix on the booted simulator: Release (no runtime, label nohopi) then Debug
# (the runtimes, default cap) then Debug with the 8 MB cap, for ROUNDS rounds. Each arm installs its
# own app from build/giant-json-ios/<dir> (xcodebuild ... -derivedDataPath build/giant-json-ios/<dir>):
# Release (-configuration Release), Debug (-configuration Debug) and Debug8, since O8 (2026-11-03)
# the cap is the build's: -configuration Debug
# SAMPLE_GRADLE_ARGS="-Psample.maxBodyBytes=8388608 -Psample.maxTotalBytes=33554432".
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
        nohopi) APP=$PRODUCTS/Release/Build/Products/Release-iphonesimulator/HopiSample.app;;
        real) APP=$PRODUCTS/Debug/Build/Products/Debug-iphonesimulator/HopiSample.app;;
        real8) APP=$PRODUCTS/Debug8/Build/Products/Debug-iphonesimulator/HopiSample.app;;
      esac
      xcrun simctl terminate booted dev.koga.hopi.sample 2>/dev/null
      xcrun simctl install booted "$APP" || exit 1
      echo "== round $r $BUILD $SPEC"
      python3 "$HERE/ios.py" "ios-$BUILD" "mb=$SIZES;runs=5;$SPEC" "$OUT" --timeout 900 | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
    done
  done
  r=$((r+1))
done
