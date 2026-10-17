# Issue 126: native bounded file import

Implemented on the parent-approved combined PR130 head
`568319c674761f794efe91bca34170003bc1a8fe`: paging PR128, lazy detail PR127,
editor PR131 and asynchronous drafts PR130 are preserved. MainActivity changes
are confined to the Import route: a native file-import link and early UTF-8 paste
admission. Existing `draftFields` lifecycle and submission readiness guards remain.
`searchPage` and the backwards-compatible `search` wrapper remain unchanged.
Parent follow-up detail fixes 40136d5 (checked JSON exceptions) and 99d6385 (off-screen preview timing) are incorporated; the already-present paging Unit fix is not duplicated. Quasar PR110 and transaction draft115 are untouched. Keep issue #126 open until
hosted Android/runtime validation completes; no deployment or merge is performed.

## User flow and lifecycle

Import → **Import JSON / NDJSON file** opens the non-exported native
`FileImportActivity`, styled with QuasarDesign, FLAG_SECURE and its single inset owner.
It uses `ACTION_OPEN_DOCUMENT` and copies the chosen stream to app-private
`noBackupFilesDir` on one process-owned I/O worker. The source snapshot is immutable
for the lifetime of that import. The selection pointer is atomically persisted and
fsynced before Import becomes available. URI grants are not retained after copying.

Selecting/recreating/reopening the screen sends no mutation. **Import file** or
**Continue import** is explicit. `openBulkImportSession` captures the server and
credential once, reads `/auth/context`, requires bounded string `principal_id` and
`principal_type`, and binds their non-secret identity to the checkpoint. Credentials
remain in the existing Keystore-backed config and the in-memory session closure;
the journal contains only a hash of the destination identity. Changing settings
cannot redirect an in-flight import. Resuming under another principal/origin fails.

The worker retains only one latest progress snapshot and no Activity/View references.
The active Activity polls that snapshot with one callback while started. Recreation
reattaches to existing work; it never queues another mutation. Process restart reads
saved source/receipt metadata on the worker and requires another explicit Continue
for a verified boundary. **Stop after current batch** waits for the outstanding
request's recorded outcome. Background execution is not guaranteed; a killed
in-flight request remains unresolved and cannot replay.

The screen reports server-accepted records, source byte boundary, unresolved count,
last batch aggregate successes/failures, correlation ID and optional async job ID.
It does not claim records were indexed, stored durably, or executed. Complete imports
can be replaced explicitly. Partially accepted, ambiguous and in-flight imports
cannot be replaced/retried through this UI; retain their receipts for server-side
reconciliation. Parsing failures preserve previously accepted counts. Corrupt/missing
selection metadata fails closed. Interrupted never-submitted staging copies can be
cleaned on restart; journalled snapshots are preserved.

Small paste import remains available, with the existing draft readiness gate and
no automatic mutation retry. An early 256 KiB UTF-8 filter rejects an oversized
replacement as a whole, preserving the old selection. Admission/submit checks count
bytes without making an unbounded offered-text copy. Existing draft filters remain.

## Bounds and transport

| Resource | Bound |
| --- | --- |
| Selected source file | 1 GiB; copied with an 8 KiB buffer |
| Individual JSON object | 128 KiB UTF-8 |
| Encoded request array | 256 KiB including brackets and commas |
| Records per request | 10 |
| Outstanding mutation requests | 1 |
| JSON nesting | 64 |
| Raw receipt response | 8 KiB bytes, enforced while reading, including chunked responses |
| Checkpoint metadata read | 8 KiB; observed fixture journal 291 bytes |
| UI progress snapshots | 1 latest snapshot |

`BulkImportSession.sendBatch` sends the exact supplied UTF-8 bytes using fixed-length
streaming mode, disables redirects and performs no retry or compatibility fallback.
Existing `bulkCreate(JSONArray)` remains source-compatible. Its older compatibility
adapter remains tested but is not used by the file-import UI. The new raw transport
avoids serializing receipts through that adapter and enforces its smaller byte cap
before JSON parsing. Batches are still boundedly parsed to check object/count shape.

Strict JSON/UTF-8 source validation occurs before sending each batch. NDJSON requires
one object per physical line, allowing blank lines; JSON arrays allow normal whitespace.
Malformed or oversized records stop the run, without silently skipping data or undoing
previously accepted batches. Schema validation remains server-owned.

Before every request the engine atomically persists/fsyncs `in-flight` state. Only an
inline receipt with integral counts matching the exact batch, all successes, zero
failures and a nonempty bounded correlation ID advances the checkpoint. Missing,
malformed, partial, async and ambiguous outcomes block. A crash after the write but
before recording its receipt leaves `in-flight`; restart cannot replay it. The journal
stores the latest receipt, not an unbounded receipt history. No document/server-body
content or credentials are logged in failure messages.

## Backend evidence

Read-only source: starintel-server main `54a5f65f0fb3ecc286da6fe426213f015a255562`,
verified against remote HEAD during implementation.

- `cli/http-contract-documents.lisp`: bulk input is an array; inline response is
  `total/succeeded/failed/correlation_id`; async has `status/job_id/total/status_url`.
- `source/frontends/http-authorization-routes.lisp`: validates and authorizes inputs
  before choosing inline or asynchronous execution.
- `source/frontends/http-bulk-jobs.lisp`: inline at <=10 records with a two-second
  deadline. Timeout can follow partial publication. Counts are publish outcomes.
- `source/frontends/http-auth-routes.lisp`: `/auth/context` supplies `principal_id`
  and `principal_type` used by the frozen session.

No per-record receipt, exactly-once or idempotent replay contract was established.
Async polling and reconciliation are intentionally not invented by the client.
HTTP tests use synthetic fixtures; no live authenticated backend acceptance is claimed.

## Actual validation and reproduction

Raw evidence: [import-126-jvm.txt](../validation/import-126-jvm.txt).

```sh
IMPORT_TEST_JARS=/workspace/import126-tools scripts/test-streaming-import.sh
IMPORT_TEST_JARS=/workspace/import126-tools scripts/test-bulk-import-client.sh
python3 scripts/test-update-manifest.py
python3 scripts/test-release-contract.py
```

- **22 core/storage JVM tests passed**, including one million procedurally generated
  records from a 123,888,890-byte file under `-Xmx32m` (33,554,432-byte max heap).
  All one million sequential IDs were checked; 100,000 requests, maximum one in flight,
  10 records/request, fixture maximum request 1,241 bytes. Source SHA-256:
  `1169554edbb95961bcf3702c52eb8664a1270ef4ea76c147bb49553ca663c1ae`.
- A separate near-limit fixture sent 131,070-byte records in 262,143-byte batches
  (two records each), exercising the byte ceiling rather than just the count bound.
- **15 combined client/paging JVM tests passed**: four existing actor tests, four new
  bulk HTTP tests and seven PR128 paging tests. Exact UTF-8 bytes, frozen credentials,
  chunked Unicode response bounds, missing principals, redirects, 401/403/404/405/500,
  dropped responses and no automatic POST replay are covered.
- Source/storage tests cover malformed JSON/UTF-8, huge records, partial and async
  receipts, interrupted staging, restart, source/principal mismatch, concurrent runs,
  simulated process death, immutable selection recovery, corrupt pointer failure,
  safe orphan cleanup and blocked snapshot preservation.
- All 22 import/storage tests also compile and pass against Android36’s checked JSON API on the JVM. The 14 inherited detail tests pass against that API after the parent-requested fix.
- Both repository Python gates and shell/diff checks passed.
- The complete Quasar/shared Android/shared design tree plus the new Android journey
  type-compiled using Kotlin 2.2.0, real Android36/AndroidX APIs and the pinned
  f801b024 Edge binding. Generated R/BuildConfig constants are temporary compiler
  stubs; Java sources participate in symbol resolution. New import Java classes also
  passed javac compilation. This is not an APK build, resource link or runtime test.

Supplemental JVM tools: OpenJDK21 with Java17 source/target (this JRE has no
`--release 17` symbol archive), Kotlin2.2.0, org.json20240303, JUnit4.13.2, Hamcrest1.3.
The core script compiles the InputFilter using compile-only Android stubs; it does
not execute Android framework behavior. Dependency filenames are documented by both
scripts; source fixtures are deterministic and generated on disk, never preloaded.

The dedicated `quasar-file-import.yml` workflow builds the real APK/test APK, runs the
actual native route, injected document-picker result, explicit start, rotation during
an outstanding HTTP request and partial-receipt tests, then force-stops the app and
runs a fresh-process check that restored unresolved state cannot submit. It rejects
physical devices. The injected picker result is not a real external document-provider
UI test. These stages are authored/type-compiled but **unrun locally**.

Canonical Nix flake/build-all gates were attempted and blocked because Nix is absent.
Full Gradle/APK/resource-link, Android/emulator runtime, external SAF-provider UI,
accessibility and physical-device tests are not claimed locally. Check hosted results
at the final head before review readiness; earlier-head successes do not validate this
new integration. Keep the issue open while those stages remain pending.

## Complexity

For source bytes S, batch bound B, record bound R and batches K: staging, hashing and
parsing take O(S) time; source working memory is O(B + R), plus fixed response buffers.
The raw client holds bounded JSON/UTF-16 validation copies of B and at most 8 KiB raw
receipt bytes. Snapshot disk is O(S), temporarily holding old and new snapshots during
replacement. Checkpoint/UI memory and checkpoint disk are O(1); fsync work is O(K).
Restart re-hashes O(S) then seeks to the accepted boundary. Backpressure permits one
synchronous request only. No controlled Android performance or peak RSS win is claimed;
the 32 MiB result establishes completion within that fixture's JVM heap cap.

## Runtime correction integrated from PR130

Parent correction `7243f45dd9ba8d43c65a161716e8c70ae5f764d8` is merged.
It fixes the observed runtime `LengthFilter[]`/`DraftInputFilter` array-store crash,
adds two Android regression cases and corrects the separately failing external
process-death fixture's drawer navigation. Import's own filter append now also uses
`toList()` plus explicit `toTypedArray<InputFilter>()`; the existing import journey
asserts its runtime component type and presence of the UTF-8 filter.

Observed previous-head results, not inferred successes: draft job 117249207690
passed all 11 Android tests, then the external script failed with “Missing draft
fixture control” before editing. Visual jobs 117249207575 and 117249207754 both
reported the array-store crash in WorkspaceDraftFields.bind. This is an application
failure, not an emulator boot failure. The correction preserves the new 13-test
Android gate, followed by external process death, and both visual comparison legs.

Combined supplemental rerun: 22 import/storage tests, 15 client/paging tests,
13 draft coordinator tests and three drawer-navigation host tests pass. Both Python
repository gates and shell/diff checks pass. The full production source tree plus
all 13 selected Android draft tests and both import journey methods API/type-compile.
Android runtime results for the corrected head must still be observed in hosted CI;
API compilation does not replace those tests. Nix/Gradle/adb/KVM are unavailable
locally, so emulator and canonical build execution remain hosted.

## Aggregate mutation-safety review correction

The earlier `submitOnce` handler treated every JSONException as proof that no request
was sent. That was unsafe: shared StarIntelClient parses a JSONObject after a POST
has received a successful HTTP status. A committed create/paste import followed by a
malformed 2xx response could therefore clear `attempted` and enable another mutation.

Create and paste Import now wrap **only local payload construction** in
`prepareMutationPayload`, which marks that phase with `LocalPayloadFailure`.
Transport and response parsing happen outside that wrapper. Only this distinct local
failure clears the attempt guard; a response JSONException retains it, records
“Submission outcome unknown,” and preserves the guard across recreation. Shared
client interfaces are unchanged by this correction. Explicit, warned “new submission”
remains distinct from automatic retry.

Two preflight JVM regressions pass (17 total client/paging/preflight tests). The real
HTTP Android `MutationReceiptJourneyTest` models a server committing before returning
malformed 200 JSON, for both Create and paste Import. It checks invalid local input
sends zero POSTs, valid input sends exactly one, then repeated clicks and recreation
cannot produce another POST. It is wired into the file-import emulator script and
API/type-compiled, but its Android execution is not claimed before hosted results.

Approved tile PR129 head `9f201a2` (all four missing commits) and widget PR128 fix
`7428384` are merged into this aggregate without conflicts. Existing SearchPage/search
and draft readiness/lifecycle remain intact. Eight tile-queue JVM tests pass; the full
production tree plus mutation/import/draft/tile/widget Android fixtures type-compile.
The mandatory Python contracts pass. Nix flake/build-all were attempted again and
remain unavailable locally; final aggregate hosted CI and independent review are
required before any merge. No component PR is merged or deployed separately.
