#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  verify-companion-signing PHONE_APK WEAR_APK

Verifies the Android companion and Wear APK are signed by the same certificate.
Wearable Data Layer delivery requires identical package names and signing identity.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

phone_apk="${1:-}"
wear_apk="${2:-}"
if [[ -z "$phone_apk" || -z "$wear_apk" ]]; then
  usage >&2
  exit 2
fi

for apk in "$phone_apk" "$wear_apk"; do
  if [[ ! -r "$apk" ]]; then
    echo "error: APK is not readable: $apk" >&2
    exit 1
  fi
done

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk_root" ]]; then
    local candidate
    candidate="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
    if [[ -n "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  echo "error: apksigner not found; install Android build-tools or enter the Nix dev shell" >&2
  return 1
}

apksigner_bin="$(find_apksigner)"

cert_digest() {
  local apk="$1"
  local output
  output="$("$apksigner_bin" verify --verbose --print-certs "$apk")" || return $?
  printf '%s\n' "$output" >&2
  printf '%s\n' "$output" | awk -F': ' '/^Signer .*certificate SHA-256 digest:/ { print tolower($2) }' | sort -u
}

package_name() {
  local apk="$1"
  local aapt_bin=""
  if command -v aapt >/dev/null 2>&1; then
    aapt_bin="$(command -v aapt)"
  else
    local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$sdk_root" ]]; then
      aapt_bin="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt 2>/dev/null | sort -V | tail -n1 || true)"
    fi
  fi

  if [[ -n "$aapt_bin" ]]; then
    "$aapt_bin" dump badging "$apk" 2>/dev/null \
      | sed -n "s/^package: name='\([^']*\)'.*/\1/p" \
      | head -n1
  fi
}

phone_digest="$(cert_digest "$phone_apk")"
wear_digest="$(cert_digest "$wear_apk")"
if [[ -z "$phone_digest" || -z "$wear_digest" ]]; then
  echo "error: could not read APK signing certificate" >&2
  exit 1
fi

phone_package="$(package_name "$phone_apk")"
wear_package="$(package_name "$wear_apk")"
if [[ -n "$phone_package" && -n "$wear_package" && "$phone_package" != "$wear_package" ]]; then
  echo "error: package names differ" >&2
  echo "  phone: $phone_package" >&2
  echo "  wear:  $wear_package" >&2
  exit 1
fi

if [[ "$phone_digest" != "$wear_digest" ]]; then
  echo "error: phone and Wear APK signing certificates differ" >&2
  echo "  phone: $phone_digest" >&2
  echo "  wear:  $wear_digest" >&2
  echo "Wearable Data Layer will reject messages between these APKs." >&2
  exit 1
fi

printf 'companion signer OK: %s\n' "$phone_digest"
if [[ -n "$phone_package" ]]; then
  printf 'package: %s\n' "$phone_package"
fi
