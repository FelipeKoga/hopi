#!/bin/sh
# Alternating A/B rounds on the booted simulator, noop first: per round, each build is installed,
# then launched twice: -captureBench (URLSession: 200 sequential small requests, then 1/5/20 MB
# request plus full read) and -giant (Ktor Darwin: 1/5/20 MB fetch plus JSON parse).
# Usage: benchmark/urlsession/e2e/ab.sh <out dir> <apps dir> [rounds]; then abtable.py <out dir>.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
OUT=$1
APPS=$2
ROUNDS=${3:-4}
mkdir -p "$OUT"
round=1
while [ "$round" -le "$ROUNDS" ]; do
    for build in noop hopi; do
        xcrun simctl install booted "$APPS/$build.app"
        uptime >> "$OUT/load.txt"
        python3 "$here/drive.py" "$OUT" "$build-r$round-bench" -captureBench 'n=200;mb=1,5,20;runs=5' --no-cli --wait 300
        python3 "$here/drive.py" "$OUT" "$build-r$round-ktor" -giant 'mb=1,5,20;runs=5;delay=2' --no-cli --wait 300
    done
    round=$((round + 1))
done
