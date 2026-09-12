# StarIntel complication metric contract

Issue: #27  
Parent: #23  
Future server-side history enhancement: `lost-rob0t/starintel-server#157`

## Current server authority

As audited against current `lost-rob0t/starintel-server` `master`, the deployed stats contract is `GET /api/v1/stats`. It currently proves only:

- total document count;
- document counts grouped by `dtype`;
- total target-family count;
- server/service version;
- server `generated_at` time.

The current Wear complication providers therefore publish only metrics derivable directly from that response.

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

## Metrics not directly exposed by `/api/v1/stats`

The stats endpoint does not directly return:

- documents added this minute/hour/today/week/month/year;
- current/average/peak ingest rate;
- historical trend/delta series;
- unique-source count;
- geocoded-document count;
- alert count;
- queue/active-job status.

Issue #29 does **not** wait on a server change for the graph. It records successful real `/api/v1/stats` observations locally and derives document-activity deltas from the cumulative total. Long offline intervals remain gaps and counter resets are explicit. Deterministic synthetic graph data is restricted to chooser/preview fixtures and is not presented as live telemetry.

Server issue `lost-rob0t/starintel-server#157` remains a future enhancement for authoritative server-side historical buckets, not a blocker for the watch-face graph.

## Freshness and cache semantics

- repository cache TTL: 60 seconds;
- current stale threshold: 15 minutes from server `generated_at`;
- complication update period: 300 seconds;
- stale cached counts retain their real cached value and are visibly marked;
- no cached response plus transport failure is `NO DATA`;
- API keys remain in the existing secure key store and are never included in provider text/log output.

## Time policy

Future server-provided historical period boundaries will be UTC unless/until the canonical server contract explicitly supports another timezone. Local watch history uses server `generated_at` timestamps and does not rewrite them into fake subintervals.

## Future activation

When server #157 lands, the Wear client may capability-discover the canonical `/v1` route and replace/augment the local-history source without changing the graph renderer contract. The compatibility `/api/v1/stats` path remains current runtime behavior until the server's canonical `/v1` migration (#58) is actually available.
