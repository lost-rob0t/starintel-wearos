# Star Wireless geotemporal mapping rules

Star Wireless keeps raw observations immutable in its local SQLite history. Dataset assignment is a projection performed by rules; changing a rule never rewrites the source observation.

## Rule authority

The canonical rule is a Prolog `dataset_rule/9` term:

```prolog
dataset_rule(
    RuleId,
    Dataset,
    StartMs,
    EndMs,
    CenterLat,
    CenterLon,
    RadiusMeters,
    Kinds,
    Priority
).
```

A rule may use `any` for time, geography, or kinds. When multiple rules match, the highest priority wins. Ties are deterministic by rule id/dataset term ordering.

## Human grammar

The Android/Quasar/StarATAK UI can emit a compact text form which is parsed by `mapping_grammar.pl`:

```text
map field_ops to dataset downtown
  from "2026-09-20T20:00:00Z"
  to "2026-09-21T04:00:00Z"
  within 750 meters of 39.9612,-82.9988
  kinds [wifi,audio,video,location]
  priority 80.
```

Map selection is therefore just rule construction: center point + radius + time window + kinds + destination dataset.

Examples without spatial/time limits:

```text
map all_wireless to dataset wireless_archive
  anytime
  anywhere
  kinds [wifi]
  priority 10.
```

## Runtime path

```text
Star Wireless raw SQLite
        |
        v
collector.observation_batch
        |
        v
Hackmode Android typed service
        |
        v
hm android-bridge
        |
        +--> Hackmode operation/Tek9 evidence
        |
        +--> SWI-Prolog / Prolog-RLM mapping rules
        |
        v
canonical StarIntel document/outbox
```

The Android APK does not silently bundle a second Prolog authority. Termux SWI-Prolog or the Prolog-RLM sidecar runs the rules until the dedicated Android Prolog ABI lane is packaged.
