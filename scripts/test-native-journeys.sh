#!/usr/bin/env bash
set -euo pipefail
# This script resets fixture state and must never target a physical device.
export ANDROID_SERIAL="$(adb get-serialno)"
[[ "$ANDROID_SERIAL" == emulator-* ]]
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
mkdir -p build/native-journeys
adb logcat -v threadtime > build/native-journeys/logcat.log &
logcat_pid=$!
trap 'kill "$logcat_pid" 2>/dev/null || true' EXIT
for apk in apks/phone-app-debug.apk apks/quasar-app-debug.apk apks/operator-app-debug.apk; do
  adb install -r "$apk"
done
while IFS= read -r apk; do adb install -r "$apk"; done < <(find journey-tests -name '*.apk' | sort)
result=0
for package in actor.starintel.operator actor.starintel.quasar actor.starintel.wear; do
  timeout 30 adb wait-for-device
  [[ "$(adb shell pm clear "$package" | tr -d '\r')" == Success ]]
  rm -rf "build/native-journeys/$package"
  log="build/native-journeys/${package}.log"
  case "$package" in
    actor.starintel.operator) journey=actor.starintel.operator.OperatorJourneyTest ;;
    actor.starintel.quasar) journey=actor.starintel.quasar.OperatorJourneyTest ;;
    actor.starintel.wear) journey=actor.starintel.mobile.OperatorJourneyTest ;;
  esac
  adb shell am instrument -w -e class "$journey" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
  if ! grep -Fq 'OK (1 test)' "$log"; then result=1; fi
  timeout 30 adb wait-for-device
  adb pull "/sdcard/Android/data/$package/files/e2e" "build/native-journeys/$package" || result=1
done
# Exercise the real editor filter, spans and recreation in its own bounded journey.
editor_log="build/native-journeys/logic-editor.log"
adb shell am instrument -w -e class actor.starintel.quasar.ide.LogicEditorJourneyTest \
  actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee "$editor_log"
if ! grep -Fq 'OK (1 test)' "$editor_log"; then result=1; fi
# Keep crash diagnostics readable even when artifact downloads are unavailable.
adb logcat -d -b crash | tee build/native-journeys/crashes.log
# Only explicitly named synthetic fixture screens enter the bounded log fallback.
# The normal artifact remains authoritative, with full instrumentation diagnostics.
screens=()
for entry in \
  actor.starintel.operator/operator-overview \
  actor.starintel.operator/operator-actors \
  actor.starintel.operator/operator-fleet \
  actor.starintel.wear/companion-home \
  actor.starintel.wear/companion-connection \
  actor.starintel.wear/companion-packages \
  actor.starintel.quasar/quasar-home \
  actor.starintel.quasar/quasar-actors \
  actor.starintel.quasar/quasar-map-response-Settings-200 \
  actor.starintel.quasar/quasar-map-response-recreate-503 \
  actor.starintel.quasar/quasar-map-response-Field-200 \
  actor.starintel.quasar/quasar-map-response-Field-503; do
  for extension in png xml; do
    file="$entry.$extension"
    if [[ -f "build/native-journeys/$file" && ! -L "build/native-journeys/$file" && -s "build/native-journeys/$file" ]]; then
      screens+=("$file")
    else
      echo "Missing fixture screen: $file" >&2
      result=1
    fi
  done
done
if (( ${#screens[@]} > 0 )); then
  tar -czf build/native-journeys/native-screens.tar.gz -C build/native-journeys "${screens[@]}"
  test "$(stat -c%s build/native-journeys/native-screens.tar.gz)" -le 8388608
  git rev-parse HEAD
  sha256sum build/native-journeys/native-screens.tar.gz
  echo NATIVE_EVIDENCE_BASE64_BEGIN
  base64 build/native-journeys/native-screens.tar.gz
  echo NATIVE_EVIDENCE_BASE64_END
fi
exit "$result"
