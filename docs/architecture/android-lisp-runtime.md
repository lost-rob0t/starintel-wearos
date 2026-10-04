# Android Common Lisp, Tek9, and local actors

## Decision and ownership

Run the canonical portable Common Lisp substrate through ECL on Android. Build
ECL with the Android NDK, compile the Tek9 dependency closure into the mobile
image, link LMDB for each supported ABI, and expose one narrow JNI request
function to Kotlin.

The reusable implementation is owned by `starintel-edge`, not this product
repository. `starintel-wearos` consumes the exact Edge revision pinned in the
Nix flake and contains only Android product bindings, adapters, and acceptance
tests. It must not retain copied ECL bootstrap code, Sento sources, a Common
Lisp actor registry, or a second runtime protocol.

ECL documents Android cross-compilation with NDK 22 or newer and is designed to
embed behind a C API. That is a materially better fit for Tek9 than putting ABCL
on ART and then replacing Tek9's LMDB/CFFI layer with a second storage engine.

## Runtime boundary

```text
Android product adapter
  -> starintel-android typed Kotlin API
  -> starintel-edge Kotlin/JNI ABI
  -> closed STARINTEL.EDGE.ANDROID operations
  -> ECL + local Sento actors
```

The shared Edge request bridge accepts bounded JSON and currently exposes the
runtime/actor operations proven by its conformance suite:

- `runtime.ping`
- `actor.roundtrip`
- bounded runtime lifecycle/status operations

There is no public `eval`, `load`, arbitrary symbol call, filesystem primitive,
or shell primitive. Lisp startup loads trusted implementation files from the
immutable Edge bundle copied into the app-private runtime directory. Product
payloads are data and are never passed to the Lisp reader or evaluator.

## Local actor lifecycle

1. The Android product loads lock-verified built-in manifests.
2. Schema parsing rejects unknown capabilities and unqualified entrypoints.
3. The trusted Lisp image separately registers compiled handlers.
4. Kotlin registers enabled actor instances with bounded mailboxes.
5. A document envelope is admitted only when its dtype is accepted.
6. The actor returns typed effects.
7. Kotlin checks every effect against the manifest.
8. The product-owned durable store commits admitted effects plus the audit
   event in one transaction.
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

## Packaging and acceptance

`starintel-edge` produces immutable arm64-v8a and x86_64 runtime bundles. The
WearOS Nix flake passes the selected bundle through
`starintel.edge.runtimeRoot`; Android Gradle packaging copies its native
libraries, Kotlin binding, and trusted assets without modifying them.

Host builds are not ART evidence. Acceptance requires an API 36 x86_64
emulator to load the exact packaged libraries, boot ECL, check the native ABI,
and complete a real local Sento actor round-trip while Wi-Fi and mobile data are
disabled. ARM64 remains a build/package gate until a compatible physical target
is explicitly selected for installation.
