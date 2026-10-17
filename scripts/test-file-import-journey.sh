#!/usr/bin/env bash
set -euo pipefail
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required' >&2; exit 1; }
mkdir -p build/file-import
adb install -r quasar-app/build/outputs/apk/debug/quasar-app-debug.apk
adb install -r quasar-app/build/outputs/apk/androidTest/debug/quasar-app-debug-androidTest.apk
adb shell pm clear actor.starintel.quasar
adb shell am instrument -w -e class 'actor.starintel.quasar.FileImportJourneyTest#stageRotateImportAndLeavePartialReceipt' \
  actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee build/file-import/lifecycle.txt
grep -Fq 'OK (1 test)' build/file-import/lifecycle.txt
adb shell am force-stop actor.starintel.quasar
adb shell am instrument -w -e class 'actor.starintel.quasar.FileImportJourneyTest#restartShowsPartialReceiptWithoutMutation' \
  actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee build/file-import/restart.txt
grep -Fq 'OK (1 test)' build/file-import/restart.txt
adb logcat -d -b crash > build/file-import/crashes.txt

adb shell am instrument -w -e class actor.starintel.quasar.MutationReceiptJourneyTest \
  actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee build/file-import/malformed-receipt.txt
grep -Fq 'OK (1 test)' build/file-import/malformed-receipt.txt
