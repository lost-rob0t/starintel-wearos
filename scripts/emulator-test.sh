#!/usr/bin/env bash
set -euo pipefail

[[ -n "${STARINTEL_EMULATOR_SDK:-}" ]] || {
  echo "error: launch via: nix run .#emulator-test" >&2
  exit 2
}

starintel_test_state="$(mktemp -d /tmp/starintel-wearos-emulator-test.XXXXXX)"
starintel_avd_home="$starintel_test_state/avd"
starintel_android_user="$starintel_test_state/android-user"
starintel_avd_name="starintel-test"
starintel_emulator_port="${STARINTEL_EMULATOR_TEST_PORT:-5584}"
starintel_serial="emulator-$starintel_emulator_port"
starintel_adb="${STARINTEL_EMULATOR_ADB:-adb}"
starintel_build_sdk="${STARINTEL_BUILD_SDK:-${ANDROID_HOME:-}}"
starintel_emulator_pid=""

cleanup() {
  if [[ -n "$starintel_emulator_pid" ]]; then
    "$starintel_adb" -s "$starintel_serial" emu kill >/dev/null 2>&1 || true
    kill "$starintel_emulator_pid" >/dev/null 2>&1 || true
    wait "$starintel_emulator_pid" >/dev/null 2>&1 || true
  fi
  case "$starintel_test_state" in
    /tmp/starintel-wearos-emulator-test.*) rm -rf -- "$starintel_test_state" ;;
    *) echo "refusing to remove unexpected test state: $starintel_test_state" >&2 ;;
  esac
}
trap cleanup EXIT INT TERM

if "$starintel_adb" -s "$starintel_serial" get-state >/dev/null 2>&1; then
  echo "error: $starintel_serial is already in use" >&2
  exit 2
fi

mkdir -p "$starintel_avd_home" "$starintel_android_user" build/emulator-test
export ANDROID_AVD_HOME="$starintel_avd_home"
export ANDROID_USER_HOME="$starintel_android_user"
export ANDROID_PREFS_ROOT="$starintel_android_user"
export ANDROID_HOME="$STARINTEL_EMULATOR_SDK"
export ANDROID_SDK_ROOT="$STARINTEL_EMULATOR_SDK"

starintel_avd_directory="$starintel_avd_home/$starintel_avd_name.avd"
mkdir -p "$starintel_avd_directory"
printf '%s\n' \
  'PlayStore.enabled=no' \
  'abi.type=x86_64' \
  "avd.id=$starintel_avd_name" \
  'avd.ini.encoding=UTF-8' \
  "avd.name=$starintel_avd_name" \
  'disk.dataPartition.size=8G' \
  'fastboot.forceColdBoot=yes' \
  'hw.cpu.arch=x86_64' \
  'hw.cpu.ncore=4' \
  'hw.gpu.enabled=yes' \
  'hw.gpu.mode=swiftshader_indirect' \
  'hw.keyboard=yes' \
  'hw.lcd.density=420' \
  'hw.lcd.height=2400' \
  'hw.lcd.width=1080' \
  'hw.ramSize=3072' \
  'image.sysdir.1=system-images/android-36/google_apis/x86_64/' \
  'tag.display=Google APIs' \
  'tag.id=google_apis' \
  'target=android-36' \
  >"$starintel_avd_directory/config.ini"
printf '%s\n' \
  'avd.ini.encoding=UTF-8' \
  "path=$starintel_avd_directory" \
  'target=android-36' \
  >"$starintel_avd_home/$starintel_avd_name.ini"

env -u 'BASH_FUNC_git-sync%%' \
  "$STARINTEL_EMULATOR_SDK/emulator/emulator" "@$starintel_avd_name" \
  -port "$starintel_emulator_port" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -no-metrics \
  -gpu swiftshader_indirect \
  >build/emulator-test/emulator.log 2>&1 &
starintel_emulator_pid=$!

for starintel_attempt in $(seq 1 180); do
  if ! kill -0 "$starintel_emulator_pid" >/dev/null 2>&1; then
    echo "error: emulator exited during boot; see build/emulator-test/emulator.log" >&2
    exit 1
  fi
  starintel_booted="$("$starintel_adb" -s "$starintel_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$starintel_booted" == "1" ]] && break
  if [[ "$starintel_attempt" == "180" ]]; then
    echo "error: emulator boot timeout" >&2
    exit 1
  fi
  sleep 2
done

"$starintel_adb" -s "$starintel_serial" shell svc wifi disable
"$starintel_adb" -s "$starintel_serial" shell svc data disable

export ANDROID_HOME="$starintel_build_sdk"
export ANDROID_SDK_ROOT="$starintel_build_sdk"
unset ANDROID_AVD_HOME ANDROID_USER_HOME ANDROID_PREFS_ROOT

gradle --no-daemon -Pstarintel.android.abi=x86_64 \
  :collector-app:testDebugUnitTest \
  :collector-app:assembleDebug \
  :collector-app:assembleDebugAndroidTest \
  :starintel-android:assembleDebugAndroidTest

starintel_collector_apk="collector-app/build/outputs/apk/debug/collector-app-debug.apk"
starintel_collector_test_apk="collector-app/build/outputs/apk/androidTest/debug/collector-app-debug-androidTest.apk"
starintel_runtime_test_apk="starintel-android/build/outputs/apk/androidTest/debug/starintel-android-debug-androidTest.apk"

for starintel_apk in \
  "$starintel_collector_apk" \
  "$starintel_collector_test_apk" \
  "$starintel_runtime_test_apk"; do
  [[ -f "$starintel_apk" ]] || {
    echo "error: missing test artifact $starintel_apk" >&2
    exit 1
  }
  "$starintel_adb" -s "$starintel_serial" install -r -g "$starintel_apk" >/dev/null
done

"$starintel_adb" -s "$starintel_serial" shell pm path actor.starintel.collector >/dev/null
"$starintel_adb" -s "$starintel_serial" shell pm path actor.starintel.collector.test >/dev/null
"$starintel_adb" -s "$starintel_serial" shell pm path actor.starintel.android.test >/dev/null

"$starintel_adb" -s "$starintel_serial" shell am instrument -w -r \
  -e class actor.starintel.collector.WigleSqliteImporterLargeTest \
  actor.starintel.collector.test/androidx.test.runner.AndroidJUnitRunner \
  | tee build/emulator-test/wigle-instrumentation.log
grep -q 'OK (2 tests)' build/emulator-test/wigle-instrumentation.log

"$starintel_adb" -s "$starintel_serial" shell am instrument -w -r \
  -e class actor.starintel.android.lisp.EdgeRuntimeInstrumentedTest \
  actor.starintel.android.test/androidx.test.runner.AndroidJUnitRunner \
  | tee build/emulator-test/edge-runtime-instrumentation.log
grep -q 'OK (1 test)' build/emulator-test/edge-runtime-instrumentation.log

"$starintel_adb" -s "$starintel_serial" shell am start -W \
  -n actor.starintel.collector/.CollectorMissionActivity \
  >build/emulator-test/collector-launch.log
grep -q 'Status: ok' build/emulator-test/collector-launch.log

echo "PASS: $starintel_serial; mocked wireless JVM tests; v3-v4 migration; 325000-row WiGLE import/replay; Edge ECL/Sento ART round-trip; collector launch"
