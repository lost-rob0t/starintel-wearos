# starintel-wearos

Wear OS surfaces for StarIntel, built around the aggregate `GET /api/v1/stats` server contract.

## Packages

This repository produces three installable packages:

- `phone-app/` — Android companion used to configure the paired StarIntel watch without typing credentials on the watch.
- `wear-app/` — standalone Wear OS app, authenticated StarIntel server client/cache, complication data sources, and Tiles.
- `watchface/` — resource-only Watch Face Format package.

The phone and Wear app deliberately share the application ID `actor.starintel.wear`. Google Play services therefore permits Wearable Data Layer communication only when both packages also have the same signing certificate. The watch-face package remains separate because executable Wear OS logic and a WFF watch face cannot live in the same bundle.

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

The normal setup path is now the Android companion. Paste the server URL and key on the phone and tap **SEND TO WATCH**. The companion does not persist the API key. It sends one configuration message through the Wearable Data Layer to the reachable paired StarIntel watch and clears the key field after dispatch.

The watch validates the payload, tests `/api/v1/stats` with the supplied credential, and only saves the new configuration after that authenticated request succeeds. The saved key is encrypted with an AES-GCM key generated inside Android Keystore; only ciphertext and the IV are persisted. The watch returns a redacted success/failure acknowledgement to the phone.

The existing on-watch configuration screen remains available as a fallback.

A least-privilege StarIntel API-client principal should be issued specifically for the watch and limited to the read capability required by `/api/v1/stats`. Do not reuse an administrator credential.

## Install on Galaxy Watch5 Pro

### 1. Get matching APKs

Every successful GitHub Actions build publishes:

- `starintel-phone-app-debug` containing `phone-app-debug.apk`
- `starintel-wear-app-debug` containing `wear-app-debug.apk`
- `starintel-watchface-debug` containing `watchface-debug.apk`

For companion configuration, download the phone and Wear APKs from the **same workflow run** so their signing certificates match.

Or build all packages locally in one checkout:

```sh
gradle :phone-app:assembleDebug :wear-app:assembleDebug :watchface:assembleDebug
```

### 2. Install the Android companion

On the paired Android phone, either open `phone-app-debug.apk` and allow installation from that source, or use adb:

```sh
adb install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

### 3. Enable wireless debugging on the watch

On the Galaxy Watch5 Pro:

1. Connect the watch and development computer to the same Wi-Fi network.
2. Open **Settings → About watch → Software**.
3. Tap **Software version** five times to enable Developer options.
4. Open **Settings → Developer options**.
5. Enable **ADB debugging**.
6. Enable **Wireless debugging** and allow the current Wi-Fi network.
7. Open **Wireless debugging → Pair new device** and note the pairing IP/port and six-digit pairing code.

### 4. Pair and connect with adb

```sh
adb pair WATCH_IP:PAIR_PORT
# enter the six-digit code shown on the watch

adb connect WATCH_IP:ADB_PORT
adb devices
```

The pairing port and normal wireless-debugging port can be different; use exactly what the watch displays.

### 5. Install both watch packages

From the repo root:

```sh
bash scripts/install-watch.sh WATCH_IP:ADB_PORT
```

Or manually:

```sh
adb -s WATCH_IP:ADB_PORT install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk
adb -s WATCH_IP:ADB_PORT install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

### 6. Configure from the phone

1. Open **StarIntel Companion** on the paired Android phone.
2. Enter the StarIntel server origin, for example `https://starintel.example`.
3. Paste the private StarIntel API key (`star_sk_v1_…`).
4. Tap **SEND TO WATCH**.
5. The phone finds a reachable paired watch advertising the StarIntel configuration capability.
6. The watch tests the credential against `/api/v1/stats` before saving it.
7. Wait for the phone to show the authenticated acknowledgement.
8. Select **StarIntel** from the watch-face picker and add the **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** Tiles.

Release Wear builds require HTTPS. Debug Wear builds allow cleartext HTTP for LAN development; bearer authentication is still required.

## Build

The CI configuration uses JDK 17, Gradle 9.6, Android API 36, Wearable Data Layer 20.0.1, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :phone-app:testDebugUnitTest :phone-app:assembleDebug :wear-app:testDebugUnitTest :wear-app:assembleDebug :watchface:assembleDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
