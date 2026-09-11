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

## Configure

1. Install the `wear-app` APK on the watch.
2. Open **StarIntel** from the app launcher.
3. Enter the StarIntel server origin, for example `https://starintel.example`.
4. Enter the private StarIntel API key (`star_sk_v1_…`).
5. Tap **SAVE + TEST**. A successful request shows **Authenticated**.
6. Install the `watchface` APK and select **StarIntel** as the active watch face.
7. Add the **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** Tiles from the Wear OS Tile picker.

The watch face defaults its three complication slots to the data sources from `wear-app` when that package is installed. The slots remain editable in the normal watch-face editor.

Release builds require HTTPS. Debug builds allow cleartext HTTP so a local/LAN StarIntel server can be tested during development. Bearer authentication is still required in debug builds.

## Build

The CI configuration uses JDK 17, Gradle 9.6, Android API 36, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :wear-app:assembleDebug :watchface:assembleDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
