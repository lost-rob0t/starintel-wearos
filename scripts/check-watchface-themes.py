#!/usr/bin/env python3
"""Verify the WFF theme token contract and forbid scattered face colors."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WATCHFACE = Path("watchface/src/main/res/raw/watchface.xml")
EXPECTED_IDS = {"0", "1", "2", "3", "4", "5"}
TOKEN_COUNT = 5
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

    for option in options:
        colors = option.get("colors", "").split()
        if len(colors) != TOKEN_COUNT:
            fail(f"theme {option.get('id')} has {len(colors)} colors, expected {TOKEN_COUNT}")
        if any(HEX_RE.fullmatch(color) is None for color in colors):
            fail(f"theme {option.get('id')} contains invalid color token")
        if colors[-1].upper() != "#000000":
            fail(f"theme {option.get('id')} background token must remain AMOLED black")

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
