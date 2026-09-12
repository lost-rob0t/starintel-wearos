# Geo activity complication

The Wear app uses existing StarIntel APIs only. `/api/v1/stats` supplies the corpus-wide `geo` + `address` document count; bounded `/api/v1/search?q=dtype:geo&limit=25` and `dtype:address` searches supply at most 50 coordinate-bearing sample documents.

Coordinates are reduced locally into deterministic 15-degree cells; exact source coordinates are never rendered. At most 32 coarse cells are shown. `+180` longitude normalizes to `-180`, poles are clamped to valid cell centers, invalid coordinates are ignored, and no wearer/device location is collected.

The image is explicitly a bounded sample, not a complete global distribution and not a claim of recency. Runtime empty/stale states are explicit (`SETUP`, `NO DATA`, `NO GEO`, `NO SAMPLE`, `STALE`). Preview hotspots are deterministic and visibly labeled `PREVIEW`.

Geo search bodies are bounded to 256 KiB, each query is capped at 25 rows, and the cache is scoped by a SHA-256 fingerprint of server URL + API key so configuration changes cannot reuse another scope's geo sample.
