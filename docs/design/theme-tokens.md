# StarIntel watch-face theme tokens

Issue: #32

## WFF authority

`watchface/src/main/res/raw/watchface.xml` owns the canonical WFF v1 `ColorConfiguration` named `themeColor`.

Every palette provides the same five semantic token positions:

| Index | Meaning |
| ---: | --- |
| `0` | primary accent |
| `1` | secondary accent |
| `2` | primary readable text/data |
| `3` | muted structural accent |
| `4` | background |

Face-owned elements reference `[CONFIGURATION.themeColor.N]`; they do not scatter literal palette colors through face geometry.

The Scene keeps a literal black safety fallback, while the actual full-face background is token 4. All current palettes intentionally keep token 4 AMOLED black.

## Required palettes

1. **StarIntel Cyan** — cyan primary with cool secondary accent.
2. **Neon Magenta/Cyan** — approved neon cyan + magenta direction; default for the current HUD prototype.
3. **StarIntel Gold** — warm gold/amber accents.
4. **Tactical Green** — green operational palette.
5. **Monochrome** — white/gray only.
6. **Wear Neutral** — restrained neutral blue/slate palette suitable alongside normal Wear/Samsung UI.

## Provider data rule

Theme tokens style the StarIntel face's own presentation layer. They do not reinterpret the meaning of system/Samsung complication data. Ranged-value geometry still comes from provider min/max/value, text stays provider text, and unconfigured slots stay blank.

## Ambient/AOD

Ambient mode removes decorative accent groups. Time and configured data remain readable against the AMOLED-black token background. Final face issues may further reduce pixels, but may not introduce a separate hard-coded ambient palette.

## Validation

`scripts/check-watchface-themes.py` verifies:

- exactly six palette options;
- exactly five tokens per palette;
- all five token indexes are actually referenced;
- every palette keeps the AMOLED background token black;
- no face-owned literal colors remain outside palette declarations, except the Scene's black fallback.

`nix run .#check` and `nix run .#build-all` execute both the shared slot contract and theme contract scripts before Gradle/WFF validation.
