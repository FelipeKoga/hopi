#!/bin/sh
# Closes and reopens the inspector (so its body-view cache is empty) and times opening one
# detail, <times> times, with ui.py. usage: reopen.sh <label> <row text> <times>
HERE=$(cd "$(dirname "$0")" && pwd)
U="python3 $HERE/ui.py"
i=0
while [ $i -lt "$3" ]; do
  $U tap "x back" "Back to requests" >/dev/null 2>&1
  $U tap "x close" "Close Hopi" >/dev/null 2>&1
  sleep 2
  $U tap "x open" "Open Hopi" >/dev/null
  $U tap "$1" "$2" --quiet 2 --max 60 | cut -c1-260
  i=$((i+1))
done
