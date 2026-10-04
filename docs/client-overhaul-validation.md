# Client redesign and radar acceptance

The native Wear and phone clients use black surfaces, cyan outlined controls, and terminal typography. Neon HUD adds the reference magenta/cyan palette alongside the existing Qtile Electric theme. The three dedicated faces have minimal Ultra Black modes, provider labels, numeric ranged-value fallback, and clamped gauges. Neon edge values and labels rotate sideways. Ingest graph points use elapsed time; missing observations and counter resets remain disconnected. Range changes refresh the watch complication immediately.

The new document radar consumes the authenticated `feed=recent` endpoint, polls only while visible, retains and labels stale results, and opens documents by ID. The server also exposes a guest feed via `/api/v1/search?feed=recent`. This is a bounded snapshot, not a lossless event stream or a geographic display.

Validation includes unit tests, native Robolectric round-screen rendering and lifecycle checks, populated/stale/empty radar navigation, real graph bitmap rendering, 112 provider-shaped gauge evaluations, XML layout/slot/theme/rotated-bound checks, and APK builds for the companion, Wear app and all three faces. Generated screen renders are under `wear-app/build/reports/client-screens/`.

Physical deployment and acceptance remain separate: connect a watch with ADB, install the Wear app and each face with `adb install -r`, then verify package paths, normal/Ultra Black modes, actual health provider selection, range changes, and live radar arrivals. No physical watch was available during this run; native mock screenshots are not hardware/WFF screenshots.
