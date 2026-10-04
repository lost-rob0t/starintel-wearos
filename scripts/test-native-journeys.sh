#!/usr/bin/env bash
set -euo pipefail
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
  log="build/native-journeys/${package}.log"
  adb shell am instrument -w "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
  if ! grep -Fq 'OK (1 test)' "$log"; then result=1; fi
  timeout 30 adb wait-for-device
  adb pull "/sdcard/Android/data/$package/files/e2e" "build/native-journeys/$package" || result=1
done
adb logcat -d -s AndroidRuntime > build/native-journeys/crashes.log
exit "$result"
