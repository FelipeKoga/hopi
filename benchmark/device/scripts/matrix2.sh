#!/bin/sh
# Like matrix.sh, but alternates nohopi / real / real8 for ROUNDS rounds and waits before each
# block until the guest's 1-minute load is under 2.5 (at most 180 s), so builds see the same load.
# <real8.apk> is the real build made with -Psample.maxBodyBytes=8388608, as matrix.sh says.
# usage: matrix2.sh <out.ndjson> <nohopi.apk> <real.apk> <real8.apk> <sizes> <rounds> [spec ...]
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1; NOHOPI=$2; REAL=$3; REAL8=$4; SIZES=$5; ROUNDS=$6; shift 6
settle() {
  i=0
  while [ $i -lt 90 ]; do
    l=$($ADB shell cat /proc/loadavg | cut -d' ' -f1)
    python3 -c "import sys; sys.exit(0 if float('$l') < 2.5 else 1)" && break
    sleep 2; i=$((i+1))
  done
  echo "load $l"
}
r=1
while [ $r -le "$ROUNDS" ]; do
  for SPEC in "$@"; do
    for BUILD in nohopi real real8; do
      case $BUILD in
        nohopi) APK=$NOHOPI; CAP="";;
        real) APK=$REAL; CAP="";;
        real8) APK=$REAL8; CAP="--cap 8388608";;
      esac
      $ADB install -r "$APK" > /dev/null
      echo "== round $r $BUILD $SPEC $(settle)"
      python3 "$HERE/giant.py" "$BUILD" "mb=$SIZES;runs=5;$SPEC" "$OUT" --timeout 900 $CAP | grep -E '"runs":|^\{"label"|PROBLEM' | cut -c1-300
    done
  done
  r=$((r+1))
done
