# Local activity history from existing StarIntel APIs

Issue: #29

The watch does not wait for a new StarIntel server history endpoint. `GET /api/v1/stats` already supplies `data.generated_at`, cumulative `data.documents.total`, and `data.documents.by_type`; successful live refreshes persist bounded local observations and the graph renders non-negative deltas between them.

A decrease in the cumulative counter is a reset and becomes a gap, never negative activity. Long offline intervals are gaps rather than fabricated spikes. A real zero delta remains zero. Detail history is bounded to roughly 25 hours / 1,600 samples; hourly anchors are bounded to eight days / 200 samples.

The activity app and complications expose `1M`, `5M`, `15M`, `1H`, `6H`, `1D`, and `1W`. Minute through day ranges use detailed observations; the week range uses hourly anchors. Total documents and the three busiest document types render as separate time-positioned line series. No bars or synthetic smoothing are used.

The Auto complication rotates once per minute across ranges with at least two real delta samples. If history is still sparse, it falls back to `1H` and clearly renders `COLLECTING`.

Changing server URL or API key clears history. Runtime data is always derived from real API observations. The chooser-only fixture is deterministic and labeled `PREVIEW`.

A future server history endpoint may replace or augment this source, but it is not a dependency.
