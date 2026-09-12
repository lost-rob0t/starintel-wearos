#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  smoke-wear-launchers [WATCH_IP:ADB_PORT]

Starts every exported StarIntel Wear launcher Activity on the connected watch and
fails if Android cannot start it, the app process dies, or logcat records a fatal
exception for actor.starintel.wear.

ANDROID_SERIAL may be used instead of the positional serial.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

serial="${ANDROID_SERIAL:-${1:-}}"
adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

if ! "${adb_cmd[@]}" get-state >/dev/null 2>&1; then
  echo "error: watch is not reachable through adb${serial:+ at $serial}" >&2
  exit 1
fi

launchers=(
  actor.starintel.wear/.MainActivity
  actor.starintel.wear/.SearchActivity
  actor.starintel.wear/.ExplorerActivity
  actor.starintel.wear/.TargetsActivity
  actor.starintel.wear/.GraphActivity
)

for component in "${launchers[@]}"; do
  label="${component##*/.}"
  echo "smoke: $label"

  "${adb_cmd[@]}" shell am force-stop actor.starintel.wear >/dev/null 2>&1 || true
  "${adb_cmd[@]}" logcat -c >/dev/null 2>&1 || true

  start_output="$("${adb_cmd[@]}" shell am start -W -n "$component" 2>&1 || true)"
  if ! grep -q 'Status: ok' <<<"$start_output"; then
    echo "error: $label did not start successfully" >&2
    printf '%s\n' "$start_output" >&2
    exit 1
  fi

  sleep 1
  pid="$("${adb_cmd[@]}" shell pidof actor.starintel.wear 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$pid" ]]; then
    echo "error: $label crashed or exited immediately" >&2
    "${adb_cmd[@]}" logcat -d -v brief -t 250 2>/dev/null | tail -n 250 >&2 || true
    exit 1
  fi

  fatal="$("${adb_cmd[@]}" logcat -d -v brief 'AndroidRuntime:E' '*:S' 2>/dev/null | tr -d '\r' || true)"
  if grep -q 'Process: actor.starintel.wear' <<<"$fatal"; then
    echo "error: AndroidRuntime fatal exception while launching $label" >&2
    printf '%s\n' "$fatal" >&2
    exit 1
  fi
done

# Leave the normal dashboard visible after the smoke run.
"${adb_cmd[@]}" shell am start -n actor.starintel.wear/.MainActivity >/dev/null 2>&1 || true

echo "Wear launcher smoke test passed (${#launchers[@]} launchers)."
