#!/usr/bin/env python3
"""Deterministically verify the approved Neon HUD WFF complication-slot contract."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WATCHFACE = Path("watchface/src/main/res/raw/watchface.xml")
EXPECTED = {
    1: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    2: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    3: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    4: ("BoundingArc", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    5: ("BoundingArc", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    6: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    7: ("BoundingRoundBox", {"SMALL_IMAGE", "EMPTY"}),
    8: ("BoundingRoundBox", {"SHORT_TEXT", "EMPTY"}),
}
LOWER_IDS = (1, 2, 3, 6)


def fail(message: str) -> None:
    print(f"slot-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    root = ET.parse(WATCHFACE).getroot()
    slots = root.findall(".//ComplicationSlot")
    by_id: dict[int, ET.Element] = {}

    for slot in slots:
        raw_id = slot.get("slotId")
        if raw_id is None or not raw_id.isdigit():
            fail(f"invalid slotId {raw_id!r}")
        slot_id = int(raw_id)
        if slot_id in by_id:
            fail(f"duplicate slotId {slot_id}")
        by_id[slot_id] = slot

    if set(by_id) != set(EXPECTED):
        fail(f"slot IDs {sorted(by_id)} != expected {sorted(EXPECTED)}")

    for slot_id, (bounding_shape, expected_types) in EXPECTED.items():
        slot = by_id[slot_id]
        actual_types = set(slot.get("supportedTypes", "").split())
        if actual_types != expected_types:
            fail(f"slot {slot_id}: types {sorted(actual_types)} != {sorted(expected_types)}")

        bounds = [child.tag for child in slot if child.tag.startswith("Bounding")]
        if bounds != [bounding_shape]:
            fail(f"slot {slot_id}: bounding element {bounds} != [{bounding_shape!r}]")

        complications = {child.get("type") for child in slot.findall("Complication")}
        if complications != expected_types:
            fail(f"slot {slot_id}: renderers {sorted(complications)} != {sorted(expected_types)}")

        empty = [child for child in slot.findall("Complication") if child.get("type") == "EMPTY"]
        if len(empty) != 1 or list(empty[0]):
            fail(f"slot {slot_id}: EMPTY renderer must be exactly one content-free element")

    lower = [by_id[slot_id].find("BoundingOval") for slot_id in LOWER_IDS]
    boxes = [(int(b.get("x", "0")), int(b.get("y", "0")), int(b.get("width", "0")), int(b.get("height", "0"))) for b in lower if b is not None]
    if len(boxes) != 4 or any(width != height for _, _, width, height in boxes):
        fail("Neon lower complication positions must be four circular BoundingOval slots")

    for side_id in (4, 5):
        policy = by_id[side_id].find("DefaultProviderPolicy")
        if policy is None or policy.get("defaultSystemProvider") != "EMPTY":
            fail(f"slot {side_id}: side slot must default to EMPTY")

    graph_policy = by_id[7].find("DefaultProviderPolicy")
    if graph_policy is None or not graph_policy.get("primaryProvider", "").endswith("ActivityGraphComplicationService"):
        fail("slot 7: activity graph must default to the real StarIntel graph provider")

    weather_policy = by_id[8].find("DefaultProviderPolicy")
    if weather_policy is None or weather_policy.get("defaultSystemProvider") != "EMPTY":
        fail("slot 8: weather provider must remain user-selectable and default EMPTY")

    face_style = root.find("./UserConfigurations/ListConfiguration[@id='faceStyle']")
    if face_style is None:
        fail("missing faceStyle ListConfiguration")
    options = face_style.findall("ListOption")
    if len(options) != 1 or options[0].get("id") != "0":
        fail("#35 must expose exactly the implemented Neon face style until #36/#37 land")

    print("slot-contract: OK")


if __name__ == "__main__":
    main()
