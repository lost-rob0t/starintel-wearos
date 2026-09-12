#!/usr/bin/env python3
"""Verify the WFF theme token contract and Qtile Electric palette."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WATCHFACE = Path("watchface/src/main/res/raw/watchface.xml")
EXPECTED_IDS = {"0", "1", "2", "3", "4", "5"}
TOKEN_COUNT = 13
BACKGROUND_TOKEN = 4
QTILE_THEME_ID = "1"
QTILE_COLORS = [
    "#2DE2E6",  # face primary / cyan
    "#F6019D",  # face secondary / pink
    "#F3F4F5",  # readable text
    "#170C32",  # dark structure / progress track
    "#202146",  # real Qtile bar background
    "#92406E",  # slot 1
    "#FBA922",  # slot 2
    "#2DE2E6",  # slot 3
    "#F6019D",  # slot 4 curved bar
    "#62FF00",  # slot 5 curved bar
    "#DD546E",  # slot 6
    "#9700CC",  # slot 7 graph frame
    "#F3F4F5",  # slot 8 weather/geo
]
TOKEN_RE = re.compile(r"\[CONFIGURATION\.themeColor\.(\d+)\]")
HEX_RE = re.compile(r"#[0-9A-Fa-f]{6,8}")


def fail(message: str) -> None:
    print(f"theme-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    raw = WATCHFACE.read_text(encoding="utf-8")
    root = ET.fromstring(raw)
    configs = root.findall("./UserConfigurations/ColorConfiguration")
    theme = next((item for item in configs if item.get("id") == "themeColor"), None)
    if theme is None:
        fail("missing ColorConfiguration id=themeColor")

    options = theme.findall("ColorOption")
    ids = {item.get("id") for item in options}
    if ids != EXPECTED_IDS:
        fail(f"theme ids {sorted(ids)} != expected {sorted(EXPECTED_IDS)}")

    by_id = {item.get("id", ""): item for item in options}
    for option in options:
        colors = option.get("colors", "").split()
        if len(colors) != TOKEN_COUNT:
            fail(f"theme {option.get('id')} has {len(colors)} colors, expected {TOKEN_COUNT}")
        if any(HEX_RE.fullmatch(color) is None for color in colors):
            fail(f"theme {option.get('id')} contains invalid color token")

    qtile = [color.upper() for color in by_id[QTILE_THEME_ID].get("colors", "").split()]
    if qtile != QTILE_COLORS:
        fail(f"Qtile Electric palette drifted: {qtile}")
    if qtile[BACKGROUND_TOKEN] != "#202146":
        fail("Qtile Electric background must remain the real Qtile bar color #202146")

    referenced = {int(index) for index in TOKEN_RE.findall(raw)}
    if referenced != set(range(TOKEN_COUNT)):
        fail(f"referenced token indexes {sorted(referenced)} != expected {list(range(TOKEN_COUNT))}")

    # Remove palette declarations and the Scene's black safety fallback before
    # looking for scattered face-owned literal colors.
    scrubbed = re.sub(r'<ColorOption[^>]+colors="[^"]+"[^>]*/>', "", raw)
    scrubbed = scrubbed.replace('backgroundColor="#000000"', 'backgroundColor="AMOLED_FALLBACK"')
    literals = sorted(set(HEX_RE.findall(scrubbed)))
    if literals:
        fail(f"face-owned literal colors remain outside theme tokens: {literals}")

    print("theme-contract: OK")


if __name__ == "__main__":
    main()
