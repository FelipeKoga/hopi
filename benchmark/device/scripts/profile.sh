#!/bin/sh
# Profiles one giant spec on the emulator with simpleperf (cpu-clock, call graphs, 2000 Hz) while
# giant.py runs it, and writes the report with children to <out>-children.txt and the per-thread
# split to <out>-threads.txt. Install the APK to profile first; for a run with giant.py's --cap N
# (or --store N), one built with `-Psample.maxBodyBytes=N` (`-Psample.maxTotalBytes=N`), since those
# flags only label the run and set the Ktor plugin's own caps. giant.py's --build is refused: its
# Gradle build and install would run inside the recording.
# usage: profile.sh <out prefix> <label> <spec> <seconds> [giant.py args ...]
set -u
case " $* " in
  *" --build "*) echo "profile.sh: install the APK first; --build would build inside the recording" >&2; exit 2;;
esac
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; LABEL=$2; SPEC=$3; SECONDS_=$4; shift 4
PKG=dev.koga.hopi.sample
$ADB shell am force-stop $PKG; $ADB shell rm -f /data/local/tmp/perf.data
( $ADB shell simpleperf record --app $PKG -e cpu-clock -g -f 2000 --duration "$SECONDS_" -o /data/local/tmp/perf.data > "$OUT-record.log" 2>&1 ) &
REC=$!
python3 "$HERE/giant.py" "$LABEL" "$SPEC" "$OUT.ndjson" "$@" | grep -E '"runs":|PROBLEM' | cut -c1-300
wait $REC
$ADB shell simpleperf report -i /data/local/tmp/perf.data --children --sort symbol --percent-limit 0.01 > "$OUT-children.txt" 2>&1
$ADB shell simpleperf report -i /data/local/tmp/perf.data --sort tid,comm --percent-limit 0.5 > "$OUT-threads.txt" 2>&1
$ADB pull /data/local/tmp/perf.data "$OUT-perf.data" > /dev/null; $ADB shell am force-stop $PKG; $ADB shell rm -f /data/local/tmp/perf.data
