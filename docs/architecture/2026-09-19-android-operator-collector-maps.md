# StarIntel Android tactical bootstrap

Issue: #105

## Package split

The phone-side tactical client remains a suite of separately installable APKs:

| Module | Package | Bootstrap responsibility |
| --- | --- | --- |
| `operator-app` | `actor.starintel.operator` | ATAK-style command shell and cross-app launch/handoff |
| `collector-app` | `actor.starintel.collector` | Explicit foreground collection session and Android Share ingestion |
| `maps-app` | `actor.starintel.maps` | Geo-document projection and tactical map surface |
| `quasar-app` | `actor.starintel.quasar` | Existing native Star/Quasar data and control client |
| `phone-app` | `actor.starintel.wear` | Existing Wear companion, package catalog, and update manager |

The split is deliberate. A sensor lifecycle bug must not kill the map. A map-engine
upgrade must not change Wear Data Layer identity. Operator can coordinate apps
without owning all of their permissions.

## Android interop contract

`android-contracts` is a pure JVM module containing Android-local package names,
actions, and extras. Version 1 defines:

- `actor.starintel.action.OPEN_MAP`
- `actor.starintel.action.COLLECT`
- direct geo extras: document id, label, latitude, longitude
- bounded geo JSON array payload for multi-document handoff

This is only an Android IPC contract. It does not redefine StarIntel document,
target, actor, graph, JSON-LD, Quasar, or star:// semantics.

## Operator bootstrap

Operator provides a dark tactical launcher for:

- Maps
- Collector
- Quasar
- Wear Companion

The first slice deliberately avoids duplicating Quasar screens. Later work can
add a task/target dashboard and selected-document handoff once the corresponding
server capability is available.

## Collector bootstrap

Collector has two real ingestion paths in the bootstrap:

1. a user-started foreground service with a persistent Android notification;
2. exported Android Share targets for `ACTION_SEND` and
   `ACTION_SEND_MULTIPLE`.

Shared text and URI metadata are normalized into a bounded app-private JSON
inbox (100 rows, bounded text and URI counts). This bootstrap does not silently
open shared content, does not start sensor capture by itself, and does not claim
server persistence.

Next collector slices should replace the local-only inbox with the canonical
JSON-LD Observation/document envelope, durable upload queue, idempotency key,
provenance, server authentication, and capability-scoped adapters for
user-authorized location/media/audio sources.

## Maps bootstrap

Maps renders only geo payload supplied through the inter-app contract. Direct
lat/lon and a bounded JSON list are accepted. Invalid coordinates are dropped.

The current renderer is dependency-free and intentionally simple: an
equirectangular world projection, grid, crosshair markers, document labels, and
an explicit `NO GEO DOCUMENTS` state. It is a real data projection, not a fake
basemap.

The next map-engine slice should add a real offline-capable basemap/layer engine,
StarIntel geo-document queries, linked non-geo document expansion, time/entity
filters, target/task overlays, import/export, and testable layer adapters.

## Wear OS handoff

Wear remains a companion/operator surface rather than a tiny copy of the phone
map. The next integration should let a watch action identify a document/target,
open the appropriate phone app, and send compact acknowledgement/status back
over the existing signed Wearable Data Layer identity.

## Build/release contract

All new APKs use API 36, minSdk 26, the repository-owned development signer, and
the repository's synchronized version. CI and Nix `build-all` must build and
stage all three alongside the existing phone, Quasar, Wear, and watch-face APKs.
Master/tagged update catalogs treat them as phone-target artifacts.
