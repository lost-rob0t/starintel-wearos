# StarIntel complication metric contract

Issue: #27  
Parent: #23  
Server dependency for history/rates: `lost-rob0t/starintel-server#157`

## Current server authority

As audited against current `lost-rob0t/starintel-server` `master`, the deployed stats contract is `GET /api/v1/stats`. It currently proves only:

- total document count;
- document counts grouped by `dtype`;
- total target-family count;
- server/service version;
- server `generated_at` time.

The current Wear repository therefore publishes only metrics derivable directly from that response. It does not infer history from local polling.

## Providers implemented now

| Provider | Source | Runtime behavior |
| --- | --- | --- |
| Status | configured/reachable/cache metadata | `SETUP`, `NO DATA`, `STALE`, `ONLINE`, or `OFFLINE` |
| Documents | `documents.total` | real compact count; stale cached value is prefixed `~` |
| Targets | `targets.total` | real target-family count |
| Target documents | `documents.by_dtype.target` | real dtype count |
| Investigation targets | `documents.by_dtype.investigation-target` | real dtype count |
| Sync freshness | local receive timestamp for a real server response | bounded age while fresh; explicit stale/offline/no-data state otherwise |

Provider chooser previews use semantic labels such as `DOCS` and `FRESH`; they intentionally contain no fake numeric examples.

A numeric zero is displayed only after a real response has been received. An unreachable watch with no cached response is `NO DATA`, never `0`.

## Required but not currently derivable

The following requested metrics remain unavailable until the server exposes a canonical, versioned contract:

- documents added this minute/hour/today/week/month/year;
- current/average/peak ingest rate;
- historical trend/delta values;
- unique-source count;
- geocoded-document count;
- alert count;
- queue/active-job status.

Server issue `lost-rob0t/starintel-server#157` defines the generic bounded `/v1/activity` contract for future real historical/range metrics and UTC boundary counts. Geo aggregation is tracked separately by #30/server work.

Per operator decision on 2026-09-12, the watch-face **activity graph visual may use deterministic synthetic/demo data** until the real server series exists. Synthetic graph points are app-owned visual data and must never be labeled as live StarIntel telemetry. This exception applies to the graph visual only; complication values and operational counters remain real or explicitly unavailable.

## Freshness and cache semantics

- repository cache TTL: 60 seconds;
- current stale threshold: 15 minutes from server `generated_at`;
- complication update period: 300 seconds;
- stale cached counts retain their real cached value and are visibly marked;
- no cached response plus transport failure is `NO DATA`;
- API keys remain in the existing secure key store and are never included in provider text/log output.

## Time policy

Future real historical period boundaries will be UTC unless/until the canonical server contract explicitly supports another timezone. The Wear client will consume the server's declared time basis rather than silently changing day/week/month semantics.

## Future activation

When server #157 lands, the Wear client may capability-discover the canonical `/v1` route and replace the synthetic graph source with real series data without changing the graph renderer contract. The compatibility `/api/v1/stats` path remains current runtime behavior until the server's canonical `/v1` migration (#58) is actually available.
