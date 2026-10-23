#!/bin/sh
# iOS twin of abn.sh on the booted simulator: for ROUNDS rounds, for each spec, each build's .app
# is installed in turn (the running app terminated first) and ios.py runs RUNS runs per size.
# usage: ios-ab.sh <out.ndjson> <sizes> <runs> <rounds> "<label>=<app>[@<cap>] ..." [spec ...]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; SIZES=$2; RUNS=$3; ROUNDS=$4; BUILDS=$5; shift 5
r=1
while [ $r -le "$ROUNDS" ]; do
  for SPEC in "$@"; do
    for B in $BUILDS; do
      LABEL=${B%%=*}; REST=${B#*=}
      APP=${REST%@*}; CAP=""
      case $REST in *@*) CAP="--cap ${REST#*@}";; esac
      xcrun simctl terminate booted dev.koga.hopi.sample 2>/dev/null
      xcrun simctl install booted "$APP"
      echo "== round $r $LABEL $SPEC host load $(sysctl -n vm.loadavg)"
      python3 "$HERE/ios.py" "$LABEL" "mb=$SIZES;runs=$RUNS;delay=3;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
    done
  done
  r=$((r+1))
done
