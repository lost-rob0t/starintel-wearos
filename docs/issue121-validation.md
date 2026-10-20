# Issue 121: bounded IDE analysis

Based on native redesign PR120 at `82c2a3ae332fdc42c61e487b2607d81210ea9278`;
main was `e80ab4558cc580eff26b13f8f86edf3578143730`. Neither PR120 nor backend
Quasar PR110 is modified. No deployment or mutation retries.

## Integration status / ownership

Parent explicitly assigned highlighting/editor wiring to #121 and directed issue
#123's draft-storage owner to integrate after these changes settle. The activity
wiring is now applied; the temporary integration patch is removed.

The watcher marks the active buffer dirty without copying its source on every
keystroke. Existing `captureDraft()` calls in save, validate, search, tab/language
navigation and instance-save still snapshot the current text. Opening New buffer
now captures the current draft before the dialog can switch editors. The issue
#123 owner must preserve this contract for any additional navigation/restoration
paths. The onCreate/onSaveInstanceState draft-storage implementation is unchanged.
No MainActivity.kt, workspace store or backend files are edited.

Integration interface: construct `SourceEditorAnalysis(field, language, tokenColor,
rejected, changed)` once per newly created field, including restored fields.
`changed` marks the buffer dirty; it must not copy the source. `rejected` reports a
rejected edit. The helper owns its watcher/filter, debounce, worker and view-detach
cleanup. Ordinary view detach closes the current analysis session; reattach creates
a fresh one. Worker completions from closed/superseded sessions cannot apply spans.

## Bounds and complexity

- Structural validation tracks one-based UTF-16 positions in one scan, with O(S)
  work and O(S) delimiter stack memory. At most 25 diagnostics are constructed,
  including Prolog missing-period diagnostics. Original diagnostic ordering is
  preserved for the retained prefix. No prefix copies per diagnostic remain.
- Search position accounting advances monotonically per buffer: O(S + M) position
  work, no prefix allocation per match. Query matching itself is unchanged.
- Coloring waits 150 ms after edits. One analysis runs at a time, with at most one
  latest edit awaiting analysis. Snapshots are taken after debounce on the UI
  dispatcher; tokenization runs on the worker. Applying spans remains on the UI
  dispatcher. This is bounded full-buffer analysis after a pause, not incremental
  parsing. Beyond 32,768 UTF-16 code units or 4,096 tokens, the result is plain text.
- Input filter rejects a whole edit that would exceed 262,144 UTF-8 bytes, without
  allocating an unbounded paste. Byte accounting scans changed text and surrogate
  neighbours; it does not rescan the entire document on ordinary edits. Oversized
  legacy restored text is retained so it is not silently destroyed; only edits
  reducing it are accepted until it is within the limit. The lifecycle worker
  owns restoration and aggregate unsaved workspace limits. Existing saved-source
  256 KiB / workspace 2 MiB / 64-file limits are unchanged.

Adversarial tests exercise both languages and all-open/all-close inputs:

| Source characters | Scanner character visits | Maximum diagnostics |
| ---: | ---: | ---: |
| 1,024 | 1,024 | 25 |
| 4,096 | 4,096 | 25 |
| 16,384 | 16,384 | 25 |
| 65,536 | 65,536 | 25 |
| 262,144 | 262,144 | 25 |

At most 25 remaining openers are visited for output. These are deterministic
operation-count assertions, **not timing or memory performance measurements**.

## Actual local validation

- 13 JUnit tests passed with standalone Kotlin 2.2.0 compiler and JUnit 4.13.2.
  Includes the existing language tests, diagnostic bounds/positions/order,
  search positions, 1,000 debounced edits, stale result suppression, single-worker
  coalescing, closed/restored analysis sessions, large-source/token fallback, and
  UTF-8 replacement accounting at every surrogate boundary of an adversarial value.
- `SourceEditorAnalysis.kt` compiled against Maven's Android API stub jar. This is
  only a compile check, not an Android runtime or SDK36/Gradle build.
- `python3 scripts/test-update-manifest.py`: passed.
- `python3 scripts/test-release-contract.py`: passed (9 APK contracts).
- Activity integration applied after explicit parent ownership assignment.
- `bash -n scripts/test-native-journeys.sh`: passed.
- `git diff --check`: passed.
- `nix flake check --no-update-lock-file --show-trace` and
  `nix run --no-update-lock-file .#build-all`: attempted, unavailable (`nix` absent).

No Gradle, Android SDK, emulator or physical-device execution was available.
A new `LogicEditorJourneyTest` is wired into the hosted native journey script. It
covers large paste rejection, exact 256 KiB input, multibyte replacement, setText
replacement accounting, latest-token spans, real activity recreation with draft
restoration, and plain-text fallback. It is not yet run locally. Android IME/span
behavior, real activity recreation, persisted draft restoration and APK assembly
remain unverified until hosted checks complete.
The pure session restoration test does not claim Android lifecycle coverage.

Standalone reproduction (jars outside repository in `/workspace/issue121-tools`):

```sh
java -cp '/workspace/issue121-tools/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath /workspace/issue121-tools/stdlib.jar:/workspace/issue121-tools/junit.jar:/workspace/issue121-tools/android.jar \
  -d /tmp/issue121-tests.jar \
  quasar-app/src/main/java/actor/starintel/quasar/ide/IdeLanguage.kt \
  quasar-app/src/main/java/actor/starintel/quasar/ide/EditorAnalysis.kt \
  quasar-app/src/main/java/actor/starintel/quasar/ide/SourceEditorAnalysis.kt \
  quasar-app/src/test/java/actor/starintel/quasar/ide/IdeLanguageTest.kt \
  quasar-app/src/test/java/actor/starintel/quasar/ide/EditorAnalysisTest.kt
java -cp '/tmp/issue121-tests.jar:/workspace/issue121-tools/*' org.junit.runner.JUnitCore \
  actor.starintel.quasar.ide.IdeLanguageTest actor.starintel.quasar.ide.EditorAnalysisTest
```

The compiler runtime uses Kotlin stdlib/script-runtime 2.2.0, reflect 1.6.10,
kotlinx-coroutines-core-jvm 1.8.0, JetBrains annotations 13.0; JUnit uses Hamcrest
1.3. Android compile-only stub is `com.google.android:android:4.1.1.4`.
