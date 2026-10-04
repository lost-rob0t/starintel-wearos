# Native Quasar Android

`quasar-app` is a separate native APK (`actor.starintel.quasar`). It mirrors the
top-level Quasar route model without embedding the browser UI: Home/Stats,
Graphs, Datasets, Documents, Add document, Agents, Actors, Import, Targets, and
Settings.

The first native slice is a direct Star server client. It stores the bearer key
under an AES-GCM key in Android Keystore, bounds HTTP responses, uses the current
versioned document/search/target operations, and falls back to a documented
legacy read route only after an explicit 404/405. Settings tests capability
discovery before reporting the server connected.

Settings supports two equivalent entry paths: direct `star_sk_v1_...` API-key
authentication, or username/password exchange through `POST /auth/login`.
Passwords remain in the visible activity only long enough to mint the bearer
key and are cleared after success. Both paths validate the bearer with
`GET /auth/context` before replacing the previous Keystore-encrypted key.

Graph and dataset screens remain projections over real StarIntel documents.
The native client boots the exact pinned StarIntel Edge ECL image; Common Lisp
and Sento own the managed actor runtime, catalog, and dispatch. Android neither
defines a second actor supervisor nor turns manifest entrypoint text into code.
Tek9-backed operations remain unavailable until the canonical Edge image ships
them, and the UI does not substitute a parallel local store. Connected durable
mutations continue to use Star server or Quasar control-plane operations.
Android fails closed on ABI or protocol mismatch.

The visual shell is a mobile-first native Qtile Electric command deck: near-black surfaces,
cyan/pink/amber semantic accents, large readable titles, persistent primary
navigation, honest runtime state, touch targets of at least 52 dp, and a
single-column phone layout that expands to two columns on large screens. Its
route catalog retains the desktop top-level surfaces while presenting them for
touch instead of copying the desktop chrome.

The release catalog treats Quasar as a phone-target artifact. StarIntel
Companion downloads it over HTTPS, checks the declared byte length and SHA-256,
inspects the APK package/version/signing certificate, and then starts an Android
`PackageInstaller` session requiring user confirmation.
