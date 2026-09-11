#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  pair-android HOST:PAIR_PORT [HOST:ADB_PORT]
  pair-android --connect HOST:ADB_PORT
  pair-android --help

Examples:
  pair-android 192.168.1.50:37123
  pair-android 192.168.1.50:37123 192.168.1.50:42177
  pair-android --connect 192.168.1.50:42177

On Android/Wear OS:
  Developer options -> Wireless debugging -> Pair device with pairing code

The pairing endpoint and normal ADB endpoint are usually different ports.
EOF
}

verify_online() {
  local endpoint="$1"
  if ! adb devices | awk -v target="$endpoint" '$1 == target && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "error: adb did not report $endpoint as an online device" >&2
    echo "Current adb devices:" >&2
    adb devices >&2
    exit 1
  fi
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is required (use the Nix app or nix develop)" >&2
  exit 1
fi

if [[ "${1:-}" == "--connect" ]]; then
  if [[ $# -ne 2 || "$2" != *:* ]]; then
    usage >&2
    exit 2
  fi
  endpoint="$2"
  echo "Connecting to $endpoint"
  adb connect "$endpoint"
  verify_online "$endpoint"
  echo
  echo "Android device connected: $endpoint"
  exit 0
fi

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 2
fi

pair_endpoint="$1"
connect_endpoint="${2:-}"

if [[ "$pair_endpoint" != *:* ]]; then
  echo "error: pairing endpoint must look like HOST:PAIR_PORT" >&2
  exit 2
fi

if [[ -n "$connect_endpoint" && "$connect_endpoint" != *:* ]]; then
  echo "error: ADB endpoint must look like HOST:ADB_PORT" >&2
  exit 2
fi

echo "Pairing with $pair_endpoint"
echo "Enter the six-digit pairing code shown on the device when adb asks for it."
adb pair "$pair_endpoint"

echo
echo "Pairing succeeded."

if [[ -z "$connect_endpoint" ]]; then
  echo "Now note the normal Wireless debugging IP address & port and run:"
  echo "  nix run .#pair-android -- --connect HOST:ADB_PORT"
  exit 0
fi

echo "Connecting to $connect_endpoint"
adb connect "$connect_endpoint"
verify_online "$connect_endpoint"

echo
echo "Android device paired and connected: $connect_endpoint"
echo "Phone companion install:"
echo "  nix run .#install-phone -- $connect_endpoint"
echo "Watch install:"
echo "  nix run .#install-watch -- $connect_endpoint"
