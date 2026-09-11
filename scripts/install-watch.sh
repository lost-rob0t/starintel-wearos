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
    "wear-app/build/outputs/apk/debug/wear-app-debug.apk" \
    "wear-app-debug.apk" \
    "starintel-wear-app-debug/wear-app-debug.apk")"
fi

face_apk="${STARINTEL_FACE_APK:-}"
if [[ -z "$face_apk" ]]; then
  face_apk="$(pick_apk "watch face" \
    "watchface/build/outputs/apk/debug/watchface-debug.apk" \
    "watchface-debug.apk" \
    "starintel-watchface-debug/watchface-debug.apk")"
fi

adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

"${adb_cmd[@]}" get-state >/dev/null

echo "Installing StarIntel Wear app: $wear_apk"
"${adb_cmd[@]}" install -r "$wear_apk"

echo "Installing StarIntel watch face: $face_apk"
"${adb_cmd[@]}" install -r "$face_apk"

echo "Opening StarIntel setup on the watch..."
"${adb_cmd[@]}" shell am start -n actor.starintel.wear/.ConfigActivity >/dev/null

echo "Done. Configure the server URL + private API key, then select the StarIntel watch face and add its Tiles."
