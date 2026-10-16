#!/usr/bin/env bash
set -euo pipefail
# This installs only on the CI/disposable emulator; never a physical phone.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required' >&2; exit 1; }
mkdir -p build/draft-storage
adb install -r quasar-app/build/outputs/apk/debug/quasar-app-debug.apk
adb install -r quasar-app/build/outputs/apk/androidTest/debug/quasar-app-debug-androidTest.apk
adb shell am instrument -w -e class actor.starintel.quasar.WorkspaceDraftsTest \
  actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner \
  | tee build/draft-storage/instrumentation.txt
grep -Fq 'OK (4 tests)' build/draft-storage/instrumentation.txt
