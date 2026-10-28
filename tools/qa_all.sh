#!/usr/bin/env bash
# Every QA screenshot of the design package in one go: tools/qa_all.sh [OUT_DIR]
# Each line: "<state name>|<file name>|PRE|SETTLE|extra cmds..." (see qa_shot.sh)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
OUT="${1:-$ROOT/.scratch/shots}"
export SH="$OUT"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.turtlewalk
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
while IFS='|' read -r state file pre settle extra; do
  [ -z "$state" ] && continue
  [[ "$state" == \#* ]] && continue
  echo "== $state -> $file"
  PRE="${pre:-1.5}" SETTLE="${settle:-6}" bash "$ROOT/tools/qa_shot.sh" "$state" "$file" $extra </dev/null 2>&1 | tail -2
done <<'LIST'
starting|starting|1.5|5
picker|picker|1.5|5
picker+scanning|picker-scanning|1.5|5
picker+menu|picker-menu|1.5|5
placing|placing|2|6
placing+cramped|placing-cramped|2|6
placing+front|placing-front|2|6
ready|ready|2|6
ready+coach|ready-coach|2|6
ready+notOnStart|ready-notOnStart|2|6
ready:follower|ready-follower|2|6
ready+preview|ready-preview|1|4
countIn|countIn|0|1.5
dancing|dancing|3|6
dancing+offFloor|dancing-offFloor|3|6
paused|paused|1.5|6
paused+lost|paused-lost|1.5|6
LIST
cmd found
sleep 1
PRE=1.5 SETTLE=6 bash "$ROOT/tools/qa_shot.sh" summary summary 2>&1 | tail -2
PRE=1.5 SETTLE=6 bash "$ROOT/tools/qa_shot.sh" blocked blocked 2>&1 | tail -2
cmd "state picker"
echo "ALL DONE"
