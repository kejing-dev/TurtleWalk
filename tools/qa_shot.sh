#!/usr/bin/env bash
# One design state -> one screenshot: tools/qa_shot.sh "<state name>" [FILE] [extra debug cmds...]
#   tools/qa_shot.sh ready                          -> .scratch/shots/ready.png
#   tools/qa_shot.sh "dancing+offFloor:follower" dancing-offFloor-follower
#   tools/qa_shot.sh - walk "walk_x"                (no state change, extra commands only)
# Sends `state <name>` over the debug broadcast, un-throttles the emulator to 60 fps (harness `fast on`), lets
# the panels render, captures a full frame with the emulator console, throttles again. The idle emulator runs
# at ~1 fps and a pooled panel needs several frames after `show` before it is drawn: never capture without `fast on`.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.turtlewalk
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
SH="${SH:-$ROOT/.scratch/shots}"; mkdir -p "$SH/emu"
id="$1"; name="${2:-$1}"; shift; [ $# -gt 0 ] && shift
name="${name//[:+]/-}"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
cap() { before=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; after=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); [ "$after" -gt "$before" ] && break; done; sleep 0.5
  newest=$(ls -1t "$SH/emu"/*.png 2>/dev/null | head -1); [ -n "$newest" ] && mv -f "$newest" "$SH/$1.png" && echo "OK $SH/$1.png" || echo "capture FAILED"; }
adb -s $DEV logcat -c
[ "$id" != "-" ] && cmd "state $id"
for c in "$@"; do cmd "$c"; sleep "${GAP:-0.5}"; done
sleep "${PRE:-1.5}"
$H fast on >/dev/null 2>&1
sleep "${SETTLE:-6}"
cap "$name"
if [ "${TWICE:-1}" = 1 ]; then sleep 1.5; cap "$name"; fi
$H fast off >/dev/null 2>&1
adb -s $DEV logcat -d -s DanceSteps:V 2>/dev/null | grep -E "STATE |FATAL|ANR" | tail -1 | cut -c1-260
