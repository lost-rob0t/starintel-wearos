#!/usr/bin/env python3
"""Deterministically verify the shared WFF complication-slot contract."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WATCHFACE = Path("watchface/src/main/res/raw/watchface.xml")
EXPECTED = {
    1: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    2: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    3: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    4: ("BoundingArc", {"RANGED_VALUE", "EMPTY"}),
    5: ("BoundingArc", {"RANGED_VALUE", "EMPTY"}),
    6: ("BoundingRoundBox", {"SHORT_TEXT", "LONG_TEXT", "EMPTY"}),
}


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

    for side_id in (4, 5):
        policy = by_id[side_id].find("DefaultProviderPolicy")
        if policy is None:
            fail(f"slot {side_id}: missing DefaultProviderPolicy")
        if policy.get("defaultSystemProvider") != "EMPTY":
            fail(f"slot {side_id}: side slot must default to EMPTY")

    print("slot-contract: OK")


if __name__ == "__main__":
    main()
