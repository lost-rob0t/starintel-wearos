# Issue 123: serialized Activity integration contract

Base: PR120 `82c2a3ae332fdc42c61e487b2607d81210ea9278`.
LogicStudioActivity integrates the coordinator on PR131 `92e6ef5`.
MainActivity now integrates on PR127 `59c0c51228064b0b82ef3845e3b06d775b6b236e`,
which already includes PR128 paging and PR125 lazy detail. Integration was serialized
through the parent. No historical Activity patch was applied. This combined head is
ready for the parent to hand to #126, but remains draft pending Android runtime and
canonical CI results. No deployment or merge has occurred.

## Shared interface for 122/124/125/126

Use `WorkspaceDrafts.coordinator(applicationContext)` in both Activities. It owns
one process-lifetime worker and no Activity references. Do not construct a worker
per screen or shut it down on rotation. `WorkspaceDrafts.save/read` remain compatibility entry points used by the worker
and storage tests. Both Activities now use the coordinator rather than invoking
these disk/encryption operations on main.

- Install `DraftInputFilter { input.error = "Draft exceeds 256 KiB" }` before
  any restored or user text reaches each non-credential Editable. Preserve existing
  filters. Never attach credential inputs (`isSaveEnabled == false`). Rejection
  preserves the complete original selection, rather than silently truncating JSON.
- After an accepted edit, call `update("${route}:$index", input.text)` from the
  editor thread. Stable field IDs are preferable when an owner's layout changes.
  Logic Studio should use `logic:${language.name}:${buffer.name}`. Admission scans
  at most 256 KiB, then takes one immutable bounded copy. Catch aggregate/name/slot
  rejections visibly and preserve the previous accepted editor content; don't silently
  lose the newly entered text. Do not call update from rememberView on every navigation.
- `current(name)` supplies process-local content across navigation/rotation, including
  unacknowledged edits. `acknowledged(name)` returns a key and generation ONLY after
  AtomicFile.finishWrite. `isDirty`/`failure` distinguish pending/failed persistence.
  A prior receipt must never be labelled as acknowledgement of newer edited text.
- In rememberView/onSaveInstanceState save `DraftReference.key(name)` even before
  a first write acknowledges, alongside cursor, route, and bounded small inline
  fields. Persist a pending marker separately; a reference is not an acknowledgement.
  Otherwise a first write completed after state capture would have no restore key.
  On process recreation a pending marker requires a visible "restored last saved
  version; review before submitting" gate, because the v1 envelope does not persist
  generations. If no envelope exists, fail visibly. Never substitute empty content.
  Then call `flush()` without waiting. Lifecycle requests coalesce.
  Use a weak Activity reference or route/generation check when posting completion
  to main; never retain an Activity in a process coordinator callback.
- `flush()` returns a future for the snapshot at execution; later edits may still
  be dirty. Success does not authorize submission. Failure retains dirty content;
  show a save failure, don't suppress it. No automatic retry of failed mutations.
- Restore with `restore(key)` on the worker; on main apply only if route, field
  identity and edit generation still match. Disable affected submission while
  restoration is pending or failed. Missing/corrupt envelopes fail exceptionally,
  never as an empty or default source. Require explicit user discard/reopen before
  re-enabling submission following failure.
- `release(name)` refuses dirty drafts; `discard(name)` removes memory only.
  `discardSaved(name)` serializes explicit named-envelope deletion after in-flight
  writes and skips deletion if a newer edit has reclaimed the name.
  No automatic LRU eviction of unsaved content. A process has at most 32 entries
  and 2 MiB UTF-16 snapshots, plus one bounded worker snapshot and bounded read tasks.
- `pruneUnreferenced(allLiveKeys)` is a worker-only operation on WorkspaceDrafts.
  Call only after collecting ALL live/saved keys across MainActivity, Logic Studio
  and retained task state. It also removes orphan `.new`/`.bak` siblings. Never
  prune from the current route's keys alone. Quota rejection is preferable to
  deleting an unenumerated saved task's unsaved work.

## Durability and acceptance still required

Pending asynchronous edits cannot survive an immediate process kill before the
worker acknowledgement. UI must distinguish pending from saved and never claim
otherwise. The last acknowledged encrypted AtomicFile survives an interrupted
replacement. Never promise onStop/onSaveInstanceState grants background execution.

The integration owner must add/run oversized paste, repeated navigation, rotation,
external process-death after acknowledgement, corrupt restore/no-submit, and
credential-exclusion Android tests. Run the added storage instrumentation tests,
unit tests, Android build and canonical Nix gates. Until then, no main-thread
latency, end-to-end durability or measured performance improvement is claimed.

## Complexity (static bounds, not timings)

Previously each navigation did O(S) text copies/encryption/disk work across all
fields, and restore allocated unbounded complete files. Once integrated, each
accepted edit costs O(min(input, 256 KiB)) admission/copy, not an unbounded pasted
buffer. Unchanged navigation reads fixed-sized receipts; dirty encryption remains
O(D) on one worker, coalesced across debounce windows. One draft is at most 256 KiB
UTF-8; retained memory is at most 2 MiB UTF-16 plus up to one 2 MiB in-flight
snapshot. At most 32 restore tasks may be queued; callers must consume/release
completed futures rather than retaining arbitrary results. Disk quota is 8 MiB
including interrupted envelope siblings; count quota is 64 directory entries
(with at most one temporary replacement). There is no full-corpus or time-based
retention sweep. No plaintext spillover is written.

## Logic Studio integration validation

Integrated after parent release of PR131 `92e6ef5`, preserving one
SourceEditorAnalysis per editor and captureDraft before navigation/save. Edits
mark dirty without copying source; a 300 ms idle capture or navigation/lifecycle
capture admits one bounded snapshot. Repeated unchanged navigation does not copy
or encrypt. Save/restore of encrypted drafts uses the process coordinator worker.
Normal explicit PrivateWorkspaceStore save/list operations are unchanged.

Language switching retains unsaved buffers using qualified language/name refs.
Rotation consumes process-local snapshots immediately. A process restore reads on
the worker and adopts clean versions without redundant writes. Missing/corrupt
references block all editor/mode actions until explicit retry/discard. Pending
process-restored content requires a review gate. Aggregate admission rejects a
whole edit before insertion; size handling remains the existing editor filter.
No shared MainActivity edits were included.

Added three Android integration tests: language-switch/rotation preservation, a
missing-envelope gate that cannot be bypassed by changing mode, and review-gate
persistence across rotation. The dedicated emulator workflow now selects eight tests including PR131's highlighting/rotation
test. These are API/type compiled locally but have NOT run on Android yet. The
standalone compiler uses real Android14/AndroidX classes and actual production
sources, with only generated R.style IDs supplied as a temporary compile stub;
this is not an APK build or resource-link check. Twelve coordinator JVM tests pass,
including restored-clean no-write and stale-restore/new-edit ordering. Thirteen
IDE language/analysis regression JVM tests also pass. Both Python
repository gates, shell syntax and diff checks pass. Hosted runs remained queued.

## MainActivity combined handoff

WorkspaceDraftFields owns the isolated binding code; MainActivity changes are the
remember/restore lifecycle hooks and submission readiness checks. New route methods
stop if an admission failure would otherwise discard edited text. A route retains
at most 4096 inline characters; large sources use stable keys. Dirty text is captured
after 300 ms idle or lifecycle/navigation, unchanged text reuses snapshots. Bound
filters exclude credential fields. Failures/pending restore disable source actions
and require explicit discard/review; submitOnce's button guard remains in place.
There are no automatic network/mutation retries. Explicit screen discard removes
only the named encrypted envelopes on the worker. Logic Studio does the same on
explicit discard or after saving its source file. Arbitrary age/LRU cleanup is not
used because saved tasks may still reference those files.

One inherited compile error in PR127's restored-search Elvis expression was fixed
with an explicit Unit result; search behavior is unchanged. The parent-requested
PR128 corrective commit `329a2b02f0b837ea81778ba987c1b8fb40a76e23` is now
incorporated, using its trailing `Unit` form instead of the equivalent local fix.

Validation after MainActivity integration: 13 coordinator JVM tests pass, including
named-envelope cleanup; 13 editor regression tests passed after the editor merge.
The entire Quasar + shared Android + shared design source tree and selected native
tests API/type compile with the real pinned Edge binding at f801b024, Android14 and
AndroidX libraries. Generated R/BuildConfig constants are temporary compiler stubs;
this remains distinct from an APK build/resource link and Java code generation.
The dedicated emulator workflow selects 11 tests, then runs
scripts/test-quasar-draft-process-death.py for an acknowledged >4096-character
source. The script refuses physical devices, clears disposable fixture app data,
waits for encrypted AtomicFile replacement, backgrounds/kills the app process and
checks exact source restoration under a different PID without submitting anything.
These Android runtime checks are added but unrun locally. Hosted results remain
pending. Both Python gates, script syntax and diff checks pass. Nix/Gradle/emulator
limitations from the earlier validation still apply.

## Observed Android runtime follow-up (2026-10-17)

PR132 visual run 38983398286 jobs 117249207575/117249207754 reproduced
ArrayStoreException in WorkspaceDraftFields.bind: Kotlin Arrays.plus copied the
runtime component type InputFilter.LengthFilter[], then attempted to store a
DraftInputFilter. All three editor filter append sites now build a List and use
explicit toTypedArray<InputFilter>(), preserving existing filters/order while
allocating a genuine InputFilter[]. Two new Android regressions start from the
concrete LengthFilter[] case and verify binding/analyzer setup and existing limits.
MainActivity and the #126 import changes are untouched by this correction.

Separately, draft-storage run 38983398295 job 117249207690 passed all 11 Android
instrumentation tests, then failed before editing: Add record was absent from the
unscoped drawer lookup. The fixture set 360x800 physical pixels but left the
emulator's 420 dpi density in place. This yields a much smaller dp viewport;
off-screen navigation is the likely cause, distinct from the array crash. That
failed run did not archive hierarchy evidence, so the precise viewport state
cannot be retrospectively proven. The fixture now sets 160 dpi, waits for the open
drawer, searches only Workspace destinations, and scrolls that pane at most eight
times. It records drawer XML plus crash/window/display diagnostics on failure.
Three host navigation tests verify covered-card exclusion, off-screen scrolling
and bounded missing-control failure. Both runtime gates remain mandatory; the
new dedicated test selection expects 13 Android tests before external process death.
