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
[[ -n "$wear_apk" ]] || wear_apk="$(pick_apk "Wear app" "build/nix/wear-app-debug.apk" "wear-app/build/outputs/apk/debug/wear-app-debug.apk" "wear-app-debug.apk")"

neon_apk="${STARINTEL_NEON_FACE_APK:-}"
[[ -n "$neon_apk" ]] || neon_apk="$(pick_apk "Neon watch face" "build/nix/watchface-neon-debug.apk" "watchface/build/outputs/apk/neon/debug/watchface-neon-debug.apk")"

command_apk="${STARINTEL_COMMAND_FACE_APK:-}"
[[ -n "$command_apk" ]] || command_apk="$(pick_apk "Command watch face" "build/nix/watchface-command-debug.apk" "watchface/build/outputs/apk/command/debug/watchface-command-debug.apk")"

terminal_apk="${STARINTEL_TERMINAL_FACE_APK:-}"
[[ -n "$terminal_apk" ]] || terminal_apk="$(pick_apk "Terminal watch face" "build/nix/watchface-terminal-debug.apk" "watchface/build/outputs/apk/terminal/debug/watchface-terminal-debug.apk")"

for apk in "$wear_apk" "$neon_apk" "$command_apk" "$terminal_apk"; do
  [[ -r "$apk" ]] || { echo "error: APK is not readable: $apk" >&2; exit 1; }
done

adb_cmd=(adb)
[[ -z "$serial" ]] || adb_cmd+=( -s "$serial" )

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: watch is not reachable through adb${serial:+ at $serial}" >&2
  echo "hint: run 'adb devices' and reconnect Wireless debugging if needed" >&2
  exit 1
fi

# Remove the old single-package multiplexed face so the stale picker entry cannot
# shadow the three independently laid-out WFF packages.
"${adb_cmd[@]}" uninstall actor.starintel.watchface >/dev/null 2>&1 || true

echo "[1/6] Installing StarIntel Wear app"
"${adb_cmd[@]}" install -r "$wear_apk" >/dev/null

echo "[2/6] Installing StarIntel Neon"
"${adb_cmd[@]}" install -r "$neon_apk" >/dev/null

echo "[3/6] Installing StarIntel Command"
"${adb_cmd[@]}" install -r "$command_apk" >/dev/null

echo "[4/6] Installing StarIntel Terminal"
"${adb_cmd[@]}" install -r "$terminal_apk" >/dev/null

echo "[5/6] Verifying installed packages"
for package in \
  actor.starintel.wear \
  actor.starintel.watchface.neon \
  actor.starintel.watchface.command \
  actor.starintel.watchface.terminal; do
  "${adb_cmd[@]}" shell pm path "$package" | grep -q '^package:' || {
    echo "error: $package was not found after install" >&2
    exit 1
  }
done

echo "[6/6] Install verified: Neon, Command, and Terminal are separate selectable faces"

if [[ "${STARINTEL_OPEN_WATCH_SETUP:-0}" == "1" ]]; then
  "${adb_cmd[@]}" shell am start -n actor.starintel.wear/.ConfigActivity >/dev/null
else
  echo "Next: open the Wear OS watch-face picker. You should see StarIntel Neon, StarIntel Command, and StarIntel Terminal separately."
fi
