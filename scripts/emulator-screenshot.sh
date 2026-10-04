#!/usr/bin/env bash
# Capture the emulator's HOST-side X11 framebuffer.
# FLAG_SECURE only blinds guest-side capture paths (screencap/screenrecord/
# MediaProjection). The emulator window on the host X server still shows the
# real pixels, exactly like the human eye on a physical device, so capturing
# the Xvfb window yields screenshots without touching the app.
set -euo pipefail

STATE_DIR="${STARINTEL_EMU_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/emulator}"
OUT="${1:-}"
if [[ -z "$OUT" ]]; then
  mkdir -p build/emu/shots
  OUT="build/emu/shots/shot-$(date +%Y%m%d-%H%M%S).png"
fi

DISPLAY="${STARINTEL_EMULATOR_DISPLAY:-$(cat "$STATE_DIR/display" 2>/dev/null || echo :97)}"
export DISPLAY

TARGET="root"
WID="$(xdotool search --class '^(emulator|qemu|Android Emulator)$' 2>/dev/null | head -n 1 || true)"
if [[ -n "$WID" ]]; then
  TARGET="$WID"
fi

if [[ "$TARGET" == "root" ]]; then
  import -window root "$OUT"
else
  import -window "$TARGET" "$OUT"
fi

echo "$OUT ($(identify -format '%wx%h' "$OUT")) display=$DISPLAY target=$TARGET"
