#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  pair-watch WATCH_IP:PAIR_PORT [WATCH_IP:ADB_PORT]
  pair-watch --help

Examples:
  pair-watch 192.168.1.50:37123
  pair-watch 192.168.1.50:37123 192.168.1.50:42177

On the watch:
  Settings -> Developer options -> Wireless debugging -> Pair new device

The pairing endpoint and normal ADB endpoint are usually different ports.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 2
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb is required (use the Nix app or nix develop)" >&2
  exit 1
fi

pair_endpoint="$1"
connect_endpoint="${2:-}"

if [[ "$pair_endpoint" != *:* ]]; then
  echo "error: pairing endpoint must look like WATCH_IP:PAIR_PORT" >&2
  exit 2
fi

if [[ -n "$connect_endpoint" && "$connect_endpoint" != *:* ]]; then
  echo "error: ADB endpoint must look like WATCH_IP:ADB_PORT" >&2
  exit 2
fi

echo "Pairing with $pair_endpoint"
echo "Enter the six-digit pairing code shown on the watch when adb asks for it."
adb pair "$pair_endpoint"

echo
echo "Pairing succeeded."

if [[ -z "$connect_endpoint" ]]; then
  echo "Next, return to Wireless debugging and note the normal IP address & port."
  echo "Then run:"
  echo "  nix run .#pair-watch -- $pair_endpoint WATCH_IP:ADB_PORT"
  echo
  echo "If the watch is already paired, you can also connect directly with:"
  echo "  nix develop -c adb connect WATCH_IP:ADB_PORT"
  exit 0
fi

echo "Connecting to $connect_endpoint"
adb connect "$connect_endpoint"

if ! adb devices | awk -v target="$connect_endpoint" '$1 == target && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  echo "error: adb did not report $connect_endpoint as an online device" >&2
  echo "Current adb devices:" >&2
  adb devices >&2
  exit 1
fi

echo
echo "Watch paired and connected: $connect_endpoint"
echo "Next:"
echo "  nix run .#install-watch -- $connect_endpoint"
