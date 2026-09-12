# Standard Wear OS / Samsung provider compatibility

Issue: #28  
Parent: #23

## Architecture

The watch face does not collect heart rate, steps, stress, battery, weather, calendar, or exercise data itself. Wear OS complication data sources publish data and the watch face renders the provider-selected complication type.

This is intentionally provider-neutral. The user selects a compatible installed provider through the Wear OS / Samsung complication chooser. No Samsung Health database access, Health Connect scraping, sensor polling, or StarIntel health upload exists in this feature.

## Slot compatibility

| Slot family | WFF v1 types | Behavior |
| --- | --- | --- |
| left/right curved side slots | `SHORT_TEXT`, `RANGED_VALUE`, `EMPTY` | `RANGED_VALUE` drives the real curved fill; `SHORT_TEXT` displays the provider text without inventing a range |
| lower circular slots | `SHORT_TEXT`, `RANGED_VALUE`, `EMPTY` | text or provider-owned range rendered in the circular region |
| text region | `SHORT_TEXT`, `LONG_TEXT`, `EMPTY` | calendar/weather/activity text where the selected provider supports it |

Supporting both `SHORT_TEXT` and `RANGED_VALUE` is deliberate: providers are free to expose one or more compatible complication types. We do not convert a text-only provider into a fake numeric range.

## Expected provider categories

The slot contract is compatible with installed providers for:

- heart rate;
- steps;
- Samsung stress, only if Samsung exposes it to the complication chooser as a compatible type;
- watch/phone battery where exposed;
- weather / temperature providers;
- date / calendar providers;
- exercise / activity providers that publish a compatible complication type.

There is no hard-coded provider assignment for HR or stress. They are approved example assignments only.

## WFF v1 defaults vs installed providers

A `defaultSystemProvider` is not required when the slot supports `EMPTY`. StarIntel deliberately leaves the shared side/text slots empty by default so the visual state is honest.

Built-in/default provider identifiers are version-specific. The product therefore does not treat an identifier such as `HEART_RATE` as proof that every WFF v1 / Watch5 Pro build exposes that provider. Installed Samsung/Wear provider availability is resolved by the system chooser on the real device.

## Rendering rules

- provider text/value is rendered as supplied;
- a ranged provider uses its own min/max/value fields for arc sweep;
- a text-only provider remains text-only;
- empty/unassigned is invisible;
- unavailable provider data does not get placeholder numbers;
- face-owned accents may be themed later by #32, but provider semantics are not changed;
- ambient mode never starts health collection or additional polling.

## Physical Watch5 Pro gate

CI can prove WFF type compatibility and blank-state behavior, but it cannot prove which Samsung provider services are installed/exposed on the user's watch.

Real-device verification must record:

1. Heart-rate provider appears in the chooser and can populate at least one compatible slot.
2. Steps provider appears and renders.
3. Samsung stress is recorded as supported only if it appears and supplies a compatible type.
4. Battery, weather, date/calendar, and available exercise/activity providers render without custom acquisition code.
5. Both side slots remain blank when no provider is selected.
6. No health complication data is transmitted to StarIntel.

References: Android Developers, "About complications" and "Provide useful data through complications" (current Wear OS documentation).
