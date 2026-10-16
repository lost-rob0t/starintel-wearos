# Issue 126: bounded import core and serialized UI handoff

This branch supplies the tested import engine, receipt adapter and early paste filter.
It is based on PR120 head `82c2a3ae332fdc42c61e487b2607d81210ea9278`.
It deliberately leaves `MainActivity.kt`, `StarIntelClient.kt`, `StarServerClient.kt`
and the manifest untouched: parent coordinates #122's first activity/client integration.
**File selection and lifecycle UI wiring are still required. This draft does not yet
make file import reachable from the app, and does not fully close #126.**

## Integration contract

1. Use Android's document picker (`ACTION_OPEN_DOCUMENT`) for JSON/NDJSON. Explicit
   user action starts staging on the I/O executor. Call `StreamingImport.stage` on
   the opened stream with an app-private, non-backed-up directory. Its returned
   file is a unique immutable snapshot; do not modify it. Staging copies with an
   8 KiB buffer; cancellation removes the partial copy. Manage disk quota and
   explicit cleanup in the UI owner. Never derive filenames from URI display names.
2. Persist the snapshot path, a stable associated checkpoint path, and destination
   identity before enabling Import. Restore these paths after recreation/process
   death. Do not generate a new checkpoint when resuming the same import. Preserve
   a blocked snapshot/journal for reconciliation; deleting it and reimporting may
   duplicate already accepted documents. Do not auto-start mutation on recreation.
3. Freeze server origin and authenticated principal for the attempt. The destination
   identity must bind both; use a stable non-secret principal identifier, not a bearer
   key. Resolve this via the verified auth context. The transport must use that same
   frozen session, not a dynamically changing config provider. The journal persists
   only a SHA-256 of this identity. A different identity or source hash rejects resume.
4. On explicit Import/Continue, run off the UI thread:

   ```kotlin
   val transport = BulkImportTransport { documents -> frozenClient.bulkCreate(documents) }
   val result = StreamingImport().run(
       snapshot, checkpoint, destinationIdentity, transport,
       { cancellation.get() },
       { progress -> /* post a bounded snapshot to the active view */ },
   )
   ```

   The existing shared `StarIntelClient.bulkCreate` has no mutation retry and caps
   response reads at 2 MiB characters. The adapter preserves it without shared-file
   edits, creates only a bounded JSONArray, and projects at most 8 KiB of receipt
   fields. Do not supply an unbounded reader or retrying transport. A future #122-owned
   raw-byte transport can implement the same `StreamingImport.Transport` interface.
5. Render `verifiedAccepted` as **server-accepted records**, not persisted/indexed
   or executed documents. Show state, unresolved count, last aggregate success/failure,
   correlation ID and optional job ID. Only `ready` can continue; `complete` needs no
   further writes. `blocked` and `in-flight` require reconciliation and cannot replay.
   `sourceOffset` is a verified record boundary, not the file size or a record total.
   Progress is delivered synchronously once per durable batch; UI should coalesce
   it into one latest-state callback, never enqueue an unbounded event history.
6. Cancellation finishes an outstanding request and persists its result before
   stopping at a safe boundary. It does not interrupt or retry an ambiguous mutation.
   A lock prevents concurrent runs on the same checkpoint, including separate engine
   instances. Exceptions during parsing are explicit failures; prior acceptance
   remains durable. Show prior progress even when a later record is invalid.
7. Apply `126-mainactivity-paste.patch` only after the parent/#122 integration owner
   authorizes the activity edit. It applies cleanly to PR120's head and introduces an
   early 256 KiB UTF-8 input filter and allocation-free submit check. Install filters
   before restoring drafts. Oversized replacement is rejected as a whole, preserving
   selected text; it does not truncate valid JSON into a different document.

## Verified backend contract and limits

Read-only source: `lost-rob0t/starintel-server` main
`54a5f65f0fb3ecc286da6fe426213f015a255562`, verified against remote HEAD.

- `cli/http-contract-documents.lisp`: `/api/v1/documents/bulk` accepts an array and
  declares inline `total`, `succeeded`, `failed`, `correlation_id`; async acceptance
  contains `status`, `job_id`, `total`, `status_url`, `correlation_id`.
- `source/frontends/http-authorization-routes.lisp`: validates/authorizes all inputs,
  then delegates inline versus async execution.
- `source/frontends/http-bulk-jobs.lisp`: inline at <=10 records, deadline two seconds;
  successes/failures are aggregate publish outcomes. Timeout can follow partial
  publication. No per-record reconciliation or idempotency guarantee was established.

Default batches: at most **10 records / 256 KiB encoded array bytes**, including
brackets/commas; record bound **128 KiB UTF-8**; nesting **64**. NDJSON requires one
object per physical line, allowing blank lines. JSON arrays allow ordinary whitespace.
Grammar/UTF-8 validation runs before sending each batch; schema validation is server
owned. Objects and records are never normalized or silently skipped by the parser.
A malformed record stops the run; it does not undo prior acknowledged batches.

Before every request, the engine atomically persists and fsyncs `in-flight` state.
Only an inline receipt whose integral counts match the exact batch and report all
successful with a nonempty correlation ID advances the offset. A missing/malformed/
partial/async receipt or transport exception blocks. A crash after sending but before
persisting success leaves `in-flight`, so restarting cannot duplicate the batch.
The journal stores the most recent receipt only, not a full receipt audit history.
Receipt errors do not log server bodies or document content.

No backend changes or new replay/receipt fields are invented. Async polling and
manual reconciliation need a separately verified backend contract. No changes to
Quasar PR110, transaction draft115, or Android PR120 branches.

## Complexity and evidence

For source bytes S, batch bound B, maximum record R, and number of batches K:

- Staging and SHA-256 verification each scan O(S) bytes with O(1) buffers; disk O(S).
- Parsing O(S), retained source working memory O(B + R); one outstanding request.
- Compatibility transport additionally holds bounded JSON/UTF-16 copies of B and
  the existing constant response cap (2 MiB characters); no whole-file JSONArray.
- Checkpoint memory/disk O(1); durable checkpoint I/O O(K), two saves per request.
- Restart hashes O(S) again for identity, then seeks directly to the accepted byte
  boundary. No claim of O(1) restart, exactly-once backend execution, or automatic
  recovery of an ambiguous mutation.
- Input paste checking reads at most the permitted byte prefix without allocating a
  copy of the offered text. Clipboard creation outside the input filter is not measured.

Actual supplemental command:

```sh
IMPORT_TEST_JARS=/workspace/import126-tools scripts/test-streaming-import.sh
```

16 tests passed on OpenJDK 21 using Java 17 source/target, org.json 20240303,
JUnit 4.13.2, Hamcrest 1.3, and compile-only Android 4.1.1.4 stubs. This environment's
JRE lacks the `--release 17` symbol archive; source/target validation is not a JDK17
API compatibility test. A procedural 1,000,000-record file is written to disk and
processed into 100,000 sequential batches with `-Xmx32m`; this proves that fixture
completes with the fixed JVM heap, not an Android/physical-device memory benchmark.
Tests cover huge records, Unicode, invalid JSON/UTF-8, partial and malformed receipts,
async acceptance, cancellation, restart, changed source/principal, concurrency,
crash after submission, no automatic replay, and compatibility-adapter bounds.
Android's InputFilter is compiled but its platform lifecycle has not been exercised.

Both Python manifest/release contracts passed. `git apply --check` passed for the
unapplied MainActivity patch. `nix flake check --no-update-lock-file --show-trace`
and `nix run --no-update-lock-file .#build-all` were attempted and failed because
Nix is absent. Full Gradle/APK, emulator, document-provider, lifecycle/insets/accessibility
and physical-device checks are unrun locally. Hosted CI results must be checked at
this branch's exact head before merge. No deploy or merge is authorized by this draft.
