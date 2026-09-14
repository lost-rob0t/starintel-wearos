#!/usr/bin/env python3
"""Generate the signed-download catalog consumed by StarIntel Companion."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact(path: Path, package: str, target: str, order: int, url: str) -> dict[str, object]:
    if not path.is_file() or path.stat().st_size <= 0:
        raise SystemExit(f"missing package artifact: {path}")
    return {
        "package": package,
        "target": target,
        "install_order": order,
        "url": url,
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--channel", choices=("master", "tagged"), required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--suffix", required=True, help="filename suffix, e.g. master or v0.2.0-alpha")
    args = parser.parse_args()

    if args.version_code <= 0:
        raise SystemExit("version code must be positive")
    base = args.base_url.rstrip("/")
    suffix = args.suffix
    names = {
        "phone": (f"starintel-phone-{suffix}.apk", "actor.starintel.wear", "phone", 0),
        "watchface-neon": (f"starintel-watchface-neon-{suffix}.apk", "actor.starintel.watchface.neon", "wear", 10),
        "watchface-command": (f"starintel-watchface-command-{suffix}.apk", "actor.starintel.watchface.command", "wear", 20),
        "watchface-terminal": (f"starintel-watchface-terminal-{suffix}.apk", "actor.starintel.watchface.terminal", "wear", 30),
        # Wear self-update is deliberately last: replacing the receiver can terminate the process.
        "wear": (f"starintel-wear-{suffix}.apk", "actor.starintel.wear", "wear", 100),
    }

    artifacts = {}
    for logical_id, (filename, package, target, order) in names.items():
        path = args.dist / filename
        artifacts[logical_id] = artifact(path, package, target, order, f"{base}/{filename}")

    payload = {
        "schema": 2,
        "channel": args.channel,
        "ref": args.ref,
        "commit": args.commit,
        "version_code": args.version_code,
        "version_name": args.version_name,
        "artifacts": artifacts,
    }
    output = args.dist / "update.json"
    output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
