#!/usr/bin/env bash
# QA q1-1 regression: lesson 3 drawn right, edit a block, "turn first", stop, then the debug jumps that crashed.
#   DEV=emulator-5568 tools/repro_q1_1.sh   -> prints FATAL lines (none = pass) and the last states
set -u
DEV=${DEV:-emulator-5568}
PKG=io.embodit.turtlewalk
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; sleep "${2:-3}"; }
adb -s $DEV logcat -c
cmd "state drawn:match@L3" 6
cmd "ui block_2"
cmd "ui value_45"
cmd "ui guess" 6
cmd "ui stop" 4
cmd "state turnAlong:guess@L5" 6
cmd "state turnDone:along@L3" 6
cmd "state turnDone:guess@L5" 6
cmd "state walkReady@L3" 6
cmd "state drawn:match@L3" 6
adb -s $DEV logcat -d | grep -E "FATAL|AndroidRuntime: java|ANR in" | head -5
adb -s $DEV logcat -d -s TurtleWalk:V | grep -E "STATE |UI " | tail -12 | cut -c33-160
