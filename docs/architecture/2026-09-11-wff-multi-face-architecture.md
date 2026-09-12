# Architecture: Multi-Face StarIntel Wear Experience

**Issue:** #25 — Clone with Nix, audit WFF capabilities, and lock multi-face architecture
**Parent epic:** #23
**Repo state audited:** `main` @ `469e7e0` (GPT-approved design references in `docs/design/references/`)

---

## 1. Toolchain & build baseline (verified)

| Item | Value | Evidence |
| --- | --- | --- |
| Nix | nixpkgs `nixos-unstable`; Android platform 36 / build-tools 36.0.0; jdk17; gradle_9 | `flake.nix` |
| Gradle (Nix local) | 9.7.1 (nixpkgs) | observed in local build |
| Gradle (CI) | pinned 9.6.0 via setup-gradle | `.github/workflows/android.yml` |
| AGP | 9.4.0 | root `build.gradle.kts` |
| Modules | `:phone-app`, `:wear-app`, `:watchface` | `settings.gradle.kts` |
| package IDs | phone & wear share `actor.starintel.wear` (companion devices differ); watchface `actor.starintel.watchface` | per-module `build.gradle.kts` |
| minSdk | phone 26, wear 30, watchface 33 | per-module `build.gradle.kts` |
| WFF version | v1 (`com.google.wear.watchface.format.version=1`) | `watchface/src/main/AndroidManifest.xml` |
| CI | PR-triggered gradle build+test; Nix flake check + build-all + lock-drift check; WFF validator on `watchface.xml` v1 | `.github/workflows/android.yml` |
| Signing | debug-only; no signingConfig anywhere | grep across build files |

**Baseline commands run locally on this head — all green:**

```text
nix flake check --no-update-lock-file --show-trace   # all checks passed
nix run .#check                                       # BUILD SUCCESSFUL in 1m 35s
nix run .#build-all                                   # BUILD SUCCESSFUL in 28s; 3 APKs in build/nix/
```

---

## 2. WFF v1 capability matrix (evidence-based)

Legend: **S** = supported, **SC** = supported with constraint, **NS** = not supported.

| # | Capability | Verdict | Evidence / constraint |
|---|-----------|---------|----------------------|
| 1 | UserConfigurations (Boolean/List/Color options) | **S** | `ListConfiguration`/`BooleanConfiguration`/`ColorConfiguration` in v1 XSD; surface in the system face editor; `Editable=true` already set in `watch_face_info.xml` |
| 2 | Multiple selectable faces in one APK | **NS** | One APK = one face entry in the picker; `watch_face_shapes.xml` maps layout variants per device shape only, not separate picker entries. WFF-blessed path for variation: UserConfigurations/Flavors within one face, or multiple APKs (developer.android.com WFF setup docs) |
| 3 | Complication types in v1 slots | **SC** | v1 slot set: SHORT_TEXT, LONG_TEXT, MONOCHROMATIC_IMAGE, SMALL_IMAGE, PHOTO_IMAGE, RANGED_VALUE, EMPTY. **No GOAL_PROGRESS / WEIGHTED_ELEMENTS / TIME_TEXT in v1 slots.** Providers can publish richer types; a v1 face cannot consume them (v1 Complication XML reference) |
| 4 | Curved side-bar complication slots | **S** | `BoundingArc` (centerX/centerY/width/height/thickness/startAngle/endAngle/direction/isRoundEdge/outlinePadding) in v1 `boundingElement.xsd`; content drawn via PartDraw `Arc` bound to RANGED_VALUE expressions |
| 5 | Ranged/progress rendering & data sources | **S** | `[COMPLICATION.RANGED_VALUE_VALUE/MIN/MAX]`, `..._COLORS`, `..._COLORS_INTERPOLATE`. No `[COMPLICATION.PROGRESS]` expression exists. System providers: STEP_COUNT, HEART_RATE, WATCH_BATTERY, DATE, DAY_AND_DATE, DAY_OF_WEEK, TIME_AND_DATE, NEXT_EVENT, UNREAD_NOTIFICATION_COUNT, SUNRISE_SUNSET, WORLD_CLOCK, FAVORITE_CONTACT, APP_SHORTCUT. Weather is an expression data source (`[WEATHER.*]`), not a complication provider |
| 6 | Tap actions (Launch) | **SC** | `<Launch target=...>` is a valid child of any Part or Group (not of ComplicationSlot — slot launch comes from provider data). Built-ins: ALARM, BATTERY_STATUS, CALENDAR, MESSAGE, MUSIC_PLAYER, PHONE, SETTINGS, HEALTH_HEART_RATE; arbitrary strings = deep-link/component URIs. One Launch per element; inert in ambient |
| 7 | Complication carousels / rotating-icon picker on face | **NS** | v1 ComplicationSlot XSD has no carousel/rotation mechanism (androidx renderer / WFS territory). Alternative: per-slot provider selection in the system picker + `DefaultProviderPolicy` defaults — not on-face interactive |
| 8 | On-face graph time-range switching by tap | **NS** | WFF v1 has no tap-driven config switching; `Variant`/`Condition` are render-mode driven, not tap driven. Alternative: `ListConfiguration`/`ListOption` (e.g. 24h/7d/30d) set in the face picker, consumed via `[CONFIGURATION.<id>]` expressions; companion override possible via existing DataMap protocol (VERSION bump) |
| 9 | Sparkline/graph drawing | **SC** | PartDraw shapes in v1: Ellipse, Line, Arc, Rectangle, RoundRectangle only — no Polyline/Path. Sparkline = one `Line` per segment, each with its own Transform; endpoints can be data-bound (arithmeticExpressionType: `[COMPLICATION.*]`, `[CONFIGURATION.<id>]`, named Expressions). No per-point array binding |
| 10 | Ambient/AOD | **S** | `Variant mode="AMBIENT"` only; ≤15% illuminated pixels guidance; avoid PHOTO_IMAGE in ambient; SMALL_IMAGE/SHORT_TEXT have *_AMBIENT alternates; low-luminance palettes |
| 11 | Flavors presets in v1 | **UNVERIFIED** | Must be confirmed against the v1 XSD/validator before use; fallback = user picks configuration options manually in the editor |

---

## 3. Current face audit

- Single 450×450 face, WFF v1; `MultipleInstancesAllowed=true`, `Editable=true` in `watch_face_info.xml`.
- 3 ComplicationSlots (slotIds 1–3), all `SHORT_TEXT EMPTY`, `BoundingOval`, `DefaultProviderPolicy` → `actor.starintel.wear/.complications.{Status,Targets,Documents}ComplicationService`.
- Ambient: `Variant mode="AMBIENT" target="alpha" value="0"` on accents + footer.
- Providers: abstract `StarIntelComplicationService : SuspendingComplicationDataSourceService`; SHORT_TEXT only; 7-char text truncation; UPDATE_PERIOD_SECONDS=300; repository caches `/api/v1/stats` for 60s, stale threshold 15 min.
- Truthful data surface today: `documentsTotal`, `targetsTotal`, `documentsByType` (per-dtype), reachability/staleness. No time series, no rates, no geo data.
- Tiles: OpsTileService, TargetsTileService, CorpusTileService (Material3 protolayout).
- Companion protocol v1: DataMap payload `server_url` + `api_key` (`star_sk_v1_` prefix enforced), 8 KiB cap, ACK path; does not request complication updates today.

---

## 4. Required architecture decisions

### 4.1 One WFF package with configurations vs multiple installable face packages

**Decision: one WFF APK using UserConfigurations (and Flavors if verified in v1) for face variation. Not multiple installable face packages.**

- One APK yields one picker entry; extra raw `watchface*.xml` files would be invalid unless mapped via `watch_face_shapes.xml` (layout variants only).
- UserConfigurations + option-scoped Scene groups express face personalities inside one resource; keeps Nix/CI/WFF-validator wiring to a single face XML, preserving exact-head CI.
- If a future face truly cannot be expressed as options, a deliberate multi-APK packaging change is required — out of scope unless forced.

### 4.2 How multiple selectable StarIntel faces are exposed to the system picker

- One picker entry (StarIntel). `watch_face_info.xml` keeps `MultipleInstancesAllowed=true`, `Editable=true`.
- Personality axes are `ListConfiguration` options (face style: neon-geometric-hud / command-data-geo / terminal-ops; theme; slot layout; graph range), consumed via `[CONFIGURATION.<id>]` and option-scoped Scene groups (alpha/visibility/geometry per option).
- If Flavors verify in v1 (matrix row 11), they map preset option combos to named selectable presets; otherwise users choose options in the editor.
- Final visual implementations remain gated on #24/#35–#37 per the epic design gate. Architecture and data work proceed independently.

### 4.3 Which desired slots can be true standard complication slots

- Existing 3 StarIntel SHORT_TEXT slots: contract unchanged.
- New StarIntel RANGED_VALUE slot(s): v1-consumable; providers publish `RangedValueComplicationData` derived from real snapshot data (by-dtype fractions, totals vs configured goals).
- Standard system slots via `defaultSystemProvider`: STEP_COUNT, HEART_RATE, WATCH_BATTERY, DATE/DAY_AND_DATE, NEXT_EVENT, UNREAD_NOTIFICATION_COUNT.
- Weather: not a system complication provider; use `[WEATHER.*]` expressions on face-owned elements.
- Samsung Health specifics (e.g. stress): only where the platform exposes them as standard providers on Watch5 Pro; verify per-slot with device evidence during #26/#28. Nothing invented.
- Blank-state semantics: every slot keeps `EMPTY` in `supportedTypes` with EMPTY default system provider; unset slots stay clean/blank (current pattern is the contract).
- Left/right curved side bars: real slots via `BoundingArc` (§4.4).

### 4.4 How curved side bars are represented

**Real ComplicationSlots with `BoundingArc` bounds; content drawn with PartDraw `Arc` bound to `[COMPLICATION.RANGED_VALUE_*]` expressions.**

- Left/right bars anchor on the bezel with start/end angles per side; `direction` and `thickness` per slot.
- Slot declares RANGED_VALUE (+ EMPTY) in `supportedTypes`; provider (ours or system) publishes RangedValueComplicationData; arc sweep ∝ value/range.
- Non-complication decorative arcs remain PartDraw `Arc` inside the ambient-dimmed accent group; decoration never substitutes for a slot.

### 4.5 How StarIntel graph/map data reaches the face

**Complication data source services are the only bridge to the face.**

- The face reads only `[COMPLICATION.*]` bindings and face-owned expressions (`[CONFIGURATION.*]`, time/calendar/weather/health expression sources).
- Time series, rates, or geocoded events beyond `/api/v1/stats` require real server endpoints first; per epic rule 7, the missing contract is opened/linked as server/API work — no fabricated values, ever.
- Map imagery: WFF v1 cannot draw a real projected map (no Polyline/Path). Real geo data must arrive as image data (SMALL_IMAGE/MONOCHROMATIC_IMAGE) rendered by a complication provider, or live in Tiles/companion. This contract is binding for #30.
- Complex graphs belong in Tiles (protolayout) rather than the WFF face; the face gets arc/line summaries bounded by §2 row 9.
- The companion DataMap protocol (8 KiB) may carry small configuration values; not imagery or series.

### 4.6 Graph time-range selection: on-face vs companion/config

**Time-range selection is a face-editor `ListConfiguration` option (24h/7d/30d), consumed via `[CONFIGURATION.<id>]`; NOT on-face tap interaction.**

- WFF v1 has no tap-driven config switching; `Launch` is navigation only.
- The system persists the user's option choice; no per-request transport needed for the range axis.
- When real windowed data exists (#29), the selected range drives rendering via expressions; the provider/server must expose windowed metrics matching those ranges — contract opened when #29 starts.
- A companion override (#33) can later ride the existing DataMap protocol with a protocol VERSION bump, layered on the editor default.

### 4.7 Rotating/carousel icon complication feasibility

**Not feasible in WFF v1.** The v1 ComplicationSlot XSD has no carousel/rotation mechanism; on-face interactivity is a single `Launch` tap per element.

#34 must not fake a rotating visual. Supported alternatives:
- Per-slot provider selection in the system complication chooser (the real platform mechanism).
- A provider may vary its published icon (MONOCHROMATIC_IMAGE) between updates — never an on-face animated/interactive rotation.
- #34 stays open until platform evidence shows a supported on-face mechanism (any WFF version bump is gated on Watch5 Pro compatibility proof).

### 4.8 Theming standard provider data without violating provider ownership

**Face-owned styling only; provider-owned content is never restyled beyond slot-level `tintColor`.**

- Standard providers own their content; the face renders what they publish. Styling is limited to slot `tintColor`, the font/color of `[COMPLICATION.TEXT]` inside the slot's own Complication XML, and slot ambient variants.
- No custom artwork replacing provider icons; no provider-content modification.
- Face-owned elements (borders, arcs, labels, graph frames) are themeable via `ColorConfiguration` axes; our own providers may style their renditions.
- #32 builds the theme/token system on these axes; #28 standard-provider slots use tintColor only.

### 4.9 Ambient/AOD constraints and burn-in handling

- `Variant mode="AMBIENT"` only; dim decorative groups, keep clock + selected slot values readable.
- ≤15% illuminated pixels guidance; no PHOTO_IMAGE in ambient; publish *_AMBIENT alternates where supported; low-luminance ambient palettes.
- Every new face-owned decorative element carries an AMBIENT Variant (alpha 0 or low) — same pattern as the current accent group and footer.

### 4.10 Compatibility floor for Watch5 Pro

- **minSdk stays 33 for the watchface module.** Watch5 Pro ships Wear OS 4+; WFF v1 runs on Wear OS 4+. No minSdk or WFF version bump is justified by this audit.
- Nix/CI SDK (platform 36, build-tools 36.0.0) stays as-is.
- Nothing outside WFF v1 is adopted unless evidence forces it and Watch5 Pro compatibility is preserved; the CI WFF v1 validator is the enforcement gate for every face XML change.

---

## 5. Recon provenance

- Five parallel reconnaissance agents reported to a single lead: repo/Nix scout, WFF capability scout, complication/provider scout, interaction scout, test/CI scout.
- Evidence sources: v1 XSDs from `google/watchface` `third_party/wff/specification/documents/1/`; developer.android.com WFF reference (v1 selectors); direct repo inspection at `469e7e0`.
- CI remains PR-triggered exact-head validation (`pull_request` trigger, no path filters).

## 6. Downstream contracts (binding for #26 / #27 / #28 / #32)

- **#26 (complication-slot framework):** real ComplicationSlots with EMPTY blank-state semantics; manifest triple pattern (intent-filter + SUPPORTED_TYPES + UPDATE_PERIOD_SECONDS) per provider; side bars via BoundingArc; file ownership: watchface XML + wear-app complications package.
- **#27 (StarIntel providers + time windows):** publish only data derivable from `/api/v1/stats` or new real server contracts; RANGED_VALUE is the only v1-consumable richer type; 7-char text cap relaxation is #27 provider work; protocol changes require VERSION bump; wire `requestUpdate()` for new providers.
- **#28 (standard providers):** slots via `defaultSystemProvider` (STEP_COUNT, HEART_RATE, WATCH_BATTERY, …); weather via `[WEATHER.*]` on face-owned elements; Samsung/stress only where the platform exposes it; theming via slot tintColor only.
- **#32 (theme/token system):** ColorConfiguration axes bound to face-owned elements + slot tintColor; ListConfiguration face-style axis; AMBIENT Variants on all face-owned decorative elements; no provider-content restyling.
- **#29/#30:** consume §4.5; link server/API work when real time-series/geo contracts are missing.
- **#33:** integrate stable schemas from #26/#27/#29/#30/#32; range override rides DataMap with VERSION bump.
- **#35–#37:** blocked on per-face design gate (#24 references); implemented as option-scoped groups inside the single face.
- **#39:** test infrastructure may start early; closes last with user-confirmed real-device verification.

## 7. Acceptance checklist (from #25)

- [x] Clean clone works through Nix (flake check + check + build-all green at this head)
- [x] Architecture note committed (`docs/architecture/2026-09-11-wff-multi-face-architecture.md`)
- [x] Feature matrix marked supported / supported-with-constraint / not supported with evidence
- [x] No speculative API claims (Flavors-in-v1 flagged UNVERIFIED, validator-checked before use)
- [x] Existing Android/Wear/WFF/Nix CI remains green
- [x] Downstream issues can implement against explicit contracts instead of guessing
