#!/bin/sh
# Warm A/B of any number of builds on the emulator: for ROUNDS rounds, for each spec, each build
# in turn is installed (always, so every block starts from the same fresh install), the guest is
# left to settle (1-minute load under 2.5, at most 180 s), and giant.py runs RUNS runs per size.
# warm.py then keeps the runs after the 5th of each block.
# A label ending in -aot is compiled with `cmd package compile -m speed` after its install.
# HOST_LOAD_MAX, when set, also waits (at most 180 s) for the host's 1-minute load to fall under it.
# usage: abn.sh <out.ndjson> <sizes> <runs> <rounds> "<label>=<apk>[@<cap>] ..." [spec ...]
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; SIZES=$2; RUNS=$3; ROUNDS=$4; BUILDS=$5; shift 5
settle() {
  i=0
  while [ $i -lt 90 ]; do
    l=$($ADB shell cat /proc/loadavg | cut -d' ' -f1)
    python3 -c "import sys; sys.exit(0 if float('$l') < 2.5 else 1)" && break
    sleep 2; i=$((i+1))
  done
  h=$(sysctl -n vm.loadavg | cut -d' ' -f2)
  if [ -n "${HOST_LOAD_MAX:-}" ]; then
    i=0
    while [ $i -lt 90 ]; do
      h=$(sysctl -n vm.loadavg | cut -d' ' -f2)
      python3 -c "import sys; sys.exit(0 if float('$h') < float('$HOST_LOAD_MAX') else 1)" && break
      sleep 2; i=$((i+1))
    done
  fi
  echo "load $l host $h"
}
r=1
while [ $r -le "$ROUNDS" ]; do
  for SPEC in "$@"; do
    for B in $BUILDS; do
      LABEL=${B%%=*}; REST=${B#*=}
      APK=${REST%@*}; CAP=""
      case $REST in *@*) CAP="--cap ${REST#*@}";; esac
      $ADB install -r "$APK" > /dev/null
      case $LABEL in
        # Compiled ahead of time, as a release-like run: no interpreter, no JIT thread.
        *-aot) $ADB shell cmd package compile -m speed -f dev.koga.hopi.sample > /dev/null
          echo "dexopt $($ADB shell dumpsys package dev.koga.hopi.sample | grep -o 'status=[a-z-]*' | head -1)";;
      esac
      echo "== round $r $LABEL $SPEC $(settle)"
      python3 "$HERE/giant.py" "$LABEL" "mb=$SIZES;runs=$RUNS;delay=3;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
    done
  done
  r=$((r+1))
done
