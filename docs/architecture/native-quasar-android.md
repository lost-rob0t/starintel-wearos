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

Graph, dataset, agent, and actor screens are projections over real StarIntel
documents. They do not execute the Common Lisp Quasar runtime on-device. Native
agent supervision, streaming model providers, MCP, skills, actor execution,
budgets, checkpoint recovery, and validated `GraphOperationBatch` mutations
must attach to the versioned Quasar control-plane protocol as those operations
are exposed. Android must fail closed on protocol mismatch.

The release catalog treats Quasar as a phone-target artifact. StarIntel
Companion downloads it over HTTPS, checks the declared byte length and SHA-256,
inspects the APK package/version/signing certificate, and then starts an Android
`PackageInstaller` session requiring user confirmation.
