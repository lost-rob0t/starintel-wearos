#!/usr/bin/env python3
"""Fail when a dedicated Watch5 Pro face clips or overlaps complication slots."""

from __future__ import annotations

import math
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

CANVAS = 450
CENTER = 225.0
SAFE_RADIUS = 212.0
MIN_GAP = 6
FACES = {
    "neon": Path("watchface/src/main/res/raw/watchface.xml"),
    "command": Path("watchface/src/command/res/raw/watchface.xml"),
    "terminal": Path("watchface/src/terminal/res/raw/watchface.xml"),
}
# Face-owned clock/header regions. Complication rectangles may not enter these.
RESERVED = {
    "neon": [(88, 65, 226, 92)],
    "command": [(76, 72, 298, 110)],
    "terminal": [(62, 68, 334, 104)],
}


@dataclass(frozen=True)
class Box:
    slot_id: int
    x: int
    y: int
    w: int
    h: int

    @property
    def right(self) -> int:
        return self.x + self.w

    @property
    def bottom(self) -> int:
        return self.y + self.h


def fail(message: str) -> None:
    print(f"layout-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def overlaps(a: Box, b: Box, gap: int = 0) -> bool:
    return not (
        a.right + gap <= b.x
        or b.right + gap <= a.x
        or a.bottom + gap <= b.y
        or b.bottom + gap <= a.y
    )


def rect_overlaps(box: Box, rect: tuple[int, int, int, int]) -> bool:
    x, y, w, h = rect
    other = Box(-1, x, y, w, h)
    return overlaps(box, other)


def check_face(name: str, path: Path) -> None:
    root = ET.parse(path).getroot()
    boxes: list[Box] = []
    for slot in root.findall(".//ComplicationSlot"):
        slot_id = int(slot.get("slotId", "-1"))
        # Curved edge slots intentionally occupy the bezel and are validated separately.
        arc = slot.find("BoundingArc")
        if arc is not None:
            width = int(arc.get("width", "0"))
            height = int(arc.get("height", "0"))
            thickness = int(arc.get("thickness", "0"))
            if width > 410 or height > 410 or thickness > 34:
                fail(f"{name} slot {slot_id}: curved slot exceeds safe bezel envelope")
            continue

        box = Box(
            slot_id,
            int(slot.get("x", "0")),
            int(slot.get("y", "0")),
            int(slot.get("width", "0")),
            int(slot.get("height", "0")),
        )
        if box.w <= 0 or box.h <= 0:
            fail(f"{name} slot {slot_id}: invalid dimensions {box.w}x{box.h}")
        if box.x < 0 or box.y < 0 or box.right > CANVAS or box.bottom > CANVAS:
            fail(f"{name} slot {slot_id}: box leaves 450x450 canvas: {box}")

        # Every corner must stay inside the conservative Watch5 Pro round safe area.
        for cx, cy in ((box.x, box.y), (box.right, box.y), (box.x, box.bottom), (box.right, box.bottom)):
            distance = math.hypot(cx - CENTER, cy - CENTER)
            if distance > SAFE_RADIUS:
                fail(f"{name} slot {slot_id}: corner ({cx},{cy}) clips round safe area ({distance:.1f}>{SAFE_RADIUS})")

        for reserved in RESERVED[name]:
            if rect_overlaps(box, reserved):
                fail(f"{name} slot {slot_id}: overlaps face-owned clock/header region {reserved}")
        boxes.append(box)

    for index, left in enumerate(boxes):
        for right in boxes[index + 1 :]:
            if overlaps(left, right, MIN_GAP):
                fail(f"{name}: slot {left.slot_id} and slot {right.slot_id} overlap or have <{MIN_GAP}px gap")


def main() -> None:
    for name, path in FACES.items():
        check_face(name, path)
    print("layout-contract: OK")


if __name__ == "__main__":
    main()
