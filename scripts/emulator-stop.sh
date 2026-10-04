#!/usr/bin/env bash
# Shut down the emulator (and private Xvfb if we started one).
set -euo pipefail

STATE_DIR="${STARINTEL_EMU_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/emulator}"
ADB="${STARINTEL_EMULATOR_ADB:-adb}"
PORT="${STARINTEL_EMULATOR_PORT:-5554}"

if [[ -f "$STATE_DIR/emulator.pid" ]]; then
  "$ADB" -s "emulator-$PORT" emu kill > /dev/null 2>&1 || true
  PID="$(cat "$STATE_DIR/emulator.pid")"
  for _ in $(seq 1 20); do
    kill -0 "$PID" 2> /dev/null || break
    sleep 0.5
  done
  kill "$PID" 2> /dev/null || true
  rm -f "$STATE_DIR/emulator.pid"
  echo "emulator stopped"
else
  echo "no emulator pid found in $STATE_DIR"
fi

if [[ -f "$STATE_DIR/xvfb.pid" ]]; then
  kill "$(cat "$STATE_DIR/xvfb.pid")" 2> /dev/null || true
  rm -f "$STATE_DIR/xvfb.pid"
  echo "xvfb stopped"
fi
