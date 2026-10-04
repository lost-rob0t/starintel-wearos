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
- Edge's Common Lisp/Sento runtime owns actor registration, mailboxes,
  supervision, and dispatch. Android projects the trusted `actor.list` catalog
  and submits only closed `actor.dispatch` requests.
- `fbp.FlowRuntime` implements a classic Flow-Based Programming network:
  independently scheduled processes, named ports, owned information packets,
  bounded point-to-point connections, and initial information packets (IIPs).
  The closed `starintel.fbp.graph/v1` JSON form names registered component
  types; it never embeds executable code or creates another server protocol.
- `store.LispTek9Store` is retained as a compatibility-facing store port, but
  reports unavailable until Tek9 operations are added to the canonical Edge ABI.
- `lisp.EclLispRuntime` is the thin Android wrapper around the exact
  `starintel-edge` runtime pinned in `flake.lock`. It exposes only Edge's closed
  operation vocabulary; it is not a general `eval` API.

## Trust boundary

Actor definitions are not writable Android configuration. Only IDs compiled
into the pinned Edge image appear in the catalog or dispatch successfully;
client-provided package/symbol text remains inert data. Domain expert packages
stay with their canonical owner and are unavailable when absent from the image.

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

`starintel-edge` owns ECL, LMDB, Sento, the JNI/C ABI, trusted Lisp assets, and
runtime conformance. The Nix flake pins commit
the exact revision declared in `flake.nix`, merges its ARM64 and x86_64 bundles,
and gives Gradle that immutable bundle as a source, JNI, and asset root. This
repository no longer carries a second native bridge or product-owned runtime
dispatcher.

The APK fails closed when the pinned ABI bundle is absent. Quasar copies the
immutable `starintel-edge` asset tree into app-private storage and reports the
real runtime state; mutable workspace Lisp is never evaluated by the runtime.
