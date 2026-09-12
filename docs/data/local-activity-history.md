# Local activity history from existing StarIntel APIs

Issue: #29

The watch does not wait for a new StarIntel server history endpoint. `GET /api/v1/stats` already supplies `data.generated_at` and cumulative `data.documents.total`; successful live refreshes persist bounded local observations and the graph renders non-negative deltas between them.

A decrease in the cumulative counter is a reset and becomes a gap, never negative activity. Long offline intervals are gaps rather than fabricated spikes. A real zero delta remains zero. Detail history is bounded to roughly 25 hours / 360 samples; hourly anchors are bounded to roughly 31 days / 760 samples. `1H`, `6H`, and `24H` use detail history; `7D` and `30D` use hourly anchors.

Changing server URL or API key clears history. Runtime data is always derived from real API observations. The chooser-only fixture is deterministic and labeled `PREVIEW`.

A future server history endpoint may replace or augment this source, but it is not a dependency.
