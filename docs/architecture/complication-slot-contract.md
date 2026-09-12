# Shared complication-slot contract

Issue: #26  
Parent: #23

This document defines the stable complication-slot ABI shared by the Watch Face Format resource, Wear providers, companion configuration, and deterministic tests.

## Stable slots

| ID | Key | Shape | Supported WFF v1 types | Intended use |
| ---: | --- | --- | --- | --- |
| 1 | `lower_left` | oval/circular | `SHORT_TEXT`, `RANGED_VALUE`, `EMPTY` | lower configurable complication |
| 2 | `lower_center` | oval/circular | `SHORT_TEXT`, `RANGED_VALUE`, `EMPTY` | lower configurable complication |
| 3 | `lower_right` | oval/circular | `SHORT_TEXT`, `RANGED_VALUE`, `EMPTY` | lower configurable complication |
| 4 | `left_edge` | `BoundingArc` | `RANGED_VALUE`, `EMPTY` | real left curved ranged-value complication |
| 5 | `right_edge` | `BoundingArc` | `RANGED_VALUE`, `EMPTY` | real right curved ranged-value complication |
| 6 | `text_region` | rounded rectangle | `SHORT_TEXT`, `LONG_TEXT`, `EMPTY` | compatible text/rectangular region |

IDs 1–3 predate this framework and remain stable. New faces may expose a subset of these regions, but provider code must not redefine their meaning.

## Blank-state invariant

An unassigned slot renders nothing. Every slot:

1. includes `EMPTY` in `supportedTypes`;
2. contains an explicit, content-free `<Complication type="EMPTY" />` renderer; and
3. resolves an unassigned or unavailable provider to the app-side `BLANK` state rather than sample text, a placeholder number, an icon, or decorative data.

The curved side slots additionally default to the system `EMPTY` provider. Their visible arcs exist only inside the `RANGED_VALUE` renderer, so an empty side slot cannot leave a fake meter behind.

StarIntel providers may return explicit real states such as stale/offline where their documented provider contract supports them. They must not synthesize values to avoid an empty region.

## Curved side slots

Slots 4 and 5 are real WFF `ComplicationSlot` elements with `BoundingArc` selection bounds. Their rendered sweep is derived from the provider's `RANGED_VALUE_VALUE`, `RANGED_VALUE_MIN`, and `RANGED_VALUE_MAX` expressions and is clamped to the slot arc.

They are deliberately provider-neutral. Heart rate and Samsung stress are approved example assignments only. A compatible ranged-value provider may be selected by the user. Samsung stress is considered supported only after a physical Watch5 Pro exposes it as a compatible complication provider.

## Provider ownership

Standard Wear OS/Samsung providers own their values, labels, units, ranges, icons, freshness semantics, and tap actions. Face code may style only fields WFF permits and must not reinterpret a provider's meaning.

StarIntel providers are app-owned but still publish only real cached/server-backed values or explicit unavailable/stale states.

No health complication data is forwarded to StarIntel by this framework.

## Validation

`scripts/check-watchface-slot-contract.py` verifies the stable IDs, shapes, type sets, explicit blank renderers, and empty defaults for side slots. Unit tests in `SlotCatalogTest` cover all-blank, all-configured, mixed, and provider-unavailable states.

The normal Nix validation path runs this contract check before Gradle/WFF build validation.
