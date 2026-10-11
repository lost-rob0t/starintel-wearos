#!/usr/bin/env bash
set -euo pipefail
out=build/collector-mission-journey
mkdir -p "$out"
git rev-parse HEAD > "$out/commit.txt"
adb devices -l > "$out/devices.txt"
mapfile -t serials < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
if [[ ${#serials[@]} != 1 || ! ${serials[0]} =~ ^emulator-[0-9]+$ ]]; then
  echo 'Exactly one isolated emulator is required; physical devices are forbidden.' >&2
  exit 1
fi
export ANDROID_SERIAL="${serials[0]}"
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
adb shell getprop ro.build.fingerprint > "$out/device-build.txt"
adb shell getprop ro.product.cpu.abi > "$out/device-abi.txt"
grep -q x86_64 "$out/device-abi.txt"
mapfile -t apks < <(find collector-apks -name '*.apk' | sort)
[[ ${#apks[@]} == 2 ]]
for apk in "${apks[@]}"; do adb install -r "$apk"; done
adb shell pm clear actor.starintel.collector
for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
  adb shell pm grant actor.starintel.collector "android.permission.$permission"
done
# Neither RECORD_AUDIO nor CAMERA is granted: this fixture never records media.
adb logcat -c
set +e
adb shell am instrument -w -e class actor.starintel.collector.MissionAnnotationStoreTest,actor.starintel.collector.MissionAnnotationJourneyTest actor.starintel.collector.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt"
status=${PIPESTATUS[0]}
adb pull /sdcard/Android/data/actor.starintel.collector/files/mission-annotations-evidence "$out/"
adb logcat -d > "$out/logcat.txt"
adb logcat -d -b crash > "$out/crashes.txt"
adb shell dumpsys activity services actor.starintel.collector > "$out/services.txt"
set -e
# Synthetic fixture PNG/XML only; standard artifact remains the primary evidence.
if [[ -d "$out/mission-annotations-evidence" ]]; then
  tar -czf "$out/collector-screens.tar.gz" -C "$out" mission-annotations-evidence
  test "$(stat -c%s "$out/collector-screens.tar.gz")" -le 8388608
  echo COLLECTOR_EVIDENCE_BASE64_BEGIN
  base64 "$out/collector-screens.tar.gz"
  echo COLLECTOR_EVIDENCE_BASE64_END
fi
[[ $status == 0 ]]
grep -Fxq 'OK (5 tests)' <(tr -d '\r' < "$out/instrumentation.txt")
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$out/instrumentation.txt"
for state in synthetic-open-interval synthetic-ended-interval service-stopped-interval; do
  test -s "$out/mission-annotations-evidence/$state.png"
  test -s "$out/mission-annotations-evidence/$state.xml"
done
