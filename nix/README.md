# Nix packaging inputs

`debug.keystore` is a **public, debug-only** signing identity committed so local, CI, and pure Nix builds produce APKs signed by the same certificate. This is required for the phone companion and Wear app to communicate through the Wearable Data Layer and makes debug APK signing deterministic.

The keystore is not a secret and must never be used to sign a production release. Production signing material belongs outside this repository.

`deps.json` is the nixpkgs Gradle dependency lock consumed by `gradle.fetchDeps`. It pins the Maven/Google/Gradle artifacts used by the sandboxed APK derivation.

Refresh Gradle dependencies only when build dependencies intentionally change:

```sh
updater="$(nix build --no-link --print-out-paths .#gradle-deps-update)"
"$updater"
git diff -- nix/deps.json
nix build .#all-apks
```

Review and commit the `nix/deps.json` diff together with the dependency change.
