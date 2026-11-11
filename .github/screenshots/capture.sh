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
# Animations on, to catch a tape being loaded from the rack.
adb shell -n settings put global animator_duration_scale 1
launch
shot unfolded 6
size=$(adb shell -n wm size | tail -1 | grep -o '[0-9]*x[0-9]*')
W=${size%x*}; H=${size#*x}
# The third spine is a local-folder tape, which opens no other app.
# Filmed at full speed, then cut into stills on the runner: nothing competes with the app for
# the emulator's CPU while the sequence plays.
adb shell -n screenrecord --time-limit 8 --bit-rate 8000000 /sdcard/load.mp4 &
rec=$!
sleep 1.5
adb shell -n input tap $((W * 24 / 100)) $((H * 295 / 1000))
wait $rec || true
sleep 1
adb pull /sdcard/load.mp4 shots/load.mp4 </dev/null || true
if [ -s shots/load.mp4 ]; then
  ffmpeg -loglevel error -ss 1.6 -i shots/load.mp4 -vf "fps=8,scale=600:-2" -frames:v 48 shots/load-%02d.jpg </dev/null || true
  rm -f shots/load.mp4
fi
adb shell -n settings put global animator_duration_scale 0

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
