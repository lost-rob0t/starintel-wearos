# Debug signing identity

`debug.keystore` is intentionally committed **public, debug-only** signing material.

The Android phone companion and Wear app both use package `actor.starintel.wear`. Google Play Services Wearable Data Layer requires the package name **and signing certificate** to match on both devices. Using the normal per-machine Android debug keystore made phone/watch communication fail whenever the two APKs were built on different machines, CI runners, or isolated Nix builds.

This key exists only to make local/CI/Nix debug builds deterministic and mutually compatible. **Never use it for a production release.** Production signing material must remain outside the repository.

Use `scripts/verify-companion-signing.sh` to verify a phone APK and Wear APK have the same signer before installation.
