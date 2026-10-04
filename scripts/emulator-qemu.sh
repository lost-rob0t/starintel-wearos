#!/usr/bin/env bash
# EXPERIMENTAL: boot the same AOSP google_apis image under vanilla
# qemu-system-x86_64 (no Android SDK emulator). Upstream QEMU >= 7 carries the
# goldfish devices (pipe/battery/rtc/sync/fb) that ranchu images need, so this
# can reach UI; when it does, the same host-side X11 screenshot path applies.
# Diagnostics land in $STATE_DIR/qemu-serial.log.
set -euo pipefail

[[ -n "${STARINTEL_EMULATOR_SDK:-}" ]] || { echo "error: launch via: nix run .#emulator-qemu" >&2; exit 2; }

API="${STARINTEL_EMULATOR_API:-36}"
IMG_DIR="$STARINTEL_EMULATOR_SDK/system-images/android-$API/google_apis/x86_64"
STATE_DIR="${STARINTEL_EMU_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/starintel-wearos/emulator}"
ADB="${STARINTEL_EMULATOR_ADB:-adb}"
HOSTFWD_PORT="${STARINTEL_QEMU_ADB_PORT:-5556}"

mkdir -p "$STATE_DIR"

for f in kernel-ranchu ramdisk.img system.img vendor.img; do
  [[ -f "$IMG_DIR/$f" ]] || { echo "error: missing $IMG_DIR/$f (nix build .#emulator-sdk)" >&2; exit 1; }
done

[[ -f "$STATE_DIR/qemu-userdata.img" ]] || {
  # New-style images ship no userdata.img; create an empty ext4 data partition.
  truncate -s 2G "$STATE_DIR/qemu-userdata.img"
  mkfs.ext4 -q -F -L data "$STATE_DIR/qemu-userdata.img"
}

# First-stage init polls for a GPT partition named "metadata"; the image
# ships it as encryptionkey.img (the emulator's metadata disk).
[[ -f "$STATE_DIR/qemu-metadata.img" ]] || { cp "$IMG_DIR/encryptionkey.img" "$STATE_DIR/qemu-metadata.img" && chmod 644 "$STATE_DIR/qemu-metadata.img"; }

# Raw cache disk keeps vdb/vdc ordering identical to the emulator.
[[ -f "$STATE_DIR/qemu-cache.img" ]] || {
  truncate -s 66M "$STATE_DIR/qemu-cache.img"
  mkfs.ext4 -q -F -L cache "$STATE_DIR/qemu-cache.img"
}

QEMU="qemu-system-x86_64"

# goldfish devices present in this QEMU build?
DEVHELP="$("$QEMU" -device help 2> /dev/null || true)"
DEVICES=()
for dev in goldfish-pipe goldfish-battery goldfish-rtc goldfish-sync goldfish-fb; do
  grep -q "name \"$dev\"" <<< "$DEVHELP" && DEVICES+=("-device" "$dev")
done
echo "goldfish devices: ${DEVICES[*]:-none}"

if grep -q 'name "goldfish-fb"' <<< "$DEVHELP"; then
  GFX=(-vga none "${DEVICES[@]}")
else
  GFX=(-vga std "${DEVICES[@]}")
fi

# reuse the run script's Xvfb display when headless
if [[ "${STARINTEL_EMULATOR_VISIBLE:-0}" != "1" ]]; then
  DISP="${STARINTEL_EMULATOR_DISPLAY:-:97}"
  if command -v xdpyinfo > /dev/null 2>&1; then
    if ! xdpyinfo -display "$DISP" > /dev/null 2>&1; then
      Xvfb "$DISP" -screen 0 1600x2800x24 -nolisten tcp > "$STATE_DIR/xvfb.log" 2>&1 &
      echo $! > "$STATE_DIR/xvfb.pid"
      sleep 1
    fi
    export DISPLAY="$DISP"
  else
    echo "warn: xdpyinfo unavailable, SDL display may fail" >&2
  fi
  echo "$DISPLAY" > "$STATE_DIR/display"
fi

# Faithful replication of the SDK emulator's PCI layout, which this image's
# fstab.ranchu hardcodes (metadata at 0000:00:06.0) and which keeps the
# /dev/block/vdX ordering stable: vda=system(super+vbmeta), vdb=cache,
# vdc=userdata(data), vdd=metadata, vde=vendor.
DISKS=(
  -drive "if=none,id=system,file=$IMG_DIR/system.img,format=raw,readonly=on"
  -device virtio-blk-pci,drive=system,addr=0x3
  -drive "if=none,id=cache,file=$STATE_DIR/qemu-cache.img,format=raw"
  -device virtio-blk-pci,drive=cache,addr=0x4
  -drive "if=none,id=userdata,file=$STATE_DIR/qemu-userdata.img,format=raw"
  -device virtio-blk-pci,drive=userdata,addr=0x5
  -drive "if=none,id=metadata,file=$STATE_DIR/qemu-metadata.img,format=raw"
  -device virtio-blk-pci,drive=metadata,addr=0x6
  -drive "if=none,id=vendor,file=$IMG_DIR/vendor.img,format=raw,readonly=on"
  -device virtio-blk-pci,drive=vendor,addr=0x7
)

nohup "$QEMU" \
  -enable-kvm -cpu host -smp 4 -m 3072 \
  -kernel "$IMG_DIR/kernel-ranchu" \
  -initrd "$IMG_DIR/ramdisk.img" \
  -append "console=ttyS0,115200 init=/init androidboot.hardware=ranchu androidboot.console=ttyS0 androidboot.selinux=permissive ro.boot.selinux=permissive androidboot.qemu=1 buildvariant=userdebug 8250.nr_uarts=1 clocksource=pit" \
  "${DISKS[@]}" \
  -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:$HOSTFWD_PORT-:5555" \
  -device virtio-net-pci,netdev=net0 \
  -display sdl \
  "${GFX[@]}" \
  -serial "file:$STATE_DIR/qemu-serial.log" \
  -no-reboot \
  > "$STATE_DIR/qemu.log" 2>&1 &

echo $! > "$STATE_DIR/qemu.pid"
echo "qemu pid $(cat "$STATE_DIR/qemu.pid"); serial log: $STATE_DIR/qemu-serial.log"
echo "once adbd is up: $ADB connect 127.0.0.1:$HOSTFWD_PORT"
echo "screenshot: nix run .#emulator-shot -- [out.png]"
