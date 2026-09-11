# starintel-wearos

Wear OS surfaces for StarIntel, built around the aggregate `GET /api/v1/stats` server contract.

## Packages

This repository produces three installable packages:

- `phone-app/` — Android companion used to configure the paired StarIntel watch without typing credentials on the watch.
- `wear-app/` — standalone Wear OS app, authenticated StarIntel server client/cache, complication data sources, and Tiles.
- `watchface/` — resource-only Watch Face Format package.

The phone and Wear app deliberately share the application ID `actor.starintel.wear`. Google Play services therefore permits Wearable Data Layer communication only when both packages also have the same signing certificate. The watch-face package remains separate because executable Wear OS logic and a WFF watch face cannot live in the same bundle.

## Nix build workflow

Nix is the preferred local build path. The flake supplies the JDK, Gradle, Android SDK/API 36, build-tools 36.0.0, platform-tools/adb, and the NixOS-safe `aapt2` override. Android Studio and a host Android SDK are not required.

Enter the development shell:

```sh
nix develop
```

Build individual packages:

```sh
nix run .#build-phone
nix run .#build-wear
nix run .#build-watchface
```

Build and test everything in one pass:

```sh
nix run .#build-all
```

The Nix entrypoints stage stable filenames here:

```text
build/nix/phone-app-debug.apk
build/nix/wear-app-debug.apk
build/nix/watchface-debug.apk
```

Run the existing unit/build checks through the Nix toolchain without staging APKs:

```sh
nix run .#check
```

The Android toolchain itself is also exposed as Nix packages:

```sh
nix build .#toolchain
nix build .#android-sdk
nix build .#gradle
```

The current build commands intentionally run Gradle from the working tree so Maven/Google dependencies can use the normal Gradle cache. The SDK/JDK/Gradle/tooling are supplied by Nix. A later hardening slice can use nixpkgs `gradle.fetchDeps` to lock every Gradle artifact and turn the APKs themselves into fully sandboxed `nix build` derivations.

## Galaxy Watch5 Pro slice

The initial UI is designed for a round Galaxy Watch5 Pro-class display:

- AMOLED-black StarIntel watch face with cyan/violet accents.
- Large digital clock.
- Three default StarIntel complications: server status, targets, and total corpus documents.
- Ops Tile: online/stale/offline state, server version, and data age.
- Targets Tile: aggregate targets plus target/investigation-target split.
- Corpus Tile: total documents plus the two largest document-type buckets.

All Tile and complication reads share a 60-second last-good cache. If the server is unavailable, the watch keeps showing cached values and marks them stale/offline instead of blanking the surface.

## Private authentication

StarIntel access is authenticated by default. The Wear app expects a StarIntel v1 API key and sends it using the server's bearer contract:

```text
Authorization: Bearer star_sk_v1_<credential-id>_<secret>
```

The normal setup path is the Android companion. Paste the server origin and key on the phone and tap **SEND SECURELY TO WATCH**. The companion does not persist the API key, disables screen capture while credentials are visible, and does not save the server URL until the watch confirms a successful authenticated setup.

The companion discovers only a reachable paired StarIntel Wear app with the matching package/signature. Each transfer has a unique request ID and protocol version; acknowledgements from the wrong node, stale requests, or incompatible payloads are ignored. Duplicate sends are blocked while a request is active and the phone times out cleanly if the watch never acknowledges it.

The watch validates the payload, tests `/api/v1/stats` with the supplied credential, and only commits the new URL/key after that authenticated request succeeds. Failed authentication, permission errors, malformed payloads, and network failures leave the previous working watch configuration untouched. The saved key is encrypted with an AES-GCM key generated inside Android Keystore; only ciphertext and the IV are persisted. The watch returns only bounded, redacted result text to the phone.

After a successful acknowledgement the companion clears the API-key field. Failed attempts keep the typed key in the field so the user can correct the server or retry without re-pasting it; leaving/destroying the activity clears the field from the UI.

The on-watch configuration screen remains available as a fallback. It follows the same test-before-save rule and confirms before deleting a saved credential.

A least-privilege StarIntel API-client principal should be issued specifically for the watch and limited to the read capability required by `/api/v1/stats`. Do not reuse an administrator credential.

## Install on Galaxy Watch5 Pro

### 1. Build matching APKs

Preferred Nix path:

```sh
nix run .#build-all
```

Every successful GitHub Actions build also publishes:

- `starintel-phone-app-debug` containing `phone-app-debug.apk`
- `starintel-wear-app-debug` containing `wear-app-debug.apk`
- `starintel-watchface-debug` containing `watchface-debug.apk`

For companion configuration, use the phone and Wear APKs from the same build/run so their signing certificates match.

### 2. Install the Android companion

With adb available from `nix develop`:

```sh
adb install -r build/nix/phone-app-debug.apk
```

Or open `phone-app-debug.apk` on the paired Android phone and allow installation from that source.

### 3. Enable wireless debugging on the watch

On the Galaxy Watch5 Pro:

1. Connect the watch and development computer to the same Wi-Fi network.
2. Open **Settings → About watch → Software**.
3. Tap **Software version** five times to enable Developer options.
4. Open **Settings → Developer options**.
5. Enable **ADB debugging**.
6. Enable **Wireless debugging** and allow the current Wi-Fi network.
7. Open **Wireless debugging → Pair new device** and note the pairing IP/port and six-digit pairing code.

### 4. Pair and connect with Nix-provided adb

```sh
nix develop
adb pair WATCH_IP:PAIR_PORT
# enter the six-digit code shown on the watch

adb connect WATCH_IP:ADB_PORT
adb devices
```

The pairing port and normal wireless-debugging port can be different; use exactly what the watch displays.

### 5. Install both watch packages

After `nix run .#build-all`:

```sh
nix run .#install-watch -- WATCH_IP:ADB_PORT
```

The installer verifies both installed package IDs before reporting success. It prefers `build/nix/` APKs but still supports the Gradle and downloaded CI-artifact layouts.

### 6. Configure from the phone

1. Open **StarIntel Companion** on the paired Android phone.
2. Wait for the app to show the reachable watch name.
3. Enter the StarIntel server origin, for example `https://starintel.example`.
4. Paste the private StarIntel API key (`star_sk_v1_…`).
5. Tap **SEND SECURELY TO WATCH**.
6. The watch tests the credential against `/api/v1/stats` before saving it.
7. Wait for the authenticated success acknowledgement on the phone.
8. Select **StarIntel** from the watch-face picker and add the **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** Tiles.

Release companion/Wear builds accept HTTPS server origins only. Debug builds allow cleartext HTTP for LAN development; bearer authentication is still required.

## Non-Nix build

The legacy CI path remains available and currently uses JDK 17, Gradle 9.6, Android API 36, Wearable Data Layer 20.0.1, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :phone-app:testDebugUnitTest :phone-app:assembleDebug :wear-app:testDebugUnitTest :wear-app:assembleDebug :watchface:assembleDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
