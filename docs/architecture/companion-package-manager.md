# StarIntel Companion package manager

StarIntel Companion is the Android control surface for StarIntel Wear.

## Menus

- **Watch Setup** — server URL, API key transfer, and watch connection.
- **Watch Apps** — install/update StarIntel Wear plus Neon, Command, and Terminal.
- **Update Manager** — select `master`, the latest published release, or a specific versioned release.

The selected source is persisted on the phone and shared by the update and watch-app screens.

## Distribution

Every supported source publishes `update.json` schema 2. The catalog pins the ref, commit, version, logical artifact id, target device, package name, byte length, SHA-256, install order, and HTTPS asset URL.

`master` resolves to the rolling `master-channel` GitHub release. Versioned sources resolve to the exact release tag. `latest` resolves to the newest versioned GitHub release, including prereleases; the rolling master release is excluded from the version list.

## Phone → watch transport

The phone downloads and verifies an APK before transfer. APK bytes are streamed over Wear OS Data Layer `ChannelClient`, which supports payloads larger than `MessageClient`/`DataItem` limits and normally uses the direct Bluetooth connection when the paired watch is nearby.

The watch re-verifies byte count and SHA-256, inspects the APK package/version, rejects downgrades, and enforces signing-certificate continuity when updating an already installed package. Only then is the APK handed to Android `PackageInstaller`.

Wear OS remains authoritative for consent. StarIntel does not bypass unknown-source permission or package-install confirmation.

## Install ordering

Watch faces install before the Wear client. Wear self-update is always last because replacing the receiver package can terminate the active process.

Current catalog package IDs:

- `actor.starintel.watchface.neon`
- `actor.starintel.watchface.command`
- `actor.starintel.watchface.terminal`
- `actor.starintel.wear`

## Bootstrap

Data Layer requires the same package name and signing certificate on phone and watch. The watch must therefore have a StarIntel Wear build containing the package receiver at least once before Bluetooth package streaming can operate. After that bootstrap, routine StarIntel installs and updates do not require ADB or a VPN.
