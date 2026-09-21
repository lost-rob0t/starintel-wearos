#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GENERATOR = ROOT / "scripts" / "generate-update-manifest.py"


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        dist = Path(tmp)
        suffix = "master"
        names = [
            f"starintel-phone-{suffix}.apk",
            f"quasar-android-{suffix}.apk",
            f"starintel-collector-{suffix}.apk",
            f"starintel-hackmode-{suffix}.apk",
            f"starintel-wear-{suffix}.apk",
            f"starintel-watchface-neon-{suffix}.apk",
            f"starintel-watchface-command-{suffix}.apk",
            f"starintel-watchface-terminal-{suffix}.apk",
        ]
        for index, name in enumerate(names, 1):
            (dist / name).write_bytes((name.encode("utf-8") + b"\n") * index)

        subprocess.run(
            [
                sys.executable,
                str(GENERATOR),
                "--dist", str(dist),
                "--channel", "master",
                "--ref", "main",
                "--commit", "a" * 40,
                "--version-code", "3",
                "--version-name", "0.2.0-alpha",
                "--suffix", suffix,
                "--base-url", "https://example.test/master-channel",
            ],
            check=True,
        )
        payload = json.loads((dist / "update.json").read_text())
        assert payload["schema"] == 2
        assert payload["artifacts"]["phone"]["target"] == "phone"
        assert payload["artifacts"]["quasar"]["package"] == "actor.starintel.quasar"
        assert payload["artifacts"]["collector"]["package"] == "actor.starintel.collector"
        assert payload["artifacts"]["hackmode"]["package"] == "actor.starintel.hackmode"
        assert payload["artifacts"]["watchface-neon"]["package"] == "actor.starintel.watchface.neon"
        assert payload["artifacts"]["wear"]["install_order"] > payload["artifacts"]["watchface-terminal"]["install_order"]
        for value in payload["artifacts"].values():
            assert value["url"].startswith("https://")
            assert len(value["sha256"]) == 64
            assert value["bytes"] > 0
    print("update-manifest: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
