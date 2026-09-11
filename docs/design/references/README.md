# GPT-approved watch-face design references

These images are design references for the StarIntel Wear OS RAGE backlog. They are not runtime assets and should not be bundled into the APK unless a later implementation issue explicitly requires an asset derived from them.

Visual authority lives in GitHub issue #24. Coding agents must implement the referenced layout/behavior rather than inventing replacement styling when a face issue is marked `BLOCKED: DESIGN`.

Current references:

- `neon-geometric-hud.jpg` — Neon Geometric HUD direction. Geometric/glitch treatment, StarIntel four-point star, large digital time, two curved side complication zones, lower circular complication slots. No outrun sunset/landscape filler. HR and Samsung stress shown only as example provider assignments.
- `command-data-geo.jpg` — Command Data/Geo direction. Large time, one real trend graph, one real geo activity module, and a deliberately small complication set. Graph/map are data-backed, not decorative.
- `terminal-ops.jpg` — Terminal Ops direction. Text-forward operational face with system/target/corpus/queue information and minimal decoration.

Global rules:

- unset complication slots remain blank/quiet;
- final face-owned colors are themeable;
- standard Wear OS/Samsung complication providers remain user-selectable where platform contracts allow;
- StarIntel graphs use real time-series data and configurable time ranges;
- StarIntel map/globe visuals use real geocoded document/activity aggregates;
- no slogans or meaningless pseudo-intelligence filler text;
- final changes to the visual references require GPT/user approval through #24.
