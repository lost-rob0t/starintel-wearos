#!/usr/bin/env python3
"""Reject watch-face geometry that can clip on a 450px round display."""
from __future__ import annotations

import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CANVAS = 450.0
CENTER = CANVAS / 2.0
SAFE_RADIUS = 210.0
EDGE_SLOT_IDS = {"4", "5"}


def fail(message: str) -> None:
    print(f"watchface-round-bounds: {message}", file=sys.stderr)
    raise SystemExit(1)


def f(node: ET.Element, name: str, default: float = 0.0) -> float:
    try:
        return float(node.attrib.get(name, default))
    except ValueError as exc:
        fail(f"invalid {name}={node.attrib.get(name)!r} on <{node.tag}>: {exc}")


def radial_extent(node: ET.Element) -> float:
    cx = f(node, "centerX", CENTER)
    cy = f(node, "centerY", CENTER)
    width = f(node, "width")
    height = f(node, "height")
    thickness = f(node, "thickness")
    # Conservative outer radius: farthest ellipse semiaxis + half stroke/slot thickness.
    local = max(width, height) / 2.0 + thickness / 2.0
    offset = math.hypot(cx - CENTER, cy - CENTER)
    return offset + local


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "watchface/src/main/res/raw/watchface.xml")
    root = ET.parse(path).getroot()
    if root.attrib.get("width") != "450" or root.attrib.get("height") != "450":
        fail("checker assumes the checked-in 450x450 Watch5 Pro design canvas")

    checked = 0
    for slot in root.iter("ComplicationSlot"):
        if slot.attrib.get("slotId") not in EDGE_SLOT_IDS:
            continue
        for node in slot.iter():
            if node.tag not in {"Arc", "BoundingArc"}:
                continue
            extent = radial_extent(node)
            checked += 1
            if extent > SAFE_RADIUS + 1e-6:
                fail(
                    f"slot {slot.attrib['slotId']} <{node.tag}> reaches radius {extent:.1f}px; "
                    f"safe maximum is {SAFE_RADIUS:.1f}px"
                )

        for text in slot.iter("PartText"):
            x = f(text, "x")
            width = f(text, "width")
            if x < 12.0 or x + width > CANVAS - 12.0:
                fail(
                    f"slot {slot.attrib['slotId']} text box [{x:.0f}, {x + width:.0f}] "
                    "does not preserve the 12px side inset"
                )

    if checked == 0:
        fail("edge complication geometry was not found")
    print(f"watchface-round-bounds: ok ({checked} edge arcs checked, safe radius {SAFE_RADIUS:.0f}px)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
