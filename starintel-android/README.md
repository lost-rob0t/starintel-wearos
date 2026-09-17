# StarIntel Android library

`starintel-android` is the reusable native client/runtime layer shared by Quasar
and future Android surfaces. It deliberately does not own StarIntel's protocol
or duplicate the canonical Common Lisp Quasar runtime.

## Public layers

- `api.StarIntelClient` implements bounded authenticated `/api/v1` access,
  documented legacy read fallback, target dispatch, and the Prolog-RLM turn
  boundary.
- `model` owns typed sessions, endpoints, actor manifests/effects, and bounded
  Prolog-RLM request/result envelopes.
- `actors.LocalActorSystem` provides bounded per-actor mailboxes on a bounded
  shared executor. One actor instance processes one message at a time.
- `fbp.FlowRuntime` implements a classic Flow-Based Programming network:
  independently scheduled processes, named ports, owned information packets,
  bounded point-to-point connections, and initial information packets (IIPs).
  The closed `starintel.fbp.graph/v1` JSON form names registered component
  types; it never embeds executable code or creates another server protocol.
- `store.LispTek9Store` batches documents, graph relations, facts, audit events,
  and target outbox intents into one embedded Tek9 transaction.
- `lisp.EclLispRuntime` is the Android-to-ECL JSON command bridge. It exposes a
  closed operation vocabulary; it is not a general `eval` API.

## Trust boundary

Local actor manifests are configuration. A manifest may reference a
package-qualified Common Lisp entrypoint, but it cannot make that entrypoint
callable. The mobile Lisp image must register the actor ID and exact entrypoint
with `STARINTEL.MOBILE.RUNTIME:REGISTER-ACTOR`. Dispatch checks both values and
then invokes the already-registered function.

Actor effects are data. Android validates each effect against the manifest's
capabilities before passing one transaction batch to Tek9. Network targets are
written to an outbox; an actor never performs ambient network I/O by returning
an effect.

FBP graphs use the same boundary. `starintel.tek9-expert-query/v1` performs a
bounded Tek9 query and emits result/alert packets. The optional
`starintel.tek9-alert-outbox/v1` sink applies its own effect ceiling, then uses
the existing Tek9 `enqueue_target` outbox transaction and audit event. Graphs
cannot define a new operation vocabulary, open sockets, or evaluate Lisp.

Prolog-RLM remains SWI-Prolog. The Android client invokes the versioned hosted
agent boundary with explicit capabilities and budgets, and renders returned
operations for authority review. It does not reinterpret arbitrary Prolog or
silently execute model output.

## Common Lisp packaging

ECL is the selected Android implementation because its supported Android NDK
cross-build and embeddable C API fit Tek9's native LMDB dependency. ABCL remains
useful for Java interop, but it does not make the existing CFFI/LMDB Tek9 system
portable to Android ART.

The runtime image must contain:

1. ECL and its native runtime dependencies for each shipped ABI;
2. LMDB;
3. Alexandria, Bordeaux Threads, Serapeum, JSOWN, cl-conspack, and cl-lmdb;
4. Tek9;
5. `src/main/assets/lisp/starintel-mobile-runtime.lisp`;
6. `src/main/assets/lisp/local-actors.lisp` plus product actor packages.

The APK fails closed when the ABI-specific bridge/image is absent. Quasar shows
that state directly instead of substituting SQLite or claiming a local actor
ran remotely.
