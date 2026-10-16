#!/usr/bin/env bash
set -euo pipefail
# Installs/clears fixture state only on a disposable Android emulator.
export ANDROID_SERIAL="$(adb get-serialno)"
[[ "$ANDROID_SERIAL" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
mkdir -p build/workstation-evidence
device_ready() {
  timeout 30 adb wait-for-device
  for attempt in {1..10}; do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then return 0; fi
    sleep 1
  done
  return 1
}
adb install -r quasar-app/build/outputs/apk/debug/quasar-app-debug.apk
adb install -r quasar-app/build/outputs/apk/androidTest/debug/quasar-app-debug-androidTest.apk
trap 'adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0; adb shell cmd uimode night auto' EXIT
# Density 160 makes requested pixels equal dp. The same fixture and dimensions run on both revisions.
result=0
for config in 320x720-1.0-no 360x800-1.3-yes 412x900-2.0-no 720x360-1.0-yes 600x900-1.0-no 360x800-2.0-yes 360x800-1.0-yes-starintel 360x800-1.0-yes-synthwave 360x800-1.0-yes-custom; do
  IFS=- read -r size font night theme <<< "$config"
  theme="${theme:-starintel}"
  device_ready
  adb shell wm density 160
  adb shell wm size "$size"
  device_ready
  adb shell settings put system font_scale "$font"
  adb shell cmd uimode night "$night"
  if [[ "$night" == no ]]; then mode=threebutton; else mode=gestural; fi
  adb shell cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$mode" > "build/workstation-evidence/$config-navigation.log" 2>&1 || true
  adb shell settings get secure navigation_mode >> "build/workstation-evidence/$config-navigation.log"
  if [[ "$size" == 412x900 ]]; then
    adb shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.tall > "build/workstation-evidence/$config-cutout.log" 2>&1 || true
  else
    adb shell cmd overlay disable com.android.internal.display.cutout.emulation.tall > "build/workstation-evidence/$config-cutout.log" 2>&1 || true
  fi
  adb shell cmd overlay list >> "build/workstation-evidence/$config-cutout.log"
  adb shell pm clear actor.starintel.quasar
  adb shell am instrument -w -e fixtureLabel "$config" -e themeFixture "$theme" -e class actor.starintel.quasar.VisualFixtureJourneyTest actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee "build/workstation-evidence/$config.log"
  grep -Fq 'OK (1 test)' "build/workstation-evidence/$config.log" || result=1
  if ! adb pull /sdcard/Android/data/actor.starintel.quasar/files/e2e "build/workstation-evidence/$config"; then
    # Retry only artifact transport, never the fixture or its mutations.
    device_ready
    adb pull /sdcard/Android/data/actor.starintel.quasar/files/e2e "build/workstation-evidence/$config" || result=1
  fi
done
if [[ -f quasar-app/src/main/java/actor/starintel/quasar/WorkspaceState.kt ]]; then
  python3 scripts/test-quasar-process-restoration.py || result=1
fi
device_ready
adb shell getprop > build/workstation-evidence/emulator-properties.txt
adb logcat -d -b crash > build/workstation-evidence/crashes.log
exit "$result"
