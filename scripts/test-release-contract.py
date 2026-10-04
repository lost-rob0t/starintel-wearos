#!/usr/bin/env python3
"""Fail when an installable APK falls out of CI or release publication."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent

APPS = (
    ("phone-app", "phone-app-debug.apk", "starintel-phone", "phone", "actor.starintel.wear"),
    ("quasar-app", "quasar-app-debug.apk", "quasar-android", "quasar", "actor.starintel.quasar"),
    ("collector-app", "collector-app-debug.apk", "starintel-collector", "collector", "actor.starintel.collector"),
    ("hackmode-app", "hackmode-app-debug.apk", "starintel-hackmode", "hackmode", "actor.starintel.hackmode"),
    ("operator-app", "operator-app-debug.apk", "starintel-operator", "operator", "actor.starintel.operator"),
    ("wear-app", "wear-app-debug.apk", "starintel-wear", "wear", "actor.starintel.wear"),
)

FACES = (
    ("neon", "watchface-neon-debug.apk", "starintel-watchface-neon"),
    ("command", "watchface-command-debug.apk", "starintel-watchface-command"),
    ("terminal", "watchface-terminal-debug.apk", "starintel-watchface-terminal"),
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(relative: str, *needles: str) -> None:
    text = read(relative)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        joined = ", ".join(repr(item) for item in missing)
        raise AssertionError(f"{relative} is missing release contract tokens: {joined}")


def version_tuple(module: str) -> tuple[int, str]:
    text = read(f"{module}/build.gradle.kts")
    code = re.search(r"^\s*versionCode = (\d+)\s*$", text, re.MULTILINE)
    name = re.search(r'^\s*versionName = "([^"]+)"\s*$', text, re.MULTILINE)
    if code is None or name is None:
        raise AssertionError(f"cannot resolve version tuple for {module}")
    return int(code.group(1)), name.group(1)


def main() -> int:
    modules = [module for module, *_ in APPS] + ["watchface"]
    versions = {module: version_tuple(module) for module in modules}
    if len(set(versions.values())) != 1:
        raise AssertionError(f"installable module versions differ: {versions}")

    module_loop = "for module in phone-app quasar-app collector-app hackmode-app operator-app wear-app watchface; do"
    require(".github/workflows/release.yml", module_loop)
    require("scripts/release.sh", module_loop)

    for module, staged, release_stem, logical_id, package_id in APPS:
        source = f"{module}/build/outputs/apk/debug/{staged}"
        require("settings.gradle.kts", f'include(":{module}")')
        require("flake.nix", f":{module}:testDebugUnitTest", source, f"build/nix/{staged}")
        require(".github/workflows/android.yml", f":{module}:testDebugUnitTest", f":{module}:assembleDebug", source, f"build/nix/{staged}")
        require(".github/workflows/release.yml", f":{module}:testDebugUnitTest", f"build/nix/{staged}", f'dist/{release_stem}-${{VERSION}}.apk')
        require(".github/workflows/update-channel.yml", f"build/nix/{staged}", f"dist/{release_stem}-master.apk")
        require("scripts/release.sh", f":{module}:testDebugUnitTest", f"build/nix/{staged}")
        require("scripts/generate-update-manifest.py", f'"{logical_id}"', release_stem, package_id)
        require("scripts/test-update-manifest.py", release_stem, f'["{logical_id}"]', package_id)

    for variant, staged, release_stem in FACES:
        source = f"watchface/build/outputs/apk/{variant}/debug/{staged}"
        require("flake.nix", f":watchface:assemble{variant.title()}Debug", source, f"build/nix/{staged}")
        require(".github/workflows/android.yml", f":watchface:assemble{variant.title()}Debug", source, f"build/nix/{staged}")
        require(".github/workflows/release.yml", f"build/nix/{staged}", f'dist/{release_stem}-${{VERSION}}.apk')
        require(".github/workflows/update-channel.yml", f"build/nix/{staged}", f"dist/{release_stem}-master.apk")
        require("scripts/release.sh", f"build/nix/{staged}")
        require("scripts/generate-update-manifest.py", release_stem)
        require("scripts/test-update-manifest.py", release_stem)

    require(".github/workflows/android.yml", "python3 scripts/test-release-contract.py")
    require(".github/workflows/android.yml", "nix develop --no-update-lock-file --command gradle")
    require(".github/workflows/release.yml", "python3 scripts/test-release-contract.py")
    require(".github/workflows/update-channel.yml", "python3 scripts/test-release-contract.py")
    require("scripts/release.sh", "python3 scripts/test-release-contract.py")
    print(f"release-contract: ok ({len(APPS) + len(FACES)} APKs, version={next(iter(versions.values()))})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
