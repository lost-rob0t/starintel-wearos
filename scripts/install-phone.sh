#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  install-phone PHONE_IP:ADB_PORT
  install-phone --help

ANDROID_SERIAL may be used instead of the positional serial.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

serial="${ANDROID_SERIAL:-${1:-}}"
if [[ -z "$serial" ]]; then
  usage >&2
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is required (use the Nix app or nix develop)" >&2
  exit 1
fi

pick_apk() {
  local candidate
  for candidate in \
    "${STARINTEL_PHONE_APK:-}" \
    "build/nix/phone-app-debug.apk" \
    "phone-app/build/outputs/apk/debug/phone-app-debug.apk" \
    "phone-app-debug.apk" \
    "starintel-phone-app-debug/phone-app-debug.apk"; do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "error: could not find Android companion APK" >&2
  echo "hint: run 'nix run .#build-phone' or 'nix run .#build-all' first" >&2
  return 1
}

phone_apk="$(pick_apk)"
if [[ ! -r "$phone_apk" ]]; then
  echo "error: APK is not readable: $phone_apk" >&2
  exit 1
fi

adb_cmd=(adb -s "$serial")
if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: phone is not reachable through adb at $serial" >&2
  echo "hint: run 'nix run .#pair-android -- --connect $serial'" >&2
  exit 1
fi

echo "[1/2] Installing StarIntel Companion"
"${adb_cmd[@]}" install -r "$phone_apk" >/dev/null

echo "[2/2] Verifying installed package"
"${adb_cmd[@]}" shell pm path actor.starintel.wear | grep -q '^package:' || {
  echo "error: actor.starintel.wear was not found after install" >&2
  exit 1
}

echo "StarIntel Companion installed and verified on $serial"
echo "Next: open StarIntel Companion on the phone."
