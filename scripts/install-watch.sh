#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is required (Android platform-tools)" >&2
  exit 1
fi

serial="${ANDROID_SERIAL:-${1:-}}"

pick_apk() {
  local label="$1"
  shift
  local candidate
  for candidate in "$@"; do
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "error: could not find ${label} APK" >&2
  return 1
}

wear_apk="${STARINTEL_WEAR_APK:-}"
if [[ -z "$wear_apk" ]]; then
  wear_apk="$(pick_apk "Wear app" \
    "build/nix/wear-app-debug.apk" \
    "wear-app/build/outputs/apk/debug/wear-app-debug.apk" \
    "wear-app-debug.apk" \
    "starintel-wear-app-debug/wear-app-debug.apk")"
fi

face_apk="${STARINTEL_FACE_APK:-}"
if [[ -z "$face_apk" ]]; then
  face_apk="$(pick_apk "watch face" \
    "build/nix/watchface-debug.apk" \
    "watchface/build/outputs/apk/debug/watchface-debug.apk" \
    "watchface-debug.apk" \
    "starintel-watchface-debug/watchface-debug.apk")"
fi

for apk in "$wear_apk" "$face_apk"; do
  if [[ ! -r "$apk" ]]; then
    echo "error: APK is not readable: $apk" >&2
    exit 1
  fi
done

adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: watch is not reachable through adb${serial:+ at $serial}" >&2
  echo "hint: run 'adb devices' and reconnect Wireless debugging if needed" >&2
  exit 1
fi

install_flags=(-r)
if [[ "${STARINTEL_INSTALL_ALLOW_DOWNGRADE:-0}" == "1" ]]; then
  install_flags+=(-d)
fi

echo "[1/4] Installing StarIntel Wear app"
"${adb_cmd[@]}" install "${install_flags[@]}" "$wear_apk" >/dev/null

echo "[2/4] Installing StarIntel watch face"
"${adb_cmd[@]}" install "${install_flags[@]}" "$face_apk" >/dev/null

echo "[3/4] Verifying installed packages"
"${adb_cmd[@]}" shell pm path actor.starintel.wear | grep -q '^package:' || {
  echo "error: actor.starintel.wear was not found after install" >&2
  exit 1
}
"${adb_cmd[@]}" shell pm path actor.starintel.watchface | grep -q '^package:' || {
  echo "error: actor.starintel.watchface was not found after install" >&2
  exit 1
}

echo "[4/4] Install verified"

if [[ "${STARINTEL_OPEN_WATCH_SETUP:-0}" == "1" ]]; then
  echo "Opening on-watch fallback setup..."
  "${adb_cmd[@]}" shell am start -n actor.starintel.wear/.ConfigActivity >/dev/null
else
  echo "Next: open StarIntel Companion on the paired Android phone and send configuration to the watch."
  echo "Fallback: set STARINTEL_OPEN_WATCH_SETUP=1 to open the watch setup screen after installation."
fi
