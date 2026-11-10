#!/usr/bin/env bash
# Run inside the emulator step: installs the debug build and photographs each posture.
set -u
mkdir -p shots
apk=$(ls app/build/outputs/apk/debug/*.apk | head -n 1)
adb install -r "$apk"
adb shell pm grant dev.foldman android.permission.POST_NOTIFICATIONS || true

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
launch() { adb shell am start -W -n dev.foldman/.MainActivity; }
seed() {
  adb shell run-as dev.foldman mkdir -p files/cases
  adb shell "run-as dev.foldman sh -c 'cat > files/cases/cases.json'" < .github/screenshots/cases.json
}

# Folded: a tall, narrow cover screen.
adb shell wm size 1080x2520
adb shell wm density 420
launch
shot cover-empty 8
adb shell am force-stop dev.foldman
seed
launch
shot cover 6
# The TAPES button, top left, opens the shelf.
adb shell input tap 160 215
shot cover-shelf 4
adb shell input keyevent KEYCODE_BACK

# Unfolded: the wide inner screen.
adb shell am force-stop dev.foldman
adb shell wm size 2184x1968
adb shell wm density 420
launch
shot unfolded 6

# Unfolded and turned sideways.
adb shell am force-stop dev.foldman
adb shell wm size 1968x2184
launch
shot unfolded-portrait 6
ls -la shots
