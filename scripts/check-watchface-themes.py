#!/usr/bin/env python3
"""Verify theme token parity across every dedicated WFF face."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FACES = {
    "neon": Path("watchface/src/neon/res/raw/watchface.xml"),
    "command": Path("watchface/src/command/res/raw/watchface.xml"),
    "terminal": Path("watchface/src/terminal/res/raw/watchface.xml"),
}
EXPECTED_IDS = {"0", "1", "2", "3", "4", "5"}
TOKEN_COUNT = 13
QTILE_THEME_ID = "1"
QTILE_COLORS = [
    "#2DE2E6", "#F6019D", "#F3F4F5", "#170C32", "#202146",
    "#92406E", "#FBA922", "#2DE2E6", "#F6019D", "#62FF00",
    "#DD546E", "#9700CC", "#F3F4F5",
]
TOKEN_RE = re.compile(r"\[CONFIGURATION\.themeColor\.(\d+)\]")
HEX_RE = re.compile(r"#[0-9A-Fa-f]{6,8}")


def fail(message: str) -> None:
    print(f"theme-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    referenced: set[int] = set()
    canonical_palettes: dict[str, tuple[str, ...]] | None = None

    for name, path in FACES.items():
        raw = path.read_text(encoding="utf-8")
        root = ET.fromstring(raw)
        theme = root.find("./UserConfigurations/ColorConfiguration[@id='themeColor']")
        if theme is None:
            fail(f"{name}: missing themeColor")
        options = theme.findall("ColorOption")
        ids = {item.get("id") for item in options}
        if ids != EXPECTED_IDS:
            fail(f"{name}: theme ids {sorted(ids)} != {sorted(EXPECTED_IDS)}")

        palettes: dict[str, tuple[str, ...]] = {}
        for option in options:
            colors = tuple(color.upper() for color in option.get("colors", "").split())
            if len(colors) != TOKEN_COUNT:
                fail(f"{name}: theme {option.get('id')} has {len(colors)} tokens, expected {TOKEN_COUNT}")
            if any(HEX_RE.fullmatch(color) is None for color in colors):
                fail(f"{name}: invalid theme color")
            palettes[option.get("id", "")] = colors

        if palettes[QTILE_THEME_ID] != tuple(QTILE_COLORS):
            fail(f"{name}: Qtile Electric palette drifted")
        if canonical_palettes is None:
            canonical_palettes = palettes
        elif palettes != canonical_palettes:
            fail(f"{name}: palette definitions differ from Neon")

        referenced.update(int(index) for index in TOKEN_RE.findall(raw))
        scrubbed = re.sub(r'<ColorOption[^>]+colors="[^"]+"[^>]*/>', "", raw)
        scrubbed = scrubbed.replace('backgroundColor="#000000"', 'backgroundColor="AMOLED_FALLBACK"')
        literals = sorted(set(HEX_RE.findall(scrubbed)))
        if literals:
            fail(f"{name}: literal colors remain outside theme tokens: {literals}")

    if referenced != set(range(TOKEN_COUNT)):
        fail(f"combined referenced token indexes {sorted(referenced)} != {list(range(TOKEN_COUNT))}")
    print("theme-contract: OK")


if __name__ == "__main__":
    main()
