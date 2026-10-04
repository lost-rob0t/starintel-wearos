#!/usr/bin/env bash
# Boot the StarIntel phone emulator through the pinned Nix SDK.
# Headless by default: the emulator renders into a private Xvfb display so
# host-side X11 capture can screenshot FLAG_SECURE app content.
set -euo pipefail

[[ -n "${STARINTEL_EMULATOR_SDK:-}" ]] || { echo "error: launch via: nix run .#emulator" >&2; exit 2; }

AVD_NAME="${STARINTEL_AVD_NAME:-starintel-phone}"
API="${STARINTEL_EMULATOR_API:-36}"
IMG="system-images;android-$API;google_apis;x86_64"
STATE_DIR="${STARINTEL_EMU_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/emulator}"
AVD_HOME="$STATE_DIR/avd"
PORT="${STARINTEL_EMULATOR_PORT:-5554}"
REPO_APKS=(phone-app quasar-app collector-app hackmode-app operator-app)

mkdir -p "$STATE_DIR" "$AVD_HOME" build/emu/shots
export ANDROID_USER_HOME="$STATE_DIR/android-user"
export ANDROID_AVD_HOME="$AVD_HOME"
export ANDROID_PREFS_ROOT="$STATE_DIR/android-user"
ADB="${STARINTEL_EMULATOR_ADB:-adb}"
SERIAL="emulator-$PORT"

# --- display: private Xvfb unless STARINTEL_EMULATOR_VISIBLE=1 ---
if [[ "${STARINTEL_EMULATOR_VISIBLE:-0}" == "1" ]]; then
  export DISPLAY="${DISPLAY:?STARINTEL_EMULATOR_VISIBLE=1 needs DISPLAY}"
  echo "$DISPLAY" > "$STATE_DIR/display"
else
  DISP="${STARINTEL_EMULATOR_DISPLAY:-:97}"
  if ! xdpyinfo -display "$DISP" > /dev/null 2>&1; then
    Xvfb "$DISP" -screen 0 1600x2800x24 -nolisten tcp > "$STATE_DIR/xvfb.log" 2>&1 &
    echo $! > "$STATE_DIR/xvfb.pid"
    for _ in $(seq 1 20); do
      xdpyinfo -display "$DISP" > /dev/null 2>&1 && break
      sleep 0.5
    done
    xdpyinfo -display "$DISP" > /dev/null 2>&1 || { echo "error: Xvfb failed, see $STATE_DIR/xvfb.log" >&2; exit 1; }
  fi
  export DISPLAY="$DISP"
  echo "$DISP" > "$STATE_DIR/display"
fi

# --- AVD (idempotent) ---
if [[ ! -f "$AVD_HOME/$AVD_NAME.ini" ]]; then
  AVDMGR="$(ls -1 "$STARINTEL_EMULATOR_SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -n1 || true)"
  [[ -n "$AVDMGR" ]] || AVDMGR="$STARINTEL_EMULATOR_SDK/tools/bin/avdmanager"
  echo "no" | "$AVDMGR" create avd -n "$AVD_NAME" -k "$IMG" -d pixel_7 < /dev/null \
    || echo "no" | "$AVDMGR" create avd -n "$AVD_NAME" -k "$IMG" < /dev/null
  CFG="$AVD_HOME/$AVD_NAME.avd/config.ini"
  {
    echo "hw.gpu.enabled = yes"
    echo "hw.gpu.mode = swiftshader_indirect"
    echo "hw.ramSize = 3072"
    echo "hw.keyboard = yes"
  } >> "$CFG"
fi

# --- launch emulator ---
EMULATOR="$STARINTEL_EMULATOR_SDK/emulator/emulator"
if "$ADB" -s "$SERIAL" get-state > /dev/null 2>&1; then
  echo "emulator already running: $SERIAL"
else
  echo "booting $AVD_NAME ($IMG) on DISPLAY=$DISPLAY port $PORT ..."
  nohup "$EMULATOR" "@$AVD_NAME" \
    -port "$PORT" \
    -no-snapshot -no-boot-anim -no-audio \
    -gpu swiftshader_indirect \
    > "$STATE_DIR/emulator.log" 2>&1 &
  echo $! > "$STATE_DIR/emulator.pid"
fi

"$ADB" -s "$SERIAL" wait-for-device
for i in $(seq 1 120); do
  BOOTED="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$BOOTED" == "1" ]] && break
  [[ "$i" == 120 ]] && { echo "error: boot timeout, see $STATE_DIR/emulator.log" >&2; exit 1; }
  sleep 5
done
echo "boot complete on $SERIAL"

# --- install debug APKs ---
INSTALLED=()
for app in "${REPO_APKS[@]}"; do
  APK="build/nix/$app-debug.apk"
  if [[ -f "$APK" ]]; then
    "$ADB" -s "$SERIAL" install -r -g "$APK" > /dev/null
    INSTALLED+=("$app")
  else
    echo "warn: $APK missing (run: nix run .#build-all)" >&2
  fi
done

echo "ready:"
echo "  serial:     $SERIAL"
echo "  display:    $DISPLAY (host-side capture target)"
echo "  installed:  ${INSTALLED[*]:-none}"
echo "  screenshot: nix run .#emulator-shot -- [out.png]"
echo "  stop:       nix run .#emulator-stop"
