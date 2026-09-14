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

Installs all three independent watch-face packages: Neon, Command, and Terminal.
The stale legacy multiplexed actor.starintel.watchface package is removed so it
cannot shadow the three picker entries.

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

neon_apk="${STARINTEL_NEON_FACE_APK:-}"
if [[ -z "$neon_apk" ]]; then
  neon_apk="$(pick_apk "Neon watch face" \
    "build/nix/watchface-neon-debug.apk" \
    "watchface/build/outputs/apk/neon/debug/watchface-neon-debug.apk" \
    "watchface-neon-debug.apk" \
    "starintel-watchface-neon-debug/watchface-neon-debug.apk")"
fi

command_apk="${STARINTEL_COMMAND_FACE_APK:-}"
if [[ -z "$command_apk" ]]; then
  command_apk="$(pick_apk "Command watch face" \
    "build/nix/watchface-command-debug.apk" \
    "watchface/build/outputs/apk/command/debug/watchface-command-debug.apk" \
    "watchface-command-debug.apk" \
    "starintel-watchface-command-debug/watchface-command-debug.apk")"
fi

terminal_apk="${STARINTEL_TERMINAL_FACE_APK:-}"
if [[ -z "$terminal_apk" ]]; then
  terminal_apk="$(pick_apk "Terminal watch face" \
    "build/nix/watchface-terminal-debug.apk" \
    "watchface/build/outputs/apk/terminal/debug/watchface-terminal-debug.apk" \
    "watchface-terminal-debug.apk" \
    "starintel-watchface-terminal-debug/watchface-terminal-debug.apk")"
fi

for apk in "$wear_apk" "$neon_apk" "$command_apk" "$terminal_apk"; do
  if [[ ! -r "$apk" ]]; then
    echo "error: APK is not readable: $apk" >&2
    exit 1
  fi
done

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

echo "[1/8] Removing stale multiplexed StarIntel face"
"${adb_cmd[@]}" uninstall actor.starintel.watchface >/dev/null 2>&1 || true

echo "[2/8] Installing StarIntel Wear app"
install_wear

echo "[3/8] Installing StarIntel Neon"
"${adb_cmd[@]}" install -r "$neon_apk" >/dev/null

echo "[4/8] Installing StarIntel Command"
"${adb_cmd[@]}" install -r "$command_apk" >/dev/null

echo "[5/8] Installing StarIntel Terminal"
"${adb_cmd[@]}" install -r "$terminal_apk" >/dev/null

echo "[6/8] Verifying installed packages"
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

echo "[7/8] Smoke-testing exported Wear launchers"
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

echo "[8/8] Install verified: Neon, Command, and Terminal are separate selectable faces"

if [[ "${STARINTEL_OPEN_WATCH_SETUP:-0}" == "1" ]]; then
  echo "Opening on-watch fallback setup..."
  "${adb_cmd[@]}" shell am start -n actor.starintel.wear/.ConfigActivity >/dev/null
else
  echo "Next: open the Wear OS watch-face picker. You should see StarIntel Neon, StarIntel Command, and StarIntel Terminal separately."
  echo "Fallback: set STARINTEL_OPEN_WATCH_SETUP=1 to open the watch setup screen after installation."
fi
