# Native Quasar Flow Studio

The Android app consumes the same closed-data FBP model as the Common Lisp Quasar
control plane: independently scheduled processes, named ports, bounded connections,
and information packets. Android does not introduce another StarIntel executor.

`FlowStudioActivity` stores canonical `starintel.fbp.graph/v1` JSON in app-private
storage. Graphs cannot contain code or grant capabilities. Component types are
resolved through the host-owned registry. The first executable nodes query the
existing Tek9 store and emit each result through `starintel.websocket-document/v1`.
That sink uses one authenticated, bounded WebSocket and waits for a correlated
acknowledgement before accepting the next information packet. Star ingest remains
the sole authority for schema validation, tenant enforcement, persistence, routing,
and alert-side effects.

The home-screen widget is a typed control projection. It may enqueue only
run/pause/resume/stop requests. Flow Studio consumes and acknowledges those requests;
widgets never evaluate Lisp or directly execute a graph.

Custom nodes and experts are declared in writable `init.lisp` through the bounded
configuration DSL. The JNI boundary never exposes general `eval` or `load`.
