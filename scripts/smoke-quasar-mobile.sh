#!/usr/bin/env bash
# Exercise the native Quasar shell and its embedded StarIntel Edge runtime.
set -euo pipefail

ADB="${STARINTEL_EMULATOR_ADB:-adb}"
SERIAL="${1:-${ANDROID_SERIAL:-emulator-5554}}"
PACKAGE="actor.starintel.quasar"
ACTIVITY="$PACKAGE/.MainActivity"
DEVICE_XML="/sdcard/starintel-quasar-smoke.xml"
WORK_DIR="$(mktemp -d)"
trap 'rm -r "$WORK_DIR"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_text() {
  local expected="$1"
  local file="$2"
  grep -Fq "text=\"$expected\"" "$file" || fail "missing UI text: $expected"
}

[[ "$($ADB -s "$SERIAL" get-state 2>/dev/null)" == "device" ]] || fail "$SERIAL is not an authorized ADB device"
$ADB -s "$SERIAL" shell pm path "$PACKAGE" | grep -q '^package:' || fail "$PACKAGE is not installed"

$ADB -s "$SERIAL" logcat -c
$ADB -s "$SERIAL" shell wm dismiss-keyguard >/dev/null
$ADB -s "$SERIAL" shell am force-stop "$PACKAGE"
$ADB -s "$SERIAL" shell am start -W -n "$ACTIVITY" > "$WORK_DIR/start.txt"

sleep 12
for _ in $(seq 1 15); do
  rm -f "$WORK_DIR/home.xml"
  if $ADB -s "$SERIAL" shell uiautomator dump "$DEVICE_XML" >/dev/null 2>&1 &&
      $ADB -s "$SERIAL" exec-out cat "$DEVICE_XML" > "$WORK_DIR/home.xml" 2>/dev/null &&
      grep -Fq 'text="READY"' "$WORK_DIR/home.xml"; then
    break
  fi
  sleep 2
done

[[ -s "$WORK_DIR/home.xml" ]] || fail "the Quasar accessibility tree was unavailable"
assert_text "Command deck" "$WORK_DIR/home.xml"
assert_text "READY" "$WORK_DIR/home.xml"
assert_text "Common Lisp → Sento actors" "$WORK_DIR/home.xml"
assert_text "Stats" "$WORK_DIR/home.xml"
assert_text "Graphs" "$WORK_DIR/home.xml"
assert_text "Datasets" "$WORK_DIR/home.xml"

for _ in $(seq 1 4); do
  $ADB -s "$SERIAL" shell input swipe 540 1850 540 650 300
done
$ADB -s "$SERIAL" shell uiautomator dump "$DEVICE_XML" >/dev/null
$ADB -s "$SERIAL" exec-out cat "$DEVICE_XML" > "$WORK_DIR/routes.xml"
assert_text "Actors" "$WORK_DIR/routes.xml"
assert_text "Import" "$WORK_DIR/routes.xml"
assert_text "Targets" "$WORK_DIR/routes.xml"
assert_text "Settings" "$WORK_DIR/routes.xml"

ACTOR_NODE="$(grep -o '<node[^>]*text="Actors"[^>]*>' "$WORK_DIR/routes.xml" | head -n 1)"
BOUNDS="$(printf '%s\n' "$ACTOR_NODE" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
[[ -n "$BOUNDS" ]] || fail "could not resolve the Actors route bounds"
read -r X1 Y1 X2 Y2 <<< "$BOUNDS"
$ADB -s "$SERIAL" shell input tap "$(((X1 + X2) / 2))" "$(((Y1 + Y2) / 2))"
sleep 2
$ADB -s "$SERIAL" shell uiautomator dump "$DEVICE_XML" >/dev/null
$ADB -s "$SERIAL" exec-out cat "$DEVICE_XML" > "$WORK_DIR/actors.xml"
assert_text "Actor mesh" "$WORK_DIR/actors.xml"
assert_text "1 trusted actor(s) discovered" "$WORK_DIR/actors.xml"
assert_text "runtime.echo" "$WORK_DIR/actors.xml"
assert_text "Closed registry; request data is never evaluated" "$WORK_DIR/actors.xml"

$ADB -s "$SERIAL" shell dumpsys activity activities > "$WORK_DIR/activities.txt"
grep -Eq "topResumedActivity=.*$PACKAGE/.MainActivity" "$WORK_DIR/activities.txt" || fail "Quasar is not the resumed activity"
$ADB -s "$SERIAL" logcat -d -b crash > "$WORK_DIR/crash.txt"
if grep -Fq "$PACKAGE" "$WORK_DIR/crash.txt"; then
  cat "$WORK_DIR/crash.txt" >&2
  fail "Quasar wrote a crash-buffer entry"
fi

echo "PASS: $PACKAGE on $SERIAL is READY with the embedded Edge/Sento actor catalog"
