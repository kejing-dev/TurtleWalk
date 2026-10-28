#!/usr/bin/env bash
# Reinstall the built APK on the factory emulator and relaunch in Chinese (build first in PowerShell: gradlew.bat assembleDebug)
# After `adb install -r` the old Stage lingers in full space and a relaunch is refused: force-stop first (Dance Steps finding).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5568}
PKG=io.embodit.turtlewalk
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
cd "$ROOT"
adb -s $DEV shell am force-stop $PKG
adb -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
for i in $(seq 1 40); do adb -s $DEV logcat -d -s TurtleWalk:V | grep -q "STATE lessons" && break; sleep 2; done
MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd 'lang zh'" >/dev/null
adb -s $DEV logcat -d | grep -E "FATAL|ANR in" | tail -3
echo "relaunched"
