# Geo activity complication

The Wear app exposes StarIntel geo activity without requiring a dedicated server endpoint.

## Data sources

The complication uses existing StarIntel APIs only:

- `/api/v1/stats` supplies the corpus-wide count of documents with dtype `geo` plus dtype `address`.
- `/api/v1/search?q=dtype:geo&limit=25` supplies a bounded sample of geocoded `geo` documents.
- `/api/v1/search?q=dtype:address&limit=25` supplies a bounded sample of geocoded `address` documents.

The bounded sample is not presented as a complete global distribution or as a time-ranked result. Current search semantics do not guarantee that the returned rows represent the most recent records, so the UI must not call these buckets "recent activity".

## Coordinate extraction

Accepted coordinate spellings are intentionally conservative:

- `data.lat` / `data.long`
- `data.latitude` / `data.longitude`
- `data.lon`
- equivalent top-level fields

Coordinates must be finite and inside latitude `[-90, 90]` and longitude `[-180, 180]`. Invalid rows are ignored. Longitude `+180` is normalized to `-180` so the dateline has one deterministic bucket.

## Coarse aggregation

The watch never renders exact source coordinates. Valid sample coordinates are reduced locally into deterministic 15-degree latitude/longitude cells. Only the 32 highest-count cells are retained. Ties are ordered deterministically by cell center.

This means:

- the numeric short-text complication is a corpus-wide count from `/api/v1/stats`;
- the image complication is a bounded coarse sample visualization;
- the image does not imply full-corpus coverage;
- no device location or wearer location is collected;
- no fake or decorative hotspots are used at runtime.

The visual map is intentionally only a structural latitude/longitude grid. It does not draw invented coastlines or imply geographic precision that the data does not support.

## Empty, stale, and preview states

Runtime states are explicit:

- `SETUP`: StarIntel URL/API key is not configured.
- `NO DATA`: no live or cached geo result exists.
- `NO GEO`: the real corpus-wide geocoded document count is zero.
- `NO SAMPLE`: geocoded documents exist, but the bounded search sample yielded no valid coordinates.
- `STALE`: cached data is being rendered after refresh failure or after the stale threshold.

Chooser preview data is deterministic synthetic data and is visibly labeled `PREVIEW`. Preview points are never written to the runtime cache and are never presented as telemetry.

## Caching and request bounds

- Geo refresh cache: 15 minutes.
- Stale threshold: 60 minutes.
- Search limit: 25 rows per dtype, at most 50 documents per refresh.
- Rendered buckets: at most 32.
- Provider update period: 15 minutes.

The geo cache is bound to a SHA-256 fingerprint of the configured server URL and API key. The credential itself is never stored in the geo cache. Changing server or credential therefore prevents a previous scope's cached geo sample from being reused.

## Future server enhancement

A future server-side coarse geo aggregate endpoint can replace the sampled search implementation without changing the complication's honest-state semantics. The Wear app does not wait on that endpoint and must continue to function against the existing APIs.
