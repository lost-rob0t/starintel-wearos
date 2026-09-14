#!/usr/bin/env python3
"""Fail when a dedicated Watch5 Pro face clips or overlaps rendered content."""

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
ARC_SAMPLE_DEGREES = 1
FACES = {
    "neon": Path("watchface/src/neon/res/raw/watchface.xml"),
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


@dataclass(frozen=True)
class ArcSpec:
    slot_id: int
    cx: float
    cy: float
    width: float
    height: float
    thickness: float
    start: float
    end: float


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
    return overlaps(box, Box(-1, x, y, w, h))


def integer(node: ET.Element, key: str) -> int:
    try:
        return int(node.get(key, "0"))
    except ValueError:
        fail(f"{node.tag}: {key} must be a static integer for layout validation")
        raise AssertionError("unreachable")


def number(node: ET.Element, key: str) -> float:
    try:
        return float(node.get(key, "0"))
    except ValueError:
        fail(f"{node.tag}: {key} must be numeric for layout validation")
        raise AssertionError("unreachable")


def check_box_inside(
    *,
    face: str,
    slot_id: int,
    node: ET.Element,
    container_w: int,
    container_h: int,
) -> None:
    x = integer(node, "x")
    y = integer(node, "y")
    width = integer(node, "width")
    height = integer(node, "height")
    if width <= 0 or height <= 0:
        fail(f"{face} slot {slot_id}: {node.tag} has invalid {width}x{height} box")
    if x < 0 or y < 0 or x + width > container_w or y + height > container_h:
        fail(
            f"{face} slot {slot_id}: {node.tag} ({x},{y},{width},{height}) "
            f"clips its {container_w}x{container_h} slot"
        )


def check_draw_geometry(
    *,
    face: str,
    slot_id: int,
    draw: ET.Element,
) -> None:
    width = integer(draw, "width")
    height = integer(draw, "height")

    for node in draw.findall("Rectangle") + draw.findall("Ellipse"):
        x = integer(node, "x")
        y = integer(node, "y")
        node_w = integer(node, "width")
        node_h = integer(node, "height")
        if x < 0 or y < 0 or x + node_w > width or y + node_h > height:
            fail(f"{face} slot {slot_id}: {node.tag} geometry clips its PartDraw")

    for line in draw.findall("Line"):
        coords = (
            integer(line, "startX"),
            integer(line, "startY"),
            integer(line, "endX"),
            integer(line, "endY"),
        )
        if not all(0 <= value <= limit for value, limit in zip(coords, (width, height, width, height))):
            fail(f"{face} slot {slot_id}: Line geometry leaves its PartDraw: {coords}")

    for arc in draw.findall("Arc"):
        cx = integer(arc, "centerX")
        cy = integer(arc, "centerY")
        arc_w = integer(arc, "width")
        arc_h = integer(arc, "height")
        if cx - arc_w / 2 < 0 or cx + arc_w / 2 > width:
            fail(f"{face} slot {slot_id}: Arc clips horizontally inside its PartDraw")
        if cy - arc_h / 2 < 0 or cy + arc_h / 2 > height:
            fail(f"{face} slot {slot_id}: Arc clips vertically inside its PartDraw")


def check_renderer_content(face: str, slot: ET.Element, slot_id: int) -> None:
    slot_w = integer(slot, "width")
    slot_h = integer(slot, "height")
    for complication in slot.findall("Complication"):
        for node in list(complication):
            if node.tag not in {"PartDraw", "PartText", "PartImage"}:
                continue
            check_box_inside(
                face=face,
                slot_id=slot_id,
                node=node,
                container_w=slot_w,
                container_h=slot_h,
            )
            if node.tag == "PartDraw":
                check_draw_geometry(face=face, slot_id=slot_id, draw=node)


def point_rect_distance(x: float, y: float, box: Box) -> float:
    dx = max(box.x - x, 0.0, x - box.right)
    dy = max(box.y - y, 0.0, y - box.bottom)
    return math.hypot(dx, dy)


def arc_points(arc: ArcSpec):
    # WFF angles use 0 degrees at 12 o'clock and increase clockwise.
    start = arc.start
    end = arc.end
    if end < start:
        end += 360.0
    angle = start
    while angle <= end + 1e-6:
        radians = math.radians(angle)
        x = arc.cx + (arc.width / 2.0) * math.sin(radians)
        y = arc.cy - (arc.height / 2.0) * math.cos(radians)
        yield angle % 360.0, x, y
        angle += ARC_SAMPLE_DEGREES


def check_arc_clearance(face: str, arc: ArcSpec, boxes: list[Box]) -> None:
    outer_radius = max(arc.width, arc.height) / 2.0 + arc.thickness / 2.0
    if outer_radius > SAFE_RADIUS:
        fail(
            f"{face} slot {arc.slot_id}: curved rail outer radius "
            f"{outer_radius:.1f}px exceeds {SAFE_RADIUS:.1f}px safe radius"
        )

    clearance = arc.thickness / 2.0 + MIN_GAP
    for angle, x, y in arc_points(arc):
        for box in boxes:
            if point_rect_distance(x, y, box) < clearance:
                fail(
                    f"{face} slot {arc.slot_id}: curved rail at {angle:.0f}deg "
                    f"collides with slot {box.slot_id} (<{clearance:.1f}px clearance)"
                )
        for index, rect in enumerate(RESERVED[face]):
            reserved = Box(-(index + 1), *rect)
            if point_rect_distance(x, y, reserved) < clearance:
                fail(
                    f"{face} slot {arc.slot_id}: curved rail at {angle:.0f}deg "
                    "collides with face-owned clock/header geometry"
                )


def check_face(name: str, path: Path) -> None:
    root = ET.parse(path).getroot()
    boxes: list[Box] = []
    arcs: list[ArcSpec] = []

    for slot in root.findall(".//ComplicationSlot"):
        slot_id = int(slot.get("slotId", "-1"))
        check_renderer_content(name, slot, slot_id)

        arc = slot.find("BoundingArc")
        if arc is not None:
            spec = ArcSpec(
                slot_id=slot_id,
                cx=number(arc, "centerX"),
                cy=number(arc, "centerY"),
                width=number(arc, "width"),
                height=number(arc, "height"),
                thickness=number(arc, "thickness"),
                start=number(arc, "startAngle"),
                end=number(arc, "endAngle"),
            )
            if spec.width > 410 or spec.height > 410 or spec.thickness > 34:
                fail(f"{name} slot {slot_id}: curved slot exceeds safe bezel envelope")
            arcs.append(spec)
            continue

        box = Box(
            slot_id,
            integer(slot, "x"),
            integer(slot, "y"),
            integer(slot, "width"),
            integer(slot, "height"),
        )
        if box.w <= 0 or box.h <= 0:
            fail(f"{name} slot {slot_id}: invalid dimensions {box.w}x{box.h}")
        if box.x < 0 or box.y < 0 or box.right > CANVAS or box.bottom > CANVAS:
            fail(f"{name} slot {slot_id}: box leaves 450x450 canvas: {box}")

        # Every corner must stay inside a conservative Galaxy Watch5 Pro round safe area.
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

    for arc in arcs:
        check_arc_clearance(name, arc, boxes)


def main() -> None:
    for name, path in FACES.items():
        check_face(name, path)
    print("layout-contract: OK")


if __name__ == "__main__":
    main()
