#!/usr/bin/env python3
"""Verify the three dedicated WFF v1 faces and their complication contracts."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FACES = {
    "neon": (Path("watchface/src/main/res/raw/watchface.xml"), {1, 2, 3, 4, 5, 6, 7, 8}),
    "command": (Path("watchface/src/command/res/raw/watchface.xml"), {1, 2, 3, 6, 7, 8}),
    "terminal": (Path("watchface/src/terminal/res/raw/watchface.xml"), {1, 2, 3, 6}),
}
TOKEN = {1: 5, 2: 6, 3: 7, 4: 8, 5: 9, 6: 10, 7: 11, 8: 12}
RANGED_SOURCES = (
    "[COMPLICATION.RANGED_VALUE_VALUE]",
    "[COMPLICATION.RANGED_VALUE_MIN]",
    "[COMPLICATION.RANGED_VALUE_MAX]",
)


def fail(message: str) -> None:
    print(f"slot-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


def serialized(element: ET.Element) -> str:
    return ET.tostring(element, encoding="unicode")


def check_face(name: str, path: Path, expected_ids: set[int]) -> None:
    root = ET.parse(path).getroot()
    if root.find("./UserConfigurations/ListConfiguration[@id='faceStyle']") is not None:
        fail(f"{name}: faceStyle must not exist in dedicated APK")
    presentation = root.find("./UserConfigurations/ListConfiguration[@id='presentationMode']")
    if presentation is None:
        fail(f"{name}: missing presentationMode")
    options = {node.get("id") for node in presentation.findall("ListOption")}
    if options != {"0", "1"}:
        fail(f"{name}: presentationMode must be Normal/Ultra Black")

    slots = root.findall(".//ComplicationSlot")
    by_id: dict[int, ET.Element] = {}
    for slot in slots:
        raw_id = slot.get("slotId", "")
        if not raw_id.isdigit():
            fail(f"{name}: invalid slotId {raw_id!r}")
        slot_id = int(raw_id)
        if slot_id in by_id:
            fail(f"{name}: duplicate slotId {slot_id}")
        by_id[slot_id] = slot
    if set(by_id) != expected_ids:
        fail(f"{name}: slots {sorted(by_id)} != {sorted(expected_ids)}")
    if len(by_id) > 8:
        fail(f"{name}: WFF v1 slot cap exceeded")

    for slot_id, slot in by_id.items():
        supported = set(slot.get("supportedTypes", "").split())
        if "EMPTY" not in supported:
            fail(f"{name} slot {slot_id}: EMPTY must be supported")
        rendered = {node.get("type") for node in slot.findall("Complication")}
        if rendered != supported:
            fail(f"{name} slot {slot_id}: renderers {sorted(rendered)} != supported {sorted(supported)}")
        empty = slot.findall("Complication[@type='EMPTY']")
        if len(empty) != 1 or list(empty[0]):
            fail(f"{name} slot {slot_id}: EMPTY renderer must stay content-free")

        text = serialized(slot)
        accent = f"[CONFIGURATION.themeColor.{TOKEN[slot_id]}]"
        if accent not in text:
            fail(f"{name} slot {slot_id}: missing dedicated accent {accent}")

        # Every configured renderer, not just the slot as a whole, must have an
        # Ultra Black branch with a one-pixel outline. This prevents e.g. a
        # RANGED_VALUE provider from accidentally retaining a thick progress bar.
        for renderer in slot.findall("Complication"):
            kind = renderer.get("type", "")
            if kind == "EMPTY":
                continue
            renderer_text = serialized(renderer)
            if "presentationMode" not in renderer_text or 'thickness="1"' not in renderer_text:
                fail(f"{name} slot {slot_id} {kind}: missing one-pixel Ultra Black wireframe")

        ranged = slot.find("Complication[@type='RANGED_VALUE']")
        if ranged is not None:
            ranged_text = serialized(ranged)
            for source in RANGED_SOURCES:
                if source not in ranged_text:
                    fail(f"{name} slot {slot_id}: ranged renderer ignores {source}")

    if name == "command":
        graph = by_id[7].find("DefaultProviderPolicy")
        geo = by_id[8].find("DefaultProviderPolicy")
        if graph is None or not graph.get("primaryProvider", "").endswith("ActivityGraphComplicationService"):
            fail("command: slot 7 must use the real activity graph provider")
        if geo is None or not geo.get("primaryProvider", "").endswith("GeoActivityComplicationService"):
            fail("command: slot 8 must use the real geo provider")


def main() -> None:
    for name, (path, ids) in FACES.items():
        check_face(name, path, ids)
    print("slot-contract: OK")


if __name__ == "__main__":
    main()
