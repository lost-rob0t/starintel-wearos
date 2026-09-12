#!/usr/bin/env python3
"""Deterministically verify the shared WFF v1 slot and Astra face contract."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

WATCHFACE = Path("watchface/src/main/res/raw/watchface.xml")
WATCHFACE_INFO = Path("watchface/src/main/res/xml/watch_face_info.xml")
EXPECTED = {
    1: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    2: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    3: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    4: ("BoundingArc", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    5: ("BoundingArc", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    6: ("BoundingOval", {"SHORT_TEXT", "RANGED_VALUE", "EMPTY"}),
    7: ("BoundingRoundBox", {"SMALL_IMAGE", "EMPTY"}),
    8: ("BoundingRoundBox", {"SHORT_TEXT", "SMALL_IMAGE", "EMPTY"}),
}
LOWER_IDS = (1, 2, 3, 6)
SIDE_IDS = (4, 5)
RANGED_IDS = LOWER_IDS + SIDE_IDS
SLOT_COLOR_TOKEN = {
    1: 5,
    2: 6,
    3: 7,
    4: 8,
    5: 9,
    6: 10,
    7: 11,
    8: 12,
}
FACE_SLOTS = {
    "0": {1, 2, 3, 4, 5, 6, 7, 8},
    "1": {1, 2, 3, 6, 7, 8},
    "2": {1, 2, 3, 6},
}
FACE_EXPRESSIONS = {
    "neon_normal",
    "neon_ultra",
    "command_normal",
    "command_ultra",
    "terminal_normal",
    "terminal_ultra",
}


def fail(message: str) -> None:
    print(f"slot-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def option_ids(config: ET.Element) -> set[str]:
    return {item.get("id", "") for item in config.findall("ListOption")}


def serialized(element: ET.Element) -> str:
    return ET.tostring(element, encoding="unicode")


def main() -> None:
    raw = WATCHFACE.read_text(encoding="utf-8")
    root = ET.fromstring(raw)
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

        token = f"[CONFIGURATION.themeColor.{SLOT_COLOR_TOKEN[slot_id]}]"
        if token not in serialized(slot):
            fail(f"slot {slot_id}: missing dedicated complication color token {token}")

    lower = [by_id[slot_id].find("BoundingOval") for slot_id in LOWER_IDS]
    boxes = [
        (
            int(box.get("x", "0")),
            int(box.get("y", "0")),
            int(box.get("width", "0")),
            int(box.get("height", "0")),
        )
        for box in lower
        if box is not None
    ]
    if len(boxes) != 4 or any(width != height for _, _, width, height in boxes):
        fail("lower metric positions must remain four circular BoundingOval slots")

    for slot_id in RANGED_IDS:
        ranged = by_id[slot_id].find("Complication[@type='RANGED_VALUE']")
        if ranged is None:
            fail(f"slot {slot_id}: missing RANGED_VALUE renderer")
        text = serialized(ranged)
        for source in (
            "[COMPLICATION.RANGED_VALUE_VALUE]",
            "[COMPLICATION.RANGED_VALUE_MIN]",
            "[COMPLICATION.RANGED_VALUE_MAX]",
        ):
            if source not in text:
                fail(f"slot {slot_id}: progress renderer must consume {source}")

    for side_id in SIDE_IDS:
        policy = by_id[side_id].find("DefaultProviderPolicy")
        if policy is None or policy.get("defaultSystemProvider") != "EMPTY":
            fail(f"slot {side_id}: side slot must default to EMPTY")
        ranged = by_id[side_id].find("Complication[@type='RANGED_VALUE']")
        assert ranged is not None
        dynamic_arcs = ranged.findall(".//Arc/Transform[@target='endAngle']")
        if len(dynamic_arcs) != 1:
            fail(f"slot {side_id}: curved ranged renderer must have one dynamic fill arc")
        if "[CONFIGURATION.themeColor.3]" not in serialized(ranged):
            fail(f"slot {side_id}: curved ranged renderer must expose an unfilled track")

    for lower_id in LOWER_IDS:
        ranged = by_id[lower_id].find("Complication[@type='RANGED_VALUE']")
        assert ranged is not None
        if ranged.find(".//Arc/Transform[@target='endAngle']") is None:
            fail(f"slot {lower_id}: Neon renderer must have a dynamic circular fill")
        if ranged.find(".//Rectangle/Transform[@target='width']") is None:
            fail(f"slot {lower_id}: Command/Terminal renderer must have a dynamic horizontal fill")
        if "[CONFIGURATION.themeColor.3]" not in serialized(ranged):
            fail(f"slot {lower_id}: ranged renderer must expose an unfilled track")

    graph_policy = by_id[7].find("DefaultProviderPolicy")
    if graph_policy is None or not graph_policy.get("primaryProvider", "").endswith("ActivityGraphComplicationService"):
        fail("slot 7: activity graph must default to the real StarIntel graph provider")

    weather_geo_policy = by_id[8].find("DefaultProviderPolicy")
    if weather_geo_policy is None or weather_geo_policy.get("defaultSystemProvider") != "EMPTY":
        fail("slot 8: shared weather/geo provider must remain user-selectable and default EMPTY")

    face_style = root.find("./UserConfigurations/ListConfiguration[@id='faceStyle']")
    if face_style is None:
        fail("missing faceStyle ListConfiguration")
    if option_ids(face_style) != set(FACE_SLOTS):
        fail(f"faceStyle options {sorted(option_ids(face_style))} != {sorted(FACE_SLOTS)}")
    for option in face_style.findall("ListOption"):
        option_id = option.get("id", "")
        actual = {int(value) for value in option.get("complicationSlotIds", "").split()}
        if actual != FACE_SLOTS[option_id]:
            fail(f"face {option_id}: slots {sorted(actual)} != {sorted(FACE_SLOTS[option_id])}")

    presentation = root.find("./UserConfigurations/ListConfiguration[@id='presentationMode']")
    if presentation is None or option_ids(presentation) != {"0", "1"}:
        fail("presentationMode must expose exactly Normal (0) and Ultra Black (1)")

    background = root.find("./Scene/PartDraw/Rectangle")
    background_draw = root.find("./Scene/PartDraw")
    if background is None or background.find("Fill") is None:
        fail("missing face-owned normal-mode background")
    if background.find("Fill").get("color") != "[CONFIGURATION.themeColor.4]":
        fail("normal-mode background must use themeColor.4")
    if background_draw is None or "presentationMode" not in serialized(background_draw):
        fail("theme background must be suppressed in Ultra Black mode")

    conditions = root.findall("./Scene/Condition")
    if len(conditions) != 1:
        fail("scene must use one face/presentation Condition")
    names = {
        expression.get("name", "")
        for expression in conditions[0].findall("./Expressions/Expression")
    }
    if names != FACE_EXPRESSIONS:
        fail(f"face/presentation expressions {sorted(names)} != {sorted(FACE_EXPRESSIONS)}")

    compares = {compare.get("expression", "") for compare in conditions[0].findall("Compare")}
    if compares != FACE_EXPRESSIONS:
        fail("every face/presentation expression must have a render branch")

    graph = by_id[7].find("Complication[@type='SMALL_IMAGE']/PartImage")
    if graph is None or "presentationMode" not in serialized(graph):
        fail("activity graph must be suppressed in Ultra Black mode")

    geo = by_id[8].find("Complication[@type='SMALL_IMAGE']/PartImage")
    if geo is None or "presentationMode" not in serialized(geo):
        fail("geo image must be suppressed in Ultra Black mode")

    info = ET.parse(WATCHFACE_INFO).getroot()
    editable = info.find("Editable")
    multiple = info.find("MultipleInstancesAllowed")
    if editable is None or editable.get("value") != "true":
        fail("watch face must remain editable so all three face styles are selectable")
    if multiple is None or multiple.get("value") != "true":
        fail("watch face must allow multiple configured StarIntel instances")

    print("slot-contract: OK")


if __name__ == "__main__":
    main()
