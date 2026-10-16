# Issue 124: bounded map requests (integration pending)

Baseline: main e80ab4558cc580eff26b13f8f86edf3578143730.
Rechecked native redesign PR120 at 82c2a3ae332fdc42c61e487b2607d81210ea9278.
Quasar PR110 remains untouched at 852bb4be300096f1ea006e9145aa992db1aead56.
No `.agents/skills` files exist in this checkout or the workspace `.agents` directory.
Read and followed repository AGENTS.md.

## Parent integration handoff

This work owns OpenMapTileRepository.kt, TileWorkQueue.kt and their tests.
The parent must coordinate the one-line FieldMapView.drawTiles change in
`issue-124-viewport.patch` with its shared-file owner, then apply it before merge.
It calls `tiles.setViewport(zoom, firstX..lastX, firstY..lastY)` before the tile
loop. No MainActivity or FieldMapView source was edited here. The callback is
required for obsolete viewport cancellation; without it only backpressure,
close cancellation, failure expiry and decoding limits are active. Do not close
issue 124 until this integration and Android validation are complete.

The repository retains `tile()` and the trailing onChanged constructor lambda.
This draft preserves PR120's MAP AVAILABLE wording, decode-null failure behavior
and cached-only error state. PR120 also edits OpenMapTileRepository: its merge
must keep these fixes and the new scheduler. Its branch was not modified.
No Quasar Lisp, transaction overlay, PR110, PR115 or native redesign branch edits.

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
- Standalone Kotlin 2.2.0/JUnit 4.13.2: 7 TileWorkQueueTest tests, 0.122 seconds.
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
  actual Java sources directly to Kotlin resolved symbol compilation. No javac
  or Java-bytecode build pass is claimed.

No deploy, merge, installation or production service changes.
