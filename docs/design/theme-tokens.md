# StarIntel watch-face theme tokens

Issue: #32

## WFF authority

`watchface/src/main/res/raw/watchface.xml` owns the canonical WFF v1 `ColorConfiguration` named `themeColor`.

Every palette provides the same thirteen semantic token positions:

| Index | Meaning |
| ---: | --- |
| `0` | primary face accent |
| `1` | secondary face accent |
| `2` | primary readable text/data |
| `3` | muted structure / unfilled progress track |
| `4` | normal-mode background |
| `5` | complication slot 1 accent |
| `6` | complication slot 2 accent |
| `7` | complication slot 3 accent |
| `8` | complication slot 4 / left curved bar accent |
| `9` | complication slot 5 / right curved bar accent |
| `10` | complication slot 6 accent |
| `11` | complication slot 7 / activity graph accent |
| `12` | complication slot 8 / weather-geo accent |

Face-owned elements reference `[CONFIGURATION.themeColor.N]`; they do not scatter literal palette colors through face geometry.

The Scene keeps a literal black safety fallback. Token 4 is drawn only in Normal presentation mode, so Ultra Black still falls through to true `#000000` even when a normal palette uses a colored background.

## Required palettes

1. **StarIntel Cyan** — cyan primary with cool secondary accent.
2. **Qtile Electric** — the default Neon palette and an exact copy of the user's Qtile/Doom Electric colors where they matter: normal background `#202146`, structure/track `#170C32`, cyan `#2DE2E6`, orange `#FBA922`, pink `#F6019D`, lime `#62FF00`, coral `#DD546E`, purple `#9700CC`, mauve `#92406E`, and readable white `#F3F4F5`.
3. **StarIntel Gold** — warm gold/amber accents.
4. **Tactical Green** — green operational palette.
5. **Monochrome** — white/gray only.
6. **Wear Neutral** — restrained neutral blue/slate palette suitable alongside normal Wear/Samsung UI.

## Complication rendering rule

Each complication has a dedicated accent token. A slot must not borrow the neighboring slot's accent.

`RANGED_VALUE` is rendered as an actual progress visualization driven by provider `MIN`, `MAX`, and `VALUE`:

- Neon lower complications use an unfilled circular track plus a value-driven filled arc.
- Command and Terminal lower complications use an unfilled horizontal track plus a value-driven filled bar.
- Neon curved edge complications use an unfilled bezel track plus a value-driven filled arc.
- `SHORT_TEXT` fallbacks do not fake progress; they show provider text with the slot accent and a neutral track/frame only.

Theme tokens style the StarIntel face's own presentation layer. They do not reinterpret system/Samsung complication values. Unconfigured slots remain blank.

## Face styles and picker behavior

WFF v1 exposes one installed StarIntel face entry. `faceStyle` provides the three selectable styles inside the Wear OS face editor: Neon Geometric HUD, Command Data / Geo, and Terminal Ops. `MultipleInstancesAllowed=true` lets the user keep multiple StarIntel favorites with different face-style configurations while retaining Watch5 Pro-compatible WFF v1 packaging.

## Ambient/AOD

Ambient mode removes decorative accents and progress graphics. Ultra Black suppresses the theme background entirely and keeps the Scene's literal black fallback. Time and configured data remain readable without turning a colored normal-mode background into an AOD fill.

## Validation

`scripts/check-watchface-themes.py` verifies:

- exactly six palette options;
- exactly thirteen tokens per palette;
- all thirteen token indexes are referenced;
- the Qtile Electric palette is locked to the real Qtile values, including `#202146` for token 4;
- no face-owned literal colors remain outside palette declarations, except the Scene's black fallback.

`scripts/check-watchface-slot-contract.py` verifies all three face-style options, multiple-instance/editability metadata, per-slot accent assignment, and value-driven ranged progress tracks/fills.

`nix run .#check` and `nix run .#build-all` execute both contracts before Gradle/WFF validation.
