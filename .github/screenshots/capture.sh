#!/usr/bin/env bash
# Run inside the emulator step: installs the debug build and photographs each posture.
set -u
mkdir -p shots
exec > >(tee shots/log.txt) 2>&1
set -x
apk=$(ls app/build/outputs/apk/debug/*.apk | head -n 1)
adb install -r "$apk"
adb shell -n pm grant dev.foldman android.permission.POST_NOTIFICATIONS || true

shot() {
  sleep "${2:-3}"
  adb shell -n screencap -p /sdcard/shot.png
  adb pull /sdcard/shot.png "shots/$1.png" </dev/null
}
launch() { adb shell -n am start -W -n dev.foldman/.MainActivity; }
seed() {
  local b64
  b64=$(base64 -w0 .github/screenshots/cases.json)
  adb shell -n run-as dev.foldman mkdir -p files/cases
  adb shell -n "run-as dev.foldman sh -c 'echo $b64 | base64 -d > files/cases/cases.json'"
  adb shell -n run-as dev.foldman ls -la files/cases
}

# Folded: a tall, narrow cover screen.
adb shell -n wm size 1080x2520
adb shell -n wm density 420
launch
shot cover-empty 8
adb shell -n am force-stop dev.foldman
seed
launch
shot cover 6
adb shell -n dumpsys activity activities | grep -i -m3 "ResumedActivity\|mFocusedApp" || true
# The TAPES button, top left, opens the shelf.
adb shell -n input tap 115 95
shot cover-shelf 4
adb shell -n input keyevent KEYCODE_BACK

# Unfolded: the wide inner screen.
adb shell -n am force-stop dev.foldman
adb shell -n wm size 2184x1968
adb shell -n wm density 420
launch
shot unfolded 6

# Unfolded and turned sideways.
adb shell -n am force-stop dev.foldman
adb shell -n wm size 1968x2184
launch
shot unfolded-portrait 6

# The second skin, set straight in the app's settings.
adb shell -n am force-stop dev.foldman
prefs=$(printf "%s" "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"skin\">SPORT</string></map>" | base64 -w0)
adb shell -n run-as dev.foldman mkdir -p shared_prefs
adb shell -n "run-as dev.foldman sh -c 'echo $prefs | base64 -d > shared_prefs/foldman.xml'"
launch
shot sport-unfolded 6
adb shell -n am force-stop dev.foldman
adb shell -n wm size 1080x2520
launch
shot sport-cover 6
adb shell -n input tap 115 95
shot sport-cover-shelf 4
ls -la shots
