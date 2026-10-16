# Issue 124: bounded map requests and viewport cancellation

Baseline: main e80ab4558cc580eff26b13f8f86edf3578143730.
Rechecked native redesign PR120 at 82c2a3ae332fdc42c61e487b2607d81210ea9278.
Quasar PR110 remains untouched at 852bb4be300096f1ea006e9145aa992db1aead56.
No `.agents/skills` files exist in this checkout or the workspace `.agents` directory.
Read and followed repository AGENTS.md.

## Integrated viewport callback

Parent authorized FieldMapView integration on follow-up. drawTiles now calls
`tiles.setViewport(zoom, firstX..lastX, firstY..lastY)` before its tile loop.
Obsolete viewport cancellation is wired to the actual UI. The standalone patch
was removed. This one-line hunk applies cleanly to PR120's FieldMapView without
altering its native-design changes. No MainActivity changes.

The repository retains `tile()` and the trailing onChanged constructor lambda.
This draft preserves PR120's MAP AVAILABLE wording, decode-null failure behavior
and cached-only error state. PR120 also edits OpenMapTileRepository: its merge
must keep these fixes and the new scheduler. Its branch was not modified; its head was merged into this tile branch on parent instruction.
No Quasar Lisp, transaction overlay, PR110, PR115 or native redesign branch edits.
The tile branch now contains PR120 through a merge commit; its MainActivity and
other redesign changes are imported unchanged, with no tile-specific edits there.

## Resource bounds and complexity

- Two physical loading workers, including obsolete requests until they return.
  Cancellation disconnects attached HTTP connections; it does not free a worker
  slot early. Timeouts remain 7 seconds connect / 8 seconds read.
- At most 32 queued URLs; duplicate requests coalesce. Viewport updates drop old
  queued work and cancel obsolete running work. Requests beyond capacity wait
  for a later redraw; completion (including cancellation) wakes the viewport.
- At most 256 failure records, 30 second monotonic TTL; every lookup/write prunes
  expired records across all URLs. HTTP error and decode failure are throttled;
  cancellation does not poison the failure cache.
- Compressed body limit remains 1 MiB per response, checked both against declared
  length and streamed bytes. Pixel decoding requires positive dimensions no
  larger than 1024x1024, giving at most 4 MiB ARGB8888 per decode, two concurrent
  decodes, separate from the 12 MiB bitmap LRU and 48 MiB disk HTTP cache.
  Byte arrays/stream buffers add bounded temporary memory. These are static
  bounds, not measured heap or network performance results.
- Former queue/history memory O(Q + U) becomes O(32 + 2 + 256). A viewport update
  constructs a transient O(V) URL set and scans bounded pending/running work.
  Failure operations scan at most 256 entries, independent of failure history.
- HTTP cache headers, offline only-if-cached and no-prefetch policy are retained.

## Actual validation (2026-10-16)

Passed:
- `python3 scripts/test-update-manifest.py`: update-manifest: ok.
- `python3 scripts/test-release-contract.py`: 9 APK contract, version 7 / 0.5.0-alpha.
- Standalone Kotlin 2.2.0/JUnit 4.13.2: initially 7 TileWorkQueueTest tests; now 8, including completion-wakeup ordering.
  Includes 1,000 viewport changes (100 URLs each) with two blocked fake transports,
  duplicate coalescing, close/obsolete cancellation publication, attach race,
  10,000 unique failures, clock advance, and dimension limits.
- Kotlin compile of actual repository, scheduler, WebMercator and GeoModels
  against Robolectric android-all 15-robolectric-12650502 Android API classes,
  actual StarIntelSharedConfig.java source and a temporary generated-style
  BuildConfig.DEBUG=true fixture. One Java URL deprecation warning. This is a
  supplemental compile, not an AGP APK build or Android runtime test.

JVM test reproduction (Kotlin compiler/dependencies on compiler classpath):

```sh
java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB:$JUNIT_JAR" -d /tmp/tile-tests.jar \
  quasar-app/src/main/java/actor/starintel/quasar/field/TileWorkQueue.kt \
  quasar-app/src/test/java/actor/starintel/quasar/field/TileWorkQueueTest.kt
java -cp "/tmp/tile-tests.jar:$KOTLIN_STDLIB:$JUNIT_JAR:$HAMCREST_JAR" \
  org.junit.runner.JUnitCore actor.starintel.quasar.field.TileWorkQueueTest
```

Blocked/unrun:
- Both `nix flake check --no-update-lock-file --show-trace` and
  `nix run --no-update-lock-file .#build-all`: nix: command not found.
- Gradle/AGP builds, lint, full unit suite: Gradle and Android SDK absent.
- Seven added Android instrumentation tests (offline fake cached HTTP response
  and memory hit, valid decode, malformed/truncated images, oversized dimensions,
  streamed compressed-byte ceiling, HTTP failure cooldown) are unrun. No emulator
  or physical-device smoke, real platform HTTP disk-cache/offline integration,
  heap profiling, or rapid-pan UI test was run.
- The initial supplemental compile failed because javac was unavailable; supplying
  actual Java sources directly to Kotlin resolved symbol compilation. The later follow-up found the JDK compiler module and successfully compiled the
  actual shared-config/design Java sources using `java com.sun.tools.javac.Main`.

No deploy, main-branch merge, installation or production service changes.

## Follow-up validation

The viewport integration is now in the source, not a proposed patch. A dedicated
Quasar tile bounds workflow builds the app and test APK and runs the seven tile
instrumentation tests on API 35; existing native/widget journey scripts select
only their own classes and would not run these tests automatically.

Hosted checks for the initial commit were inspected: Android build was queued,
Nix evaluation and widget build were running, with no completed failure yet.
The environment still has no Nix, Gradle, Android SDK, ADB or /dev/kvm.

### Integrated runtime evidence

After wiring FieldMapView, supplemental compilation passed for the actual view,
QuasarDesign, repository, scheduler and geo sources, plus all seven instrumentation
test source methods. An additional ordering race was repaired: completion redraw
now happens only after the scheduler releases the URL's slot, so a previously
cancelled URL that is visible again can immediately be requested. A regression
unit test exercises that immediate same-URL request from the completion wakeup.

Eight pure JVM tests pass. Seven Android tile cases also pass (3.569 seconds) in
Robolectric 4.14.1 on API35 with NATIVE graphics, real Android BitmapFactory and
actual repository source. The temporary harness adds Robolectric runner/config
annotations to the instrumentation test source and pumps ShadowLooper while
waiting for callbacks; it changes no production code or test assertions. It
uses android-all-instrumented 15-robolectric-12650502-i7 and generated-style
BuildConfig.DEBUG=true. This is supplemental Android framework/native-decoder
coverage, not an emulator, AGP build, actual HTTP disk-cache test or device pass.
The unmodified instrumentation class is still queued for the dedicated hosted
emulator workflow. A local harness initially loaded an older temporary scheduler
JAR and reported NoSuchMethodError; an explicit current-test JAR classpath fixed
that harness issue before the passing run.

PR120 head 82c2a3ae332fdc42c61e487b2607d81210ea9278 is now merged into
this branch, as directed by the parent. FieldMapView merged automatically; its
only difference from PR120 is the viewport call. The OpenMapTileRepository decode
conflict was resolved in source by retaining bounds validation, requireNotNull
decode and cancellation-safe publication. MAP AVAILABLE wording and cached-only
failure state are preserved. No unresolved index entries remain. Compared with
PR120, the merged working tree contains only the seven tile-workstream files.

After integration: compilation passed for the actual merged FieldMapView,
QuasarDesign, QuasarAppearance, QuasarIcon and tile/geo sources against Android API
classes, with generated-style BuildConfig/R fixtures and actual Java shared
config/design sources. Eight pure JVM tests and seven supplemental Robolectric
native-decoder cases passed again; both Python repository gates passed. The tile
repository and scheduler source match the previously compiled runtime-test source
exactly after conflict resolution. Hosted checks will run against the merge head.

Hosted initial-head Nix evaluation and helper smoke steps passed; watchface
validation passed; APK compilation remained running. Integrated-head Android,
widget and tile jobs were queued when inspected. No hosted compilation/test
failure was reported at that observation; no green hosted result is claimed.
