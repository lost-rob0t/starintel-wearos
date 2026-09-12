#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  install-watch [--clean] [WATCH_IP:ADB_PORT]
  install-watch --help

ANDROID_SERIAL may be used instead of the positional serial.

--clean
  If an older StarIntel Wear app is signed by a different debug certificate,
  uninstall only actor.starintel.wear and install the deterministic repository-
  signed build. This clears the watch-side StarIntel configuration/API key, so
  send configuration again from the Android companion afterward.

Set STARINTEL_SKIP_LAUNCH_SMOKE=1 only when you intentionally need to skip the
post-install physical-device launcher crash test.
EOF
}

clean=0
serial_arg=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean)
      clean=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    -* )
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "$serial_arg" ]]; then
        echo "error: unexpected extra argument: $1" >&2
        usage >&2
        exit 2
      fi
      serial_arg="$1"
      shift
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is required (Android platform-tools)" >&2
  exit 1
fi

serial="${ANDROID_SERIAL:-$serial_arg}"

pick_apk() {
  local label="$1"
  shift
  local candidate
  for candidate in "$@"; do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
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

# If a matching phone APK is available, verify the pair before touching the watch.
phone_candidate="${STARINTEL_PHONE_APK:-build/nix/phone-app-debug.apk}"
if [[ -r "$phone_candidate" && -f scripts/verify-companion-signing.sh ]]; then
  bash scripts/verify-companion-signing.sh "$phone_candidate" "$wear_apk"
fi

adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: watch is not reachable through adb${serial:+ at $serial}" >&2
  echo "hint: run 'adb devices' and reconnect Wireless debugging if needed" >&2
  exit 1
fi

install_wear() {
  local output
  if output="$("${adb_cmd[@]}" install -r "$wear_apk" 2>&1)"; then
    return 0
  fi

  if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<<"$output"; then
    if [[ "$clean" != "1" ]]; then
      echo "error: installed Wear client uses an older/different debug signing certificate" >&2
      echo "Phone and Wear must share one signer for Wearable Data Layer." >&2
      echo "rerun with --clean to replace only the StarIntel Wear client:" >&2
      if [[ -n "$serial" ]]; then
        echo "  install-watch --clean $serial" >&2
      else
        echo "  install-watch --clean" >&2
      fi
      echo "Note: this clears the watch-side StarIntel API key/configuration." >&2
      exit 3
    fi

    echo "Old Wear signer detected; performing clean debug reinstall."
    "${adb_cmd[@]}" uninstall actor.starintel.wear >/dev/null 2>&1 || true
    "${adb_cmd[@]}" install "$wear_apk" >/dev/null
    return 0
  fi

  printf '%s\n' "$output" >&2
  return 1
}

echo "[1/5] Installing StarIntel Wear app"
install_wear

echo "[2/5] Installing StarIntel watch face"
"${adb_cmd[@]}" install -r "$face_apk" >/dev/null

echo "[3/5] Verifying installed packages"
"${adb_cmd[@]}" shell pm path actor.starintel.wear | grep -q '^package:' || {
  echo "error: actor.starintel.wear was not found after install" >&2
  exit 1
}
"${adb_cmd[@]}" shell pm path actor.starintel.watchface | grep -q '^package:' || {
  echo "error: actor.starintel.watchface was not found after install" >&2
  exit 1
}

echo "[4/5] Smoke-testing exported Wear launchers"
if [[ "${STARINTEL_SKIP_LAUNCH_SMOKE:-0}" == "1" ]]; then
  echo "Launcher smoke test explicitly skipped."
elif [[ -f scripts/smoke-wear-launchers.sh ]]; then
  if [[ -n "$serial" ]]; then
    bash scripts/smoke-wear-launchers.sh "$serial"
  else
    bash scripts/smoke-wear-launchers.sh
  fi
else
  echo "error: scripts/smoke-wear-launchers.sh is missing" >&2
  exit 1
fi

echo "[5/5] Install verified"

if [[ "${STARINTEL_OPEN_WATCH_SETUP:-0}" == "1" ]]; then
  echo "Opening on-watch fallback setup..."
  "${adb_cmd[@]}" shell am start -n actor.starintel.wear/.ConfigActivity >/dev/null
else
  echo "Next: open StarIntel Companion on the paired Android phone and send configuration to the watch."
  echo "Fallback: set STARINTEL_OPEN_WATCH_SETUP=1 to open the watch setup screen after installation."
fi
