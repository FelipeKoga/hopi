#!/bin/sh
# A/B of one detail on the emulator: installs <apk>, runs a giant spec, opens the detail <times>
# times (fresh cache each time, reopen.sh), polls the Java heap every 0.3 s meanwhile (dumpsys
# meminfo, its peak), then reads the heap after a forced GC (am dumpheap -g). Run it once per APK.
# <cap> and <store> label the run and set only the Ktor plugin's own caps (giant.py --cap, --store):
# on Android OkHttp capture and the store take the APK's, so build <apk> with
# `-Psample.maxBodyBytes=<cap>` (and `-Psample.maxTotalBytes=<store>`).
# usage: ab.sh <label> <apk> <spec> <cap> <store|-> <row> <times>
set -u
S=$(cd "$(dirname "$0")" && pwd)
EVIDENCE=${EVIDENCE:-$(cd "$S/../../.." && pwd)/build/evidence/giant-json}
export EVIDENCE
A=~/Library/Android/sdk/platform-tools/adb
SP=$EVIDENCE/tmp
mkdir -p "$SP"
LABEL=$1; APK=$2; SPEC=$3; CAP=$4; STORE=$5; ROW=$6; N=$7
$A install -r "$APK" >/dev/null
STOREARG=""; [ "$STORE" != "-" ] && STOREARG="--store $STORE"
python3 "$S/giant.py" "ui-$LABEL" "$SPEC;delay=1" "$EVIDENCE/android-ui-runs.ndjson" --cap "$CAP" $STOREARG --timeout 300 | grep -E '"runs":|PROBLEM' | cut -c1-160
sleep 3
python3 "$S/ui.py" tap "x open" "Open Hopi" >/dev/null
# The stop flag is removed before the poller starts, so it cannot see the previous run's flag.
rm -f "$SP/stop-poll"
( while [ ! -f "$SP/stop-poll" ]; do
    $A shell dumpsys meminfo dev.koga.hopi.sample | grep -E "Java Heap:" | tr -s ' ' | cut -d' ' -f4
    sleep 0.3
  done > "$SP/poll-$LABEL.txt" ) &
python3 "$S/ui.py" tap "$LABEL open detail" "$ROW" --quiet 2 --max 60 | cut -c1-200
sh "$S/reopen.sh" "$LABEL open detail repeat" "$ROW" $((N - 1))
python3 "$S/ui.py" tap "$LABEL response tab" "Response" --quiet 2 --max 60 | cut -c1-200
touch "$SP/stop-poll"; sleep 1
PEAK=$(sort -n "$SP/poll-$LABEL.txt" | tail -1 | grep . || echo null)
SAMPLES=$(wc -l < "$SP/poll-$LABEL.txt" | tr -d ' ')
echo "{\"label\": \"$LABEL java heap kb while opening (dumpsys every 0.3 s)\", \"max\": $PEAK, \"samples\": $SAMPLES}" | tee -a "$EVIDENCE/android-ui.jsonl"
PID=$($A shell pidof dev.koga.hopi.sample | tr -d '\r')
# dumpheap -g collects garbage first; the dump itself is deleted at once.
$A shell am dumpheap -g "$PID" /data/local/tmp/ab.hprof >/dev/null 2>&1; sleep 4; $A shell rm -f /data/local/tmp/ab.hprof
MEM=$($A shell dumpsys meminfo dev.koga.hopi.sample | grep -E 'Java Heap:|TOTAL PSS:' | tr -s ' ' | tr '\n' ';')
echo "{\"label\": \"$LABEL meminfo after a forced GC, detail open\", \"meminfo\": \"$MEM\"}" | tee -a "$EVIDENCE/android-ui.jsonl"
$A logcat -d -s AndroidRuntime:E art:I | grep -E "OutOfMemory|FATAL|Background concurrent|blocking" | tail -3
