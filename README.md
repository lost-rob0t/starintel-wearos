# starintel-wearos

Wear OS surfaces for StarIntel, built around the aggregate `GET /api/v1/stats` server contract.

## Packages

This repository intentionally produces two separate installable packages:

- `wear-app/` — standalone Wear OS app, StarIntel server client/cache, complication data sources, and Tiles.
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

## Configure

1. Install the `wear-app` APK on the watch.
2. Open **StarIntel** from the app launcher.
3. Enter the StarIntel server origin, for example `https://starintel.example`.
4. Tap **SAVE + TEST**.
5. Install the `watchface` APK and select **StarIntel** as the active watch face.
6. Add the **StarIntel Ops**, **StarIntel Targets**, and **StarIntel Corpus** Tiles from the Wear OS Tile picker.

The watch face defaults its three complication slots to the data sources from `wear-app` when that package is installed. The slots remain editable in the normal watch-face editor.

Release builds require HTTPS. Debug builds allow cleartext HTTP so a local/LAN StarIntel server can be tested during development.

## Build

The CI configuration uses JDK 17, Gradle 9.6, Android API 37, Wear Tiles 1.6.2, and ProtoLayout 1.4.2.

```sh
gradle :wear-app:assembleDebug :watchface:assembleDebug
```

The watch face is WFF v1 to retain Wear OS 4 / API 33 compatibility. CI also validates `watchface.xml` with Google's WFF validator.
