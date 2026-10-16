# Issue 123: serialized Activity integration contract

Base: PR120 `82c2a3ae332fdc42c61e487b2607d81210ea9278`. MainActivity integration
must follow issue122's paging and issue125's detail integration, through the parent.
LogicStudioActivity now integrates the coordinator on top of PR131 `92e6ef5`.
MainActivity remains untouched pending the parent-approved detail head. Do not
merge yet: MainActivity's legacy synchronous call sites still exist and do not
catch size/quota rejections. Issue123 is not closed by this PR.

## Shared interface for 122/124/125/126

Use `WorkspaceDrafts.coordinator(applicationContext)` in both Activities. It owns
one process-lifetime worker and no Activity references. Do not construct a worker
per screen or shut it down on rotation. `WorkspaceDrafts.save/read` are retained
only for source compatibility; integration must eliminate their UI-thread callers.

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
- `release(name)` refuses dirty drafts; `discard(name)` is an explicit discard.
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
