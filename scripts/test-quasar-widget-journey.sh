#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/widget-journey
git rev-parse HEAD > build/widget-journey/commit.txt
adb devices -l > build/widget-journey/devices.txt
adb shell getprop ro.build.fingerprint > build/widget-journey/device-build.txt
while IFS= read -r apk; do adb install -r "$apk"; done < <(find widget-apks -name '*.apk' | sort)
adb shell appwidget grantbind --package actor.starintel.quasar --user 0 > build/widget-journey/bind-permission.txt
adb logcat -c
set +e
adb shell am instrument -w -e class actor.starintel.quasar.workflows.WorkflowWidgetJourneyTest actor.starintel.quasar.test/androidx.test.runner.AndroidJUnitRunner | tee build/widget-journey/instrumentation.txt
adb pull /sdcard/Android/data/actor.starintel.quasar/files/widget-evidence build/widget-journey/
adb logcat -d > build/widget-journey/logcat.txt
adb shell dumpsys appwidget > build/widget-journey/appwidget.txt
set -e
grep -Fq 'OK (3 tests)' build/widget-journey/instrumentation.txt
for state in widget-recent-minimum-250x360 widget-expanded-three-workflows widget-command-pending \
  widget-retained-control-rejected widget-running-stop-only widget-stale widget-future \
  widget-missing widget-offline widget-failure status-resumed-alpha status-new-intent-beta status-all-workflows; do
  test -s "build/widget-journey/widget-evidence/$state.png"
  test -s "build/widget-journey/widget-evidence/$state.xml"
done
