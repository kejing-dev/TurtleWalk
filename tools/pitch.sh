#!/usr/bin/env bash
# Pitch the emulator camera by right-button drags: tools/pitch.sh DEG   (positive = look down)
# 0.0537 deg per framebuffer px (theater-planner QA measurement); the camera_rotation_button must be On
# (tools/harness/uia_cam.ps1 set camera_rotation_button on). Reversible: pitch.sh -DEG turns back.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
deg=${1:-0}
px=$(python -c "import sys; print(int(round(float(sys.argv[1]) / 0.0537)))" "$deg")
s=1; [ "$px" -lt 0 ] && s=-1 && px=$(( -px ))
while [ "$px" -gt 0 ]; do d=$px; [ $d -gt 700 ] && d=700
  # drag down = look down
  $H drag 1080 $((1079 - s*d/2)) 1080 $((1079 + s*d/2)) -Button right >/dev/null 2>&1
  px=$(( px - d )); done
sleep 1
$H pose 2>&1 | tail -1 | grep -o "fwd=([^)]*)"
