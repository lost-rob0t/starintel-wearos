# starintel-wearos

Wear OS surfaces for StarIntel, built around the aggregate `GET /api/v1/stats` server contract.

## Packages

This repository produces four application/package families plus one reusable
Android library:

- `starintel-android/` — typed StarIntel client, local actor runtime, ECL/Tek9
  bridge contract, and Prolog-RLM agent envelopes for native Android apps.
- `phone-app/` — Android companion used to configure the paired StarIntel watch without typing credentials on the watch.
- `quasar-app/` — separately installable native Android Quasar client for the current Star server API.
- `wear-app/` — standalone Wear OS app, authenticated StarIntel server client/cache, complication data sources, and Tiles.
- `watchface/` — resource-only Watch Face Format package.

The phone and Wear app deliberately share the application ID `actor.starintel.wear`. Google Play services therefore permits Wearable Data Layer communication only when both packages also have the same signing certificate. The watch-face package remains separate because executable Wear OS logic and a WFF watch face cannot live in the same bundle.

## Nix build workflow

Nix is the preferred local build path. The committed `flake.lock` pins nixpkgs, JDK 17, Gradle, Android API 36, build-tools 36.0.0, platform-tools/adb, and the NixOS-safe `aapt2` override. Android Studio and a host Android SDK are not required.

Enter the development shell:

```sh
nix develop
```

Build individual packages:

```sh
nix run .#build-phone
nix run .#build-quasar
nix run .#build-wear
nix run .#build-watchface
```

Build and test everything in one pass:

```sh
nix run .#build-all
```

Stable APK outputs are staged at:

```text
build/nix/phone-app-debug.apk
build/nix/quasar-app-debug.apk
build/nix/wear-app-debug.apk
build/nix/watchface-debug.apk
```

Run checks without staging APKs:

```sh
nix run .#check
```

## Pair Android devices with Nix

For Android devices that expose **Pair device with QR code**, the easiest path is:

```sh
nix run .#pair-android -- --qr
```

Open **Developer options → Wireless debugging → Pair device with QR code**, then scan the terminal QR. The helper uses Android's native ADB Wi-Fi QR format, waits for the matching `_adb-tls-pairing._tcp` mDNS service, pairs it, discovers the normal `_adb-tls-connect._tcp` endpoint, connects it, and verifies that endpoint is online. `qrencode` is supplied by the Nix app/dev shell. See `docs/adb-qr-pairing.md` for protocol and troubleshooting details.

The generic pairing-code command works for Android phones and Wear OS watches and uses only Nix-provided `adb`:

```sh
nix run .#pair-android -- HOST:PAIR_PORT HOST:ADB_PORT
```

`adb` prompts for the six-digit pairing code shown by Android's Wireless debugging screen. The pairing port and normal ADB port are usually different.

If the normal ADB endpoint is not known yet, pair first:

```sh
nix run .#pair-android -- HOST:PAIR_PORT
```

Then reconnect an already-paired phone or watch without another pairing code:

```sh
nix run .#pair-android -- --connect HOST:ADB_PORT
```

The older watch-specific command remains as a compatibility alias:

```sh
nix run .#pair-watch -- WATCH_IP:PAIR_PORT WATCH_IP:ADB_PORT
nix run .#pair-watch -- --connect WATCH_IP:ADB_PORT
```

Both commands verify that the requested endpoint appears online in `adb devices`. Connection attempts are bounded so a stale or forgotten pairing cannot leave the command hanging indefinitely.

### Recover a forgotten or stale pairing

If `adb devices` is empty, the TCP port is still reachable, or `adb connect` cannot authenticate, the device may have forgotten the workstation pairing. An open TCP port by itself does not mean the ADB trust relationship is still valid.

Inspect the local ADB and mDNS state:

```sh
nix run .#pair-android -- --diagnose
```

Restart only the local ADB daemon:

```sh
nix run .#pair-android -- --reset-adb
```

Then on the phone/watch:

1. Turn **Wireless debugging** off and back on.
2. Check **Paired devices**. If this workstation is missing, it must be paired again.
3. On a QR-capable phone/device, run `nix run .#pair-android -- --qr` and scan the generated QR; otherwise open **Pair device with pairing code** and note the fresh pairing endpoint/code.
4. For pairing-code mode, note the current normal Wireless debugging IP/port.
5. Pair/connect again.

Pairing-code fallback:

```sh
nix run .#pair-android -- HOST:PAIR_PORT HOST:ADB_PORT
```

`--connect` cannot restore a trust relationship after the device has forgotten the workstation; a fresh pairing operation is required.

## Install the Android companion

First build the companion or all APKs:

```sh
nix run .#build-phone
# or
nix run .#build-all
```

On the Android phone, enable **Developer options → Wireless debugging**. Pair and connect it with the QR flow:

```sh
nix run .#pair-android -- --qr
```

Or use the pairing-code fallback:

```sh
nix run .#pair-android -- PHONE_IP:PAIR_PORT PHONE_IP:ADB_PORT
```

Install and verify the companion with:

```sh
nix run .#install-phone -- PHONE_IP:ADB_PORT
```

`install-phone` prefers `build/nix/phone-app-debug.apk`, supports the normal Gradle/CI artifact layouts as fallbacks, verifies the target is reachable before installing, and verifies package `actor.starintel.wear` exists afterward.

After the companion is installed, open **Phone apps** to download, verify, and
install/update the separate Quasar APK from the selected master or versioned
release channel. Android displays the final installation confirmation; the
companion never performs a silent install.

For reconnects after the first pairing:

```sh
nix run .#pair-android -- --connect PHONE_IP:ADB_PORT
nix run .#install-phone -- PHONE_IP:ADB_PORT
```

## Galaxy Watch5 Pro setup

### 1. Build matching APKs

```sh
nix run .#build-all
```

Use phone and Wear APKs from the same build/run so their signing certificates match.

### 2. Enable wireless debugging on the watch

On the Galaxy Watch5 Pro:

1. Connect the watch and development computer to the same Wi-Fi network.
2. Open **Settings → About watch → Software**.
3. Tap **Software version** five times to enable Developer options.
4. Open **Settings → Developer options**.
5. Enable **ADB debugging**.
6. Enable **Wireless debugging** and allow the current Wi-Fi network.
7. Note the normal **IP address & Port**.
8. Open **Pair new device** and note its pairing IP/port and six-digit code.

### 3. Pair and connect

Generic command:

```sh
nix run .#pair-android -- WATCH_IP:PAIR_PORT WATCH_IP:ADB_PORT
```

Compatibility alias:

```sh
nix run .#pair-watch -- WATCH_IP:PAIR_PORT WATCH_IP:ADB_PORT
```

For later reconnects:

```sh
nix run .#pair-android -- --connect WATCH_IP:ADB_PORT
```

### 4. Install the Wear app and watch face

```sh
nix run .#install-watch -- WATCH_IP:ADB_PORT
```

The installer verifies both installed package IDs before reporting success.

### 5. Configure from the phone

1. Open **StarIntel Companion** on the paired Android phone.
2. Wait for the app to show the reachable watch name.
3. Enter the StarIntel server origin, for example `https://starintel.example`.
4. Paste the private StarIntel API key (`star_sk_v1_…`).
5. Tap **SEND SECURELY TO WATCH**.
6. The watch tests `/api/v1/stats` with the supplied credential before saving it.
7. Wait for the authenticated success acknowledgement on the phone.
8. Select **StarIntel** from the watch-face picker and add the **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** Tiles.

## Watch UI

The initial UI is designed for a round Galaxy Watch5 Pro-class display:

- AMOLED-black StarIntel watch face with cyan/violet accents.
- Large digital clock.
- Three default StarIntel complications: server status, targets, and total corpus documents.
- Ops Tile: online/stale/offline state, server version, and data age.
- Targets Tile: aggregate targets plus target/investigation-target split.
- Corpus Tile: total documents plus the two largest document-type buckets.
- Activity app: real line graphs for 1 minute, 5 minutes, 15 minutes, 1 hour, 6 hours, 1 day, and 1 week, with total and per-type series plus an auto-rotating range.
- Stack Tile: documents, targets, one-hour activity, and reachability in one vertically stacked glance.
- Explorer: random document discovery and direct Open/Graph actions, so opaque document IDs do not need to be typed.

All Tile and complication reads share a 60-second last-good cache. If the server is unavailable, the watch keeps showing cached values and marks them stale/offline instead of blanking the surface.

The complication picker includes fixed activity-line providers for every supported range plus an Auto provider. Ultra Black keeps a true black background while retaining clearer face identity, date context, large time, and minimal operational labels.

## Private authentication

StarIntel access is authenticated by default. The Wear app expects a StarIntel v1 API key and sends it using the server bearer contract:

```text
Authorization: Bearer star_sk_v1_<credential-id>_<secret>
```

The normal setup path is the Android companion. The companion does not persist the API key, disables screen capture while credentials are visible, and does not save the server URL until the watch confirms a successful authenticated setup.

The companion discovers only a reachable paired StarIntel Wear app with the matching package/signature. Each transfer has a unique request ID and protocol version; acknowledgements from the wrong node, stale requests, or incompatible payloads are ignored.

The watch validates the payload, tests `/api/v1/stats`, and only commits the new URL/key after the authenticated request succeeds. Failed authentication, permission errors, malformed payloads, network failures, and secure-storage failures leave the previous working configuration untouched. The saved key is encrypted with an AES-GCM key generated inside Android Keystore.

A least-privilege StarIntel API-client principal should be issued specifically for the watch and limited to the read capability required by `/api/v1/stats`.

Release companion/Wear builds accept HTTPS server origins only. Debug builds allow cleartext HTTP for LAN development; bearer authentication is still required.

## CI artifacts

Every successful Android workflow publishes:

- `starintel-phone-app-debug` containing `phone-app-debug.apk`
- `quasar-android-debug` containing `quasar-app-debug.apk`
- `starintel-wear-app-debug` containing `wear-app-debug.apk`
- `starintel-watchface-debug` containing `watchface-debug.apk`
- `starintel-nix-apks-debug` containing all three APKs built together through the pinned Nix toolchain

## Nix toolchain packages

The Android toolchain itself is exposed as Nix packages:

```sh
nix build .#toolchain
nix build .#android-sdk
nix build .#gradle
```

## Non-Nix build

The legacy CI path remains available and currently uses JDK 17, Gradle 9.6, Android API 36, Wearable Data Layer 20.0.1, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :phone-app:testDebugUnitTest :phone-app:assembleDebug \
  :quasar-app:testDebugUnitTest :quasar-app:assembleDebug \
  :wear-app:testDebugUnitTest :wear-app:assembleDebug \
  :watchface:assembleNeonDebug :watchface:assembleCommandDebug :watchface:assembleTerminalDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
