# StarIntel Wear OS agent contract

This repository owns the Android phone companion, native Quasar Android app,
Star Wireless Collector, Hackmode Android control surface, StarIntel Wear app,
and resource-only Watch Face Format packages. Keep them as independent APKs
built and released from one pinned Nix/Gradle workspace.

## Persistent product decisions

- `phone-app` is the StarIntel Companion and retains package ID
  `actor.starintel.wear` so its Wearable Data Layer identity continues to match
  `wear-app`.
- `quasar-app` is a separately installable, native Android app with package ID
  `actor.starintel.quasar`. Do not merge it into the companion and do not replace
  it with a WebView wrapper.
- `collector-app` is Star Wireless with package ID `actor.starintel.collector`.
  Raw Wi-Fi/location observations and WiGLE imports are append-only source
  history; dataset mapping is a Prolog projection and must not rewrite evidence.
- `hackmode-app` is the signed Android control surface with package ID
  `actor.starintel.hackmode`. Full interactive Hackmode remains in Termux/Kali;
  cross-app automation uses the bounded `HACKMODE-ANDROID/1` typed operation
  contract and may not expose an arbitrary shell command.
- `phone-app` owns the signature-protected inter-app permissions and the
  `actor.starintel.config` provider. Shared config is non-secret control-plane
  data only. API keys, passwords, bearer tokens, private keys, and session
  credentials must remain in the owning app's Keystore-backed storage.
- The companion is the package catalog for both phone and watch. Phone packages
  install locally through Android `PackageInstaller`; watch packages cross the
  Wearable Data Layer and install on the watch.
- Android must always obtain explicit user approval for package installation.
  Never request device-owner privileges, silently install, or bypass the unknown
  sources control.
- Release catalogs are HTTPS-only, size-bounded, SHA-256 pinned, and declare the
  expected package ID. Updates must reject package mismatches, downgrades, and a
  signing-certificate change when the target is already installed.
- Keep master-channel and tagged releases capable of reproducing every APK from
  the same commit. `nix run .#build-all` is the canonical build and must stage all
  APKs in `build/nix/`.
- Release builds accept HTTPS Star server origins only. Debug builds may use
  cleartext HTTP for LAN development. Bearer keys are stored with Android
  Keystore encryption and must never enter logs, exports, release manifests, or
  the update catalog.
- Quasar supports direct `star_sk_v1_...` API keys and username/password login
  through `POST /auth/login`. Passwords are never persisted. A successful login
  mints a bearer API key, validates it against `GET /auth/context`, and only then
  commits the key to Keystore-backed storage. Failed login or validation must
  preserve the previously working credential.
- Quasar consumes the current Star server contract. Prefer machine-readable
  capability/OpenAPI discovery and versioned `/api/v1` operations. A legacy
  fallback is permitted only for an explicit 404/405 and only for a documented
  compatibility route.
- Preserve Quasar's native top-level feature map: Home/Stats, Graphs, Datasets,
  Documents, Add document, Agents, Actors, Import, Targets, and Settings. A
  feature may report an unavailable server capability, but it must not fabricate
  data or pretend a local-only action ran on the server.
- The canonical Quasar runtime remains the Common Lisp runtime. Android is a
  native client/projection, not a second protocol authority. Durable mutations
  must go through Star server or Quasar control-plane operations; UI code must
  not invent a parallel graph protocol.
- Physical-device claims require verification on the target device. Passing
  Gradle, Nix, unit, manifest, signing, and WFF gates is not a substitute for a
  Galaxy Watch5 Pro or Android phone smoke test.

## Required gates

Run before proposing a merge:

```sh
python3 scripts/test-update-manifest.py
nix flake check --no-update-lock-file --show-trace
nix run --no-update-lock-file .#build-all
```

When changing companion/Wear transport or signing, also run
`scripts/verify-companion-signing.sh` on the staged phone and Wear APKs. When
changing watch faces, run all three `scripts/check-watchface-*.py` checks and the
WFF v1 validator used by CI.
