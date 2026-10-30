#!/bin/sh
# Alternating A/B rounds on the booted simulator, nohopi first: per round, each build is installed,
# then launched twice: -captureBench (URLSession: 200 sequential small requests, then 1/5/20 MB
# request plus full read) and -giant (Ktor Darwin: 1/5/20 MB fetch plus JSON parse).
# Usage: benchmark/urlsession/e2e/ab.sh <out dir> <apps dir> [rounds [launch argument...]]; then
# abtable.py <out dir>. Launch arguments after the rounds are appended to both drive.py launches of
# every round, e.g. `4 -maxBodyBytes 8388608 -maxTotalBytes 33554432` for an 8 MiB capture cap.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
OUT=$1
APPS=$2
ROUNDS=${3:-4}
shift 2
if [ $# -gt 0 ]; then shift; fi
mkdir -p "$OUT"
round=1
while [ "$round" -le "$ROUNDS" ]; do
    for build in nohopi hopi; do
        xcrun simctl install booted "$APPS/$build.app"
        uptime >> "$OUT/load.txt"
        python3 "$here/drive.py" "$OUT" "$build-r$round-bench" -captureBench 'n=200;mb=1,5,20;runs=5' "$@" --no-cli --wait 300
        python3 "$here/drive.py" "$OUT" "$build-r$round-ktor" -giant 'mb=1,5,20;runs=5;delay=2' "$@" --no-cli --wait 300
    done
    round=$((round + 1))
done
