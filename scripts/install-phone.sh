#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  install-phone [--clean] PHONE_IP:ADB_PORT
  install-phone --help

ANDROID_SERIAL may be used instead of the positional serial.

--clean
  If an older StarIntel Companion is signed by a different debug certificate,
  uninstall it and install the deterministic repository-signed build. This clears
  the companion app's local preferences; the API key is never stored on the phone.
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

serial="${ANDROID_SERIAL:-$serial_arg}"
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

# If a matching Wear APK is present locally, refuse to install a mismatched pair.
wear_candidate="${STARINTEL_WEAR_APK:-build/nix/wear-app-debug.apk}"
if [[ -r "$wear_candidate" && -f scripts/verify-companion-signing.sh ]]; then
  bash scripts/verify-companion-signing.sh "$phone_apk" "$wear_candidate"
fi

adb_cmd=(adb -s "$serial")
if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: phone is not reachable through adb at $serial" >&2
  echo "hint: run 'nix run .#pair-android -- --connect $serial'" >&2
  exit 1
fi

install_apk() {
  local output
  if output="$("${adb_cmd[@]}" install -r "$phone_apk" 2>&1)"; then
    return 0
  fi

  if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<<"$output"; then
    if [[ "$clean" != "1" ]]; then
      echo "error: installed companion uses an older/different debug signing certificate" >&2
      echo "The phone and Wear APK must share one signer for Wearable Data Layer." >&2
      echo "rerun with --clean to replace the old debug install:" >&2
      echo "  install-phone --clean $serial" >&2
      exit 3
    fi

    echo "Old companion signer detected; performing clean debug reinstall."
    "${adb_cmd[@]}" uninstall actor.starintel.wear >/dev/null 2>&1 || true
    "${adb_cmd[@]}" install "$phone_apk" >/dev/null
    return 0
  fi

  printf '%s\n' "$output" >&2
  return 1
}

echo "[1/2] Installing StarIntel Companion"
install_apk

echo "[2/2] Verifying installed package"
"${adb_cmd[@]}" shell pm path actor.starintel.wear | grep -q '^package:' || {
  echo "error: actor.starintel.wear was not found after install" >&2
  exit 1
}

echo "StarIntel Companion installed and verified on $serial"
echo "Next: open StarIntel Companion on the phone."
