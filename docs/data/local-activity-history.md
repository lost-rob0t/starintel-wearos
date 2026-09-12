# Local activity history from existing StarIntel APIs

Issue: #29

## Decision

The watch does not wait for a new StarIntel server history endpoint.

Current StarIntel already exposes a real cumulative document counter and server timestamp at `GET /api/v1/stats`:

- `data.generated_at`
- `data.documents.total`

Every successful normal stats refresh records that pair locally on the watch. Activity is then derived from non-negative differences between successive cumulative totals.

This keeps the graph backed by real StarIntel API values without adding a private wearable-only server API.

## Semantics

For samples `A=(t1,total1)` and `B=(t2,total2)`:

- if `total2 >= total1` and the interval is within the allowed gap, graph value at `t2` is `total2 - total1`;
- if `total2 < total1`, the interval is a counter reset and is rendered as missing, never as negative activity;
- if the watch missed too much time between samples, the interval is a gap and is not connected/rendered as a spike;
- a real delta of zero remains zero;
- server/cache errors never create synthetic runtime values.

The result means **documents added between watch observations**, not an exact reconstruction of every server-side subinterval while the watch was offline.

## Bounded retention

Two bounded histories are maintained:

- detail samples: up to 360 samples and roughly 25 hours;
- hourly anchors: up to 760 samples and roughly 31 days.

Ranges:

- `1H`, `6H`, `24H` use detail samples;
- `7D`, `30D` use hourly anchors.

This caps storage and render work while preserving useful short- and long-range trends.

## Refresh behavior

The existing repository cache remains authoritative:

- stats cache TTL: 60 seconds;
- complication provider update period: 300 seconds;
- a graph request uses the same repository and does not create a high-frequency polling loop.

Successful real network refreshes update history. Reading a cached response does not manufacture another timestamp/sample.

Changing the configured StarIntel server or API key clears the local history so one server/credential scope cannot contaminate another graph.

## Rendering

`ActivityGraphComplicationService` publishes a `SMALL_IMAGE` complication generated on the watch from the bounded history.

Runtime behavior:

- real local-derived series when enough observations exist;
- `COLLECTING` empty state when history is insufficient;
- breaks in the line for gaps/resets;
- sparse ambient rendering;
- no fake runtime waveform.

Chooser/preview behavior may use a deterministic synthetic fixture marked `PREVIEW`; it is never presented as live StarIntel telemetry.

## Future server history

A future authoritative server-side history API can replace or augment this local source later. It is an enhancement, not a dependency. The graph renderer/range contract can consume that source without changing the watch-face composition.
