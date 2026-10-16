#!/usr/bin/env bash
set -euo pipefail
# Installs/clears fixture state only on a disposable Android emulator.
export ANDROID_SERIAL="$(adb get-serialno)"
[[ "$ANDROID_SERIAL" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
mkdir -p build/workstation-evidence
adb install -r quasar-app/build/outputs/apk/debug/quasar-app-debug.apk
adb install -r quasar-app/build/outputs/apk/androidTest/debug/quasar-app-debug-androidTest.apk
trap 'adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0; adb shell cmd uimode night auto' EXIT
# Density 160 makes requested pixels equal dp. The same fixture and dimensions run on both revisions.
for config in 320x720-1.0-no 360x800-1.3-yes 412x900-2.0-no 720x360-1.0-yes 600x900-1.0-no 360x800-2.0-yes; do
  IFS=- read -r size font night <<< "$config"
  adb shell wm density 160
  adb shell wm size "$size"
  adb shell settings put system font_scale "$font"
  adb shell cmd uimode night "$night"
  adb shell pm clear actor.starintel.quasar
  adb shell am instrument -w -e fixtureLabel "$config" -e class actor.starintel.quasar.VisualFixtureJourneyTest actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee "build/workstation-evidence/$config.log"
  grep -Fq 'OK (1 test)' "build/workstation-evidence/$config.log"
  adb pull /sdcard/Android/data/actor.starintel.quasar/files/e2e "build/workstation-evidence/$config"
done
adb shell getprop > build/workstation-evidence/emulator-properties.txt
adb logcat -d -b crash > build/workstation-evidence/crashes.log
