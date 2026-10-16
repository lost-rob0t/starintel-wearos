# Issue 125: bounded record detail handoff

Base: PR #120 `82c2a3ae332fdc42c61e487b2607d81210ea9278` on
main `e80ab4558cc580eff26b13f8f86edf3578143730`. Both were rechecked.
No changes to PR #120, Quasar PR #110, shared clients, or Lisp stores.
The workspace `.agents` directory is empty; this repository has no `.agents/skills`.
Repository `AGENTS.md` was read and its independent gates attempted.

## Integration ownership

Parent assigned #122 the first MainActivity integration; #123/#125/#126 supply
independent components and separate patches. Accordingly **MainActivity is not
modified in this branch**. This PR alone does not change the live detail screen
and must not close #125 until the integration patch is applied and validated.

After #122's approved integration, apply/adapt only
`docs/integration/issue-125-main-activity.patch`. It replaces `loadDocument` only,
preserves `client.document(id)` and `runApi`, and creates `RecordDetailView` on
success. The patch passes `git apply --check` against the exact PR #120 head;
it has not been checked against #122's future changes. The identity token rejects
older responses even when consecutive requests have the same record ID.
Detached content rejects responses. Network requests retain the existing client
response cap and failure handling; this change does not retry mutations.

Public component contract:

- `RecordDetailView(context, root, id)`: native LinearLayout that unwraps the
  existing document/data response envelopes, renders bounded scalar summary and
  provenance, and owns lazy Structured fields / Raw JSON expansion.
- `RecordDetailSession(root, data, uiExecutor)`: immutable parsed objects; all
  request/cancel/close calls and callback delivery must use the UI thread.
  Constructor does no serialization. Only one selected expansion is displayed;
  selecting another cancels the previous one. Collapse cancels; detach closes
  the worker and clears preview text. Reattachment starts collapsed.
- `RecordDetailPreview.raw/fields`: worker-only bounded traversal. Results are
  display text, not an export or a mutation payload. Truncation is explicit.

The app has no documented streamed full-record download contract in this scope.
No export endpoint is invented. Small complete raw previews remain selectable;
truncated previews explicitly say full export is unavailable in this view.
Adding full streaming export remains a separate server/client integration.

## Bounds and complexity

Old detail preparation pretty-printed the complete response while collapsed,
retaining another O(B) string; nested structured values were fully serialized
before truncation, on the UI thread. Input parsing and the parsed JSON tree still
cost O(B); the existing 2,097,152-character HTTP cap is not a total heap-byte cap.

Collapsed detail now has no raw/field traversal or serialization. Scalar display
reads fixed keys, copying at most 512 characters per title/provenance value,
2,048 per summary and 80 per type. Containers are never coerced to strings.
On expansion, output is at most 16,384 UTF-16 code units, traversal at most
1,024 values, nesting at most 32 edges and structured fields at most 64. Fields
share the output budget rather than each retaining 4 KiB. Work is O(C + N),
auxiliary memory O(C + D), with fixed limits C/N/D; no complete intermediate
container string or list of all keys is created. A bounded builder and final
string coexist briefly; TextView also has its own bounded representation.
Unsupported scalar types are labeled and mark the result partial rather than
calling potentially unbounded toString. Partial text is not necessarily JSON.

One worker and at most one pending task per expanded view; cancellation is
checked during string scanning/traversal and again on UI delivery. Replacing
or detaching the view closes its worker. These are static complexity bounds
with unit-test counters, not measured device latency/heap improvements.

## Validation actually run

- 14 focused JVM tests passed using Java 21, Eclipse ECJ 3.39.0 targeting Java 17,
  org.json 20240303, JUnit 4.13.2 and Hamcrest 1.3, downloaded from Maven Central.
  Coverage: 2 MiB scalar, 10,000 levels, virtual million-row array, shared field
  budget/count, JSON escaping, absent/container summaries, cancellation,
  arbitrary scalar safety, zero collapsed traversal, delayed stale callbacks,
  close, 1,000 rapid requests and worker-thread serialization.
- `python3 scripts/test-update-manifest.py`: passed.
- `python3 scripts/test-release-contract.py`: passed (9 APKs).
- `git apply --check docs/integration/issue-125-main-activity.patch`: passed.
- `git diff --check`: passed.

Focused test reproduction (temporary dependency paths for this environment):

```sh
java -jar /tmp/issue125-deps/ecj.jar -17 \
  -cp /tmp/issue125-deps/json.jar:/tmp/issue125-deps/junit.jar:/tmp/issue125-deps/hamcrest.jar \
  -d /tmp/issue125-classes \
  quasar-app/src/main/java/actor/starintel/quasar/detail/*.java \
  quasar-app/src/test/java/actor/starintel/quasar/detail/*.java
java -cp '/tmp/issue125-classes:/tmp/issue125-deps/*' org.junit.runner.JUnitCore \
  actor.starintel.quasar.detail.RecordDetailPreviewTest \
  actor.starintel.quasar.detail.RecordDetailSessionTest
```

## Unrun stages / blockers

`nix flake check --no-update-lock-file --show-trace` and
`nix run --no-update-lock-file .#build-all` were attempted; both failed because
`nix` is absent. Gradle, Android SDK/emulator and javac are also absent.
The Kotlin Android view and the proposed integration patch have not been compiled
or device-tested. JVM tests compile production Java helpers, not an Android APK.
Malformed/oversized HTTP response behavior remains the existing client contract,
not newly verified by these focused preview tests.

Before merging: serialize patch integration through #122/parent; run canonical
gates and `:quasar-app:testDebugUnitTest :quasar-app:assembleDebug`; exercise
expansion/collapse, navigation while loading, same-ID retries, missing fields,
malformed/oversized responses, recreation and detach on an emulator. Recreated
detail should restore the selected route through WorkspaceState and remain
collapsed until explicitly expanded. No physical-device claims or deployment.
