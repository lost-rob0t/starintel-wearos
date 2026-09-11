# starintel-wearos

Wear OS surfaces for StarIntel, built around the aggregate `GET /api/v1/stats` server contract.

## Packages

This repository intentionally produces two separate installable packages:

- `wear-app/` — standalone Wear OS app, authenticated StarIntel server client/cache, complication data sources, and Tiles.
- `watchface/` — resource-only Watch Face Format package.

The split is required by the current Watch Face Format packaging model: executable Wear OS logic and a WFF watch face cannot live in the same bundle.

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

The raw API key is never stored in ordinary preferences. It is encrypted with an AES-GCM key generated inside Android Keystore; only the ciphertext and IV are persisted. The setup UI never reads the saved key back into the text field, and **FORGET KEY** deletes both the encrypted value and the Keystore entry.

A least-privilege StarIntel API-client principal should be issued specifically for the watch and limited to the read capability required by `/api/v1/stats`. Do not reuse an administrator credential.

## Install on Galaxy Watch5 Pro

### 1. Get the APKs

Every successful GitHub Actions build publishes two artifacts:

- `starintel-wear-app-debug` containing `wear-app-debug.apk`
- `starintel-watchface-debug` containing `watchface-debug.apk`

Download and unzip both artifacts into the repo root, or build locally with:

```sh
gradle :wear-app:assembleDebug :watchface:assembleDebug
```

### 2. Enable wireless debugging on the watch

On the Galaxy Watch5 Pro:

1. Connect the watch and development computer to the same Wi-Fi network.
2. Open **Settings → About watch → Software**.
3. Tap **Software version** five times to enable Developer options.
4. Open **Settings → Developer options**.
5. Enable **ADB debugging**.
6. Enable **Wireless debugging** and allow the current Wi-Fi network.
7. Open **Wireless debugging → Pair new device** and note the pairing IP/port and six-digit pairing code.

### 3. Pair and connect with adb

On the development computer:

```sh
adb pair WATCH_IP:PAIR_PORT
# enter the six-digit code shown on the watch

adb connect WATCH_IP:ADB_PORT
adb devices
```

The pairing port and normal wireless-debugging port can be different; use exactly what the watch displays.

### 4. Install both StarIntel packages

From the repo root, either run the helper:

```sh
bash scripts/install-watch.sh WATCH_IP:ADB_PORT
```

Or install manually:

```sh
adb -s WATCH_IP:ADB_PORT install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk
adb -s WATCH_IP:ADB_PORT install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

If you downloaded CI artifacts instead of building locally, the helper also recognizes:

```text
wear-app-debug.apk
watchface-debug.apk
starintel-wear-app-debug/wear-app-debug.apk
starintel-watchface-debug/watchface-debug.apk
```

The helper opens the StarIntel setup screen after installation.

### 5. Configure StarIntel

1. Enter the StarIntel server origin, for example `https://starintel.example`.
2. Enter the private StarIntel API key (`star_sk_v1_…`).
3. Tap **SAVE + TEST**. A successful request shows **Authenticated**.
4. Select **StarIntel** from the normal watch-face picker.
5. Add **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** from the Tile picker.

The watch face defaults its three complication slots to the data sources from `wear-app` when that package is installed. The slots remain editable in the normal watch-face editor.

Release builds require HTTPS. Debug builds allow cleartext HTTP so a local/LAN StarIntel server can be tested during development. Bearer authentication is still required in debug builds.

## Build

The CI configuration uses JDK 17, Gradle 9.6, Android API 36, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :wear-app:assembleDebug :watchface:assembleDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
