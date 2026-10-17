# PR132 aggregate validation

PR132 targets `main` at `e80ab4558cc580eff26b13f8f86edf3578143730`.
The reviewed implementation head is `fa95a48c976f54fa32a724c56164ce785d42cfc9`;
this record adds documentation without changing that implementation.

## Scope and ancestry

The aggregate preserves the original native redesign PR120 ancestry and the
approved search, detail, draft/editor, tile and import workstreams. It includes
the PR130 corrections through `7243f45`, PR129 tile work through `9f201a2`, and
the widget fixture correction `7428384`. No component PR needs to be merged
separately first. Quasar's separate repository PR110 and transaction overlay
PR115 are untouched. Shared client and MainActivity changes were integrated
after coordination rather than concurrent overlapping edits.

The main-relative implementation diff has 106 files, 8,322 additions and 428
deletions. Its production scope is native Quasar and its shared Android API;
other APK production modules, runtime dependency pins and Keystore ownership
remain intact. Three AndroidX dependencies support the original native redesign.
FileImportActivity is non-exported. Existing search and bulkCreate entry points
remain available alongside additive pagination and bounded import interfaces.

## Evidence and outstanding gates

Local executed checks: 22 import/parser/storage tests (also repeated against
Android 36 JSON), 17 client/paging/mutation-preflight tests, 13 draft coordinator
tests, 14 detail tests, eight tile queue tests and three process-drawer fixture
tests passed. The million-record fixture completed under a 32 MiB JVM heap with
100,000 batches and one request in flight. Both required Python contracts and
main-relative whitespace validation passed after retargeting.

Production Kotlin and selected Android test sources type-compiled against
Android 36 and AndroidX with temporary generated resource constants. This is
not an APK build or emulator execution. Local Nix gates were attempted but Nix
is absent; no local Gradle/emulator or physical-device results are claimed.

Fresh pull-request synchronize runs are required after the main retarget for all
seven workflows: Android, draft storage, file import, before/after visuals, tile
bounds, widget journey and collector journey. Older stacked-base runs are not
final evidence. The visual workflow checks out the event's base SHA for the
before variant and head SHA for after, retaining the same fixture harness.
Both variants must pass against this main base. Pending checks do not establish
merge readiness. Final review and all seven completed workflows are required;
this task does not merge or deploy.

## Resource bounds

File import uses O(B + R) source memory for batch and record byte budgets,
O(S) staging disk and hashing/parsing work, O(1) latest receipt/progress state,
and one synchronous request at a time. Resume rehashes the staged source in
O(S) and then seeks to the verified accepted boundary. Ambiguous, partial or
asynchronous receipts remain blocked rather than automatically replayed.
Detailed bounds and reproducible commands are in
`docs/integration/126-streaming-import.md`.
