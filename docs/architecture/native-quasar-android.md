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
The reusable `starintel-android` library now defines a local ECL/Tek9 boundary,
manifest-driven local actors with bounded mailboxes, atomic effect batches, and
a hosted Prolog-RLM agent contract. The APK fails closed while the ABI-specific
ECL image is absent; it never substitutes a parallel storage engine or claims a
local operation ran remotely. Connected durable mutations continue to use Star
server or Quasar control-plane operations. Android must fail closed on protocol
mismatch.

The visual shell is a native Qtile Electric command deck: near-black surfaces,
cyan/pink/amber semantic accents, large readable titles, persistent primary
navigation, honest runtime state, touch targets of at least 52 dp, and dense
operator data grouped into calm cards rather than a wall of default buttons.

The release catalog treats Quasar as a phone-target artifact. StarIntel
Companion downloads it over HTTPS, checks the declared byte length and SHA-256,
inspects the APK package/version/signing certificate, and then starts an Android
`PackageInstaller` session requiring user confirmation.
