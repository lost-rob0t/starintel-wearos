# Collector runtime and repository boundary plan

Status: implementation plan for the collector branch. This document does not
authorize deployment, pushing, or edits to the in-progress Collector/Quasar
Mobile UI.

## Resolved authority

- StarIntel release: `0.10.1` from `schema/starintel-schema.lock.json`.
- Canonical schema: `nsaspy/star-lang` at
  `919833266723edc9bddb337d606f72ae25fb8ced`.
- Authority library: `org.starintel/core@1` with lower-camel wire fields.
- Android Common Lisp runtime: `starintel-edge` at
  `f801b024965488c21f93c52a1a9fd2aecd224700`.
- Android consumer: this repository pins the exact Edge revision in
  `flake.lock`; Gradle receives the immutable Nix runtime bundle rather than a
  copied ECL, Sento, or Common Lisp implementation.

## Ownership map

| Repository | Owns | Must not own |
| --- | --- | --- |
| `star-lang` | Core StarIntel schema, Star-Lang compiler/runtime semantics, generated language bindings | Collector-specific UI or deployment configuration |
| `starintel-edge` | Reusable Android ECL/JNI ABI, bundled Sento dependencies, closed local runtime dispatcher, ABI packaging/tests | Product UI, collector state, Star server plugins |
| `starintel-wearos` | Collector and native Android adapters, collector Star-Lang extension source, Star server add-on source for that extension, exact dependency pins, emulator acceptance | A fork of ECL/Sento, a second graph protocol, private deployment secrets |
| `starintel-pro-actors` | Dockerized remote transcription actor and manifest, external Whisper port, retry/idempotency behavior | Android runtime boot code or private credentials |
| `starintel-server` | Maintained add-on lifecycle and the registry hook that admits a trusted document-extension validator | Collector-specific schema definitions or copied actor implementations |
| `starintel-biz` | Private, non-secret configuration policy and trusted init composition for the collector add-on/actor | Runtime secret values in Git |
| `starintel-infra` | Out-of-store secret material installation and read-only container mounts | Product or schema authority |

## Contract flow

```text
Star-Lang 0.10.1 core
  + collector extension source (wearos)
  -> generated collector schema/Lisp validator (wearos)
  -> trusted collector add-on (wearos)
  -> maintained validator registration hook (starintel-server)

starintel-edge immutable Android bundle
  -> starintel-android binding/adapters (wearos)
  -> local bounded Sento actors in ART
  -> canonical StarIntel documents + durable offline queue

audio document
  -> local Android transcription actor, or durable remote target
  -> Docker transcription actor (starintel-pro-actors)
  -> transcript/audio-segment/speaker-turn documents
  -> Star server bulk ingest
```

All actor boundaries use typed messages and deterministic document identities.
No actor accepts Lisp evaluation text, shell commands, arbitrary filesystem
paths, or credentials in its payload.

## Collector document extension

The extension remains additive to `org.starintel/core@1`. Core media,
transcript, wireless, person, organization, relation, and actor-manifest
documents are reused unchanged. The collector extension should introduce only
the missing orchestration records:

- `collection-session`: session identity, lifecycle state, device/run
  provenance, start/end time, and bounded counters/references;
- `transcription-job`: source audio reference, requested actor/backend/model,
  lifecycle state, attempts, and result/error references;
- typed `transcribe-capture`, `transcription-completed`, and
  `transcription-failed` messages.

The `.star` import must lock `org.starintel/core@1` version `0.10.1` and the
SHA-256 of the canonical `core.star`. Generated JSON Schema and Common Lisp are
compiler outputs and must never be hand-edited.

The server add-on is a separate ASDF system inside this repository. Loading it
through `star:load-addon` registers only the extension dtypes and lifecycle
hooks. It must use a maintained server registry API, unregister its validators
on stop/reload, and fail closed when its generated schema digest or core lock
does not match the server.

## Transcription actors

Two implementations share the same message/document contract:

1. The Android actor is local, bounded, and offline-capable. It uses the Edge
   runtime and the pinned on-device Whisper model. Android lifecycle, model
   storage, and permissions remain WearOS concerns.
2. The remote actor reuses `WhisperTranscriberActor` and `OpenWhisperBackend`
   already present in `starintel-pro-actors`. It is generalized from the
   podcast pipeline instead of duplicated, receives a dedicated manifest and
   named Dockerfile target, and is covered by the repository's container
   coverage test.

Remote delivery is at-least-once. The actor therefore derives transcript IDs
from the source audio content hash plus backend/model identity and treats an
existing identical document as success.

## Private configuration

`starintel-biz` will own the checked-in configuration shape and trusted init
composition only. Expected inputs are references such as server URL, actor ID,
model name, queue bounds, and paths to credential files. Secret values remain
operator material installed outside Git and the Nix store, then mounted
read-only into the server/actor containers. Neither Android assets nor actor
manifests contain server keys or Whisper service credentials.

## Current gate and sequence

The following order is required:

1. Keep the in-progress Collector UI and Quasar Mobile files reserved until the
   user explicitly approves UI edits.
2. Land the already-pushed Edge runtime commit and keep WearOS pinned to its
   immutable revision.
3. Land the WearOS non-UI collector/runtime work after separating it from the
   reserved UI/Quasar files; rerun unit, Nix, emulator, mocked wireless, large
   WiGLE, and ART actor gates on the exact commit.
4. Migrate `starintel-server` and `starintel-pro-actors` to the live 0.10.1
   authority commit. Their current Forgejo default branches still resolve
   StarIntel 0.9.1. The existing unmerged server 0.10.1 branch also pins an
   older Star-Lang commit and cannot be treated as current authority.
5. Open/resolve the server-owned issue for a document-validator registration
   hook, then implement the WearOS-owned extension/add-on against that API.
6. Generalize the existing Docker Whisper actor in `starintel-pro-actors` after
   its schema lock is current; do not create a parallel transcription engine.
7. Add the `starintel-biz` configuration composition and infra read-only
   material mount with fixtures containing placeholders only.
8. Run local Docker actor integration against a local Star server, then the
   Android emulator end-to-end suite. Deployment remains a separate,
   explicitly authorized step.

## Acceptance evidence

- schema lock check resolves the exact canonical commit;
- generated extension artifacts reproduce byte-for-byte from `.star` source;
- add-on load/reload/unload tests prove validator registration lifecycle;
- Docker actor health, typed request, idempotent retry, and transcript ingest
  tests pass without external network access;
- Android unit tests use mocked Wi-Fi/BLE inputs;
- emulator imports and replays at least 325,000 WiGLE rows with a bounded
  projection queue;
- emulator boots ECL inside ART and completes a real local Sento actor
  round-trip with Wi-Fi/mobile data disabled;
- exact APK install paths and collector launch are verified;
- secret scans prove no credential material entered Git, logs, APK assets, Nix
  derivations, or test fixtures;
- no deployment command runs before explicit user approval.
