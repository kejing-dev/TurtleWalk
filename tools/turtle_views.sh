#!/usr/bin/env bash
# Taste D25 gate shots: the turtle on the route's start marker from the front, the side and above.
#   tools/turtle_views.sh [OUT_DIR]      (lesson 9 starts at the field centre; a 1 m field 0.9 m ahead keeps it close)
# Camera pitch via tools/pitch.sh (right-button drags); the pose line tells the real pitch, so we iterate to a target.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DEV="${DEV:-emulator-5554}"
export AVD="${AVD:-PICO_6_1_kj-alienware_1}"
export SH="${1:-$ROOT/.scratch/shots-v013}"
mkdir -p "$SH"
PKG=io.embodit.turtlewalk
export PATH="$PATH:C:/Users/Admin/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
shot() { PRE="${2:-1.5}" SETTLE="${3:-5}" TWICE=0 bash "$ROOT/tools/qa_shot.sh" - "$1" 2>&1 | tail -1; }
# current pitch in degrees (positive = looking down) from the pose line
pitch_now() { $H pose 2>&1 | grep -o "fwd=([^)]*)" | tr -d 'fwd=()' | awk -F, '{ printf "%.1f", -atan2($2, -$3) * 180 / 3.14159265 }'; }
# turn the camera until the pitch is within 4 degrees of $1
pitch_to() {
  local target=$1 i cur d
  for i in 1 2 3 4 5 6; do
    cur=$(pitch_now); d=$(python -c "print(round($target - $cur))")
    [ "${d#-}" -le 4 ] && break
    bash "$ROOT/tools/pitch.sh" "$d" >/dev/null 2>&1
  done
  echo "pitch now $(pitch_now) (target $target)"
}
cmd "state coding@L9"; sleep 1
cmd "place 1"; sleep 2
pitch_to 60
shot turtle-front 1.5 5
cmd "program R90"; sleep 0.5
cmd "run instant"; sleep 2
shot turtle-side 1.5 5
pitch_to 85
shot turtle-top 1.5 5
cmd "program L90"; sleep 0.5
cmd "run instant"; sleep 2
shot turtle-side-left 1.5 5
pitch_to 0
cmd "state lessons"
echo "done -> $SH"
