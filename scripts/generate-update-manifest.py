#!/usr/bin/env python3
"""Generate the signed-build update manifest consumed by phone and Wear apps."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ARTIFACTS = {
    "phone": ("actor.starintel.wear", Path("build/nix/phone-app-debug.apk"), "starintel-phone.apk"),
    "wear": ("actor.starintel.wear", Path("build/nix/wear-app-debug.apk"), "starintel-wear.apk"),
    "watchface": ("actor.starintel.watchface", Path("build/nix/watchface-debug.apk"), "starintel-watchface.apk"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--channel", required=True, choices=("master", "tagged"))
    parser.add_argument("--ref", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--output", default="build/nix/update.json")
    args = parser.parse_args()

    if args.version_code <= 0:
        raise SystemExit("version code must be positive")

    base_url = f"https://github.com/lost-rob0t/starintel-wearos/releases/download/{args.release_tag}"
    manifest: dict[str, object] = {
        "schema": 1,
        "channel": args.channel,
        "ref": args.ref,
        "commit": args.commit,
        "version_code": args.version_code,
        "version_name": args.version_name,
        "artifacts": {},
    }

    artifacts = manifest["artifacts"]
    assert isinstance(artifacts, dict)
    for name, (package_name, path, published_name) in ARTIFACTS.items():
        if not path.is_file() or path.stat().st_size <= 0:
            raise SystemExit(f"missing APK: {path}")
        artifacts[name] = {
            "package": package_name,
            "url": f"{base_url}/{published_name}",
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
        }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
