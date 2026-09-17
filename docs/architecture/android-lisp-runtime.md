# Android Common Lisp, Tek9, and local actors

## Decision

Run the canonical portable Common Lisp substrate through ECL on Android. Build
ECL with the Android NDK, compile the Tek9 dependency closure into the mobile
image, link LMDB for each supported ABI, and expose one narrow JNI request
function to Kotlin.

ECL documents Android cross-compilation with NDK 22 or newer and is designed to
embed behind a C API. That is a materially better fit for Tek9 than putting ABCL
on ART and then replacing Tek9's LMDB/CFFI layer with a second storage engine.

## Runtime boundary

```text
Quasar UI
  -> starintel-android typed Kotlin API
  -> ECL JNI request bridge
  -> closed STARINTEL.MOBILE.RUNTIME operations
  -> Tek9 + LMDB transaction
```

The request bridge accepts bounded JSON with one of these operation names:

- `runtime.ping`
- `runtime.reload-init`
- `tek9.open`
- `tek9.close`
- `tek9.document`
- `tek9.search`
- `tek9.transaction`
- `actor.dispatch`

There is no public `eval`, `load`, arbitrary symbol call, filesystem primitive,
or shell primitive. Lisp startup loads trusted implementation files installed
from signed APK assets. A first-run `init.lisp` is separately seeded under the
app-private runtime directory and is never overwritten on upgrade. User saves
are bounded to 256 KiB, fsynced, atomically replaced, and retain one backup.

`init.lisp` is intentionally Lisp-shaped configuration, not executable code.
The runtime binds `*READ-EVAL*` to false, admits at most 128 bounded forms, and
only accepts `quasar-config`, `define-fbp-node`, and `define-expert`. It stores
the validated forms as data and never passes them to `eval` or `load`. This is
the safe control-plane seam for the Lisp, FBP, and expert editors; expanding its
DSL requires an explicit runtime operation and validator rather than exposing
the host process, environment, API keys, or filesystem.

## Local actor lifecycle

1. Quasar loads built-in and user-authored manifests.
2. Schema parsing rejects unknown capabilities and unqualified entrypoints.
3. The trusted Lisp image separately registers compiled handlers.
4. Kotlin registers enabled actor instances with bounded mailboxes.
5. A document envelope is admitted only when its dtype is accepted.
6. The actor returns typed effects.
7. Kotlin checks every effect against the manifest.
8. Tek9 commits all local effects plus the audit event in one transaction.
9. External work remains a durable outbox intent until a separately authorized
   dispatcher sends it.

## Prolog-RLM

Prolog-RLM is a SWI-Prolog runtime and is not silently rewritten in Common Lisp.
The first Android integration is the hosted `/api/v1/agents/prolog-rlm/turn`
contract. Requests carry explicit budgets and a narrowed capability set.
Returned operations remain reviewable and do not execute just because a model
or planner proposed them.

An offline SWI-Prolog Android build is a separate ABI packaging lane. It must
preserve the same capability, budget, durable-effect, trace, and depth-gate
semantics before it can replace the hosted boundary.

## Packaging gate still required

The Kotlin runtime, actor system, Tek9 transaction protocol, Lisp bootstrap, JNI
serialization shim, and UI status handling are implemented. The Nix Android SDK
now includes CMake and the NDK and builds `libstarintel_lisp.so`. Release
packaging must still produce and smoke-test the ABI-specific ECL/Tek9 adapter
(`libstarintel_ecl_adapter.so`, including ECL, LMDB, and the compiled Lisp
image) for `arm64-v8a`, then any additional supported ABI. Supply its per-ABI
root with the `starintel.ecl.adapterRoot` Gradle property. Until that artifact is
present, the app reports the adapter as unavailable and disables local execution.
