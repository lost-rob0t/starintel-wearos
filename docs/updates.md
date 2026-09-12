# StarIntel Wear OS updates

StarIntel supports the same two update channels from Nix/ADB and from the Android/Wear apps.

## Channels

- `master` is the rolling development channel. The repository currently uses `main` as its default branch, so `--source master` resolves a real `master` branch when one exists and otherwise resolves the remote default branch. Published in-app development builds live on the rolling `master-channel` prerelease.
- `tagged` means the newest normal GitHub release/tag. The in-app UI also accepts an exact tag such as `v0.2.0`.

Every update is resolved to an immutable commit before build or install.

## CLI / Nix

```sh
nix run .#update -- --source master --watch WATCH_IP:ADB_PORT
nix run .#update -- --latest-tag --watch WATCH_IP:ADB_PORT
nix run .#update -- --tag v0.2.0 --watch WATCH_IP:ADB_PORT
nix run .#update -- --check --source master
```

Add `--phone PHONE_IP:ADB_PORT` to update the matching companion in the same run. Use `--build-only`, `--resolve-only`, or `--list-tags` for inspection without installation.

Selecting an older tag is a rollback rather than an update. Android normally rejects version-code downgrades. The CLI exposes an explicit `--allow-downgrade` switch for development/ADB rollback; the in-app updater intentionally does not bypass downgrade protection.

## In-app updates

Both the Android companion and StarIntel Wear expose an **UPDATES** screen.

The screen can check:

- **MASTER** — rolling `master-channel` development release;
- **LATEST TAG** — newest non-prerelease tagged release;
- **Exact tag** — a user-entered immutable tag.

The app downloads `update.json`, validates schema and package metadata, downloads only the APKs needed by that device, checks byte size and SHA-256, and then hands the verified APK to Android `PackageInstaller`.

On the watch the update order is deliberate:

1. install/update `actor.starintel.watchface`;
2. install/update `actor.starintel.wear` last so self-replacement cannot interrupt the watch-face update.

Android may require the user to allow StarIntel as an install source and may display an install confirmation. The updater does not bypass those OS protections.

## Update feed

Each release publishes `update.json` plus:

- `starintel-phone.apk`
- `starintel-wear.apk`
- `starintel-watchface.apk`

The manifest pins channel, source ref, source commit, Android version code/name, package ID, HTTPS asset URL, byte size, and SHA-256 for every APK.

## Signing

A stable Android signing identity is mandatory. The phone and Wear packages intentionally share application ID `actor.starintel.wear`; replacement installs and Wear Data Layer identity both depend on compatible signing certificates. The watch-face APK is signed by the same release key for one coherent update set.

The `Update Releases` workflow publishes update assets only when all of these repository secrets exist:

- `STARINTEL_UPDATE_KEYSTORE_B64`
- `STARINTEL_UPDATE_KEYSTORE_PASSWORD`
- `STARINTEL_UPDATE_KEY_ALIAS`
- `STARINTEL_UPDATE_KEY_PASSWORD`

If any are missing, the workflow reports that update publishing was skipped instead of emitting unusable ephemeral debug-signed updates.

## Validation

PR CI verifies:

- branch/default-branch fallback behavior;
- exact and newest-tag resolution;
- update-state comparisons;
- update manifest generation and JSON validity;
- malformed update-manifest rejection;
- phone and Wear compilation/tests;
- Nix build/install/update app evaluation;
- existing WFF v1 validation.
