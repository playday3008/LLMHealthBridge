#!/bin/sh
# Installs the APK on the running emulator, launches it, shares a reply into it and checks that
# the review screen shows up and nothing crashed. Catches release-only failures such as R8
# breaking startup or JSON parsing.
set -eu

apk=$1
pkg=dev.playday3008.llmhealthbridge
reply='{"request":"smoke","entries":[{"id":"e1","type":"nutrition","meal_type":"lunch","items":[{"name":"Apple","energy_kcal":95,"carbs_g":25}]},{"id":"e2","type":"hydration","volume_ml":250},{"id":"e3","type":"vitals","systolic_mmhg":120,"diastolic_mmhg":80}]}'

adb install -r "$apk"
adb logcat -c

adb shell am start -W -n "$pkg/.MainActivity"
sleep 5
adb shell "am start -W -a android.intent.action.SEND -t text/plain -n $pkg/.MainActivity --es android.intent.extra.TEXT '$reply'"
sleep 5

status=0
if ! adb shell pidof "$pkg" > /dev/null; then
    echo "::error::The app is not running"
    status=1
fi
if adb logcat -d -b crash | grep -q "$pkg"; then
    echo "::error::The app crashed"
    adb logcat -d -b crash
    status=1
fi
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
if ! adb shell cat /sdcard/ui.xml | grep -q 'records to Health Connect'; then
    echo "::error::The shared reply did not open the review screen"
    adb shell cat /sdcard/ui.xml
    status=1
fi
exit $status
