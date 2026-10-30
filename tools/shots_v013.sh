#!/usr/bin/env bash
# v0.1.3 build-station screenshots on the factory emulator (kj-alienware: emulator-5554 / PICO_6_1_kj-alienware_1).
#   tools/shots_v013.sh [OUT_DIR]        -> OUT_DIR/<name>.png (default .scratch/shots-v013)
# Needs the APK installed and the app at `lessons`. Uses qa_shot.sh (debug `state` broadcast + console screenshot).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DEV="${DEV:-emulator-5554}"
export AVD="${AVD:-PICO_6_1_kj-alienware_1}"
export SH="${1:-$ROOT/.scratch/shots-v013}"
mkdir -p "$SH"
PKG=io.embodit.turtlewalk
export PATH="$PATH:C:/Users/Admin/Android/Sdk/platform-tools"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
shot() { PRE="${3:-1.5}" SETTLE="${4:-6}" TWICE=0 bash "$ROOT/tools/qa_shot.sh" "$1" "$2" "${@:5}" 2>&1 | tail -1; }

cmd "lang zh"; sleep 1
shot lessons lessons
shot lessons+menu lessons-menu
shot lessons+exitConfirm lessons-exitConfirm
shot coding@F1 coding-F1 2 6
shot coding+hint@F5 coding-hint-F5 2 6
shot drawn:match@F4 drawn-match-F4 3 6
shot drawn:different@F4 drawn-different-F4 3 6
shot turnDone:along@F4 turnDone-along-F4 2 6
shot walkDone:great@F7 walkDone-great-F7 2 6
shot coding@F10 coding-F10 2 6
shot coding+exitConfirm@L3 coding-exitConfirm-L3 2 6
shot coding@L3 coding-L3 2 6
cmd "state lessons"
echo "done -> $SH"
