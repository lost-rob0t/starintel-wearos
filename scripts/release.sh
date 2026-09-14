#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/release.sh vX.Y.Z[-suffix] [--dry-run] [--skip-local-checks] [--no-wait]

Creates a release tag from an exact, clean main HEAD. The tag triggers the
GitHub Release workflow, which rebuilds every APK through Nix, validates all
three watch faces and signer parity, and publishes versioned release assets.

Options:
  --dry-run            Validate everything without creating or pushing a tag.
  --skip-local-checks  Skip the duplicate local build/test pass. CI still gates release.
  --no-wait            Do not wait for the GitHub release after pushing the tag.
EOF
}

version="${1:-}"
if [[ -z "$version" || "$version" == "-h" || "$version" == "--help" ]]; then
  usage
  [[ -n "$version" ]] && exit 0
  exit 2
fi
shift

dry_run=false
skip_local_checks=false
wait_for_release=true
while (($#)); do
  case "$1" in
    --dry-run) dry_run=true ;;
    --skip-local-checks) skip_local_checks=true ;;
    --no-wait) wait_for_release=false ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ ! "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]]; then
  echo "error: version must look like v0.1.1-alpha" >&2
  exit 2
fi

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$repo_root" ]]; then
  echo "error: run this inside the starintel-wearos git repository" >&2
  exit 1
fi
cd "$repo_root"

branch="$(git branch --show-current)"
if [[ "$branch" != "main" ]]; then
  echo "error: releases must be cut from main, current branch is $branch" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is not clean" >&2
  git status --short >&2
  exit 1
fi

git fetch origin main --tags
head_sha="$(git rev-parse HEAD)"
remote_sha="$(git rev-parse origin/main)"
if [[ "$head_sha" != "$remote_sha" ]]; then
  echo "error: HEAD ($head_sha) is not exact origin/main ($remote_sha)" >&2
  exit 1
fi

if git show-ref --verify --quiet "refs/tags/$version" || \
   git ls-remote --exit-code --tags origin "refs/tags/$version" >/dev/null 2>&1; then
  echo "error: tag already exists: $version" >&2
  exit 1
fi

expected="${version#v}"
version_codes=()
for module in phone-app wear-app watchface; do
  file="$module/build.gradle.kts"
  actual="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' "$file")"
  code="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)/\1/p' "$file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "error: $file has versionName=$actual; expected $expected" >&2
    exit 1
  fi
  if [[ -z "$code" ]]; then
    echo "error: could not read versionCode from $file" >&2
    exit 1
  fi
  version_codes+=("$code")
done
if [[ "${version_codes[0]}" != "${version_codes[1]}" || "${version_codes[0]}" != "${version_codes[2]}" ]]; then
  echo "error: application versionCode values are not synchronized: ${version_codes[*]}" >&2
  exit 1
fi

if [[ "$skip_local_checks" == false ]]; then
  echo "==> validating pinned Nix environment"
  nix flake check --no-update-lock-file --show-trace

  echo "==> running Android tests"
  nix develop --no-update-lock-file --command \
    gradle --stacktrace :phone-app:testDebugUnitTest :wear-app:testDebugUnitTest

  echo "==> validating all three watch-face contracts"
  python3 scripts/check-watchface-slot-contract.py
  python3 scripts/check-watchface-layout.py
  python3 scripts/check-watchface-themes.py

  echo "==> building APKs"
  nix run --no-update-lock-file .#build-all
  for apk in \
    build/nix/phone-app-debug.apk \
    build/nix/wear-app-debug.apk \
    build/nix/watchface-neon-debug.apk \
    build/nix/watchface-command-debug.apk \
    build/nix/watchface-terminal-debug.apk; do
    test -s "$apk"
  done

  echo "==> verifying phone/watch signer parity"
  nix develop --no-update-lock-file --command bash \
    scripts/verify-companion-signing.sh \
    build/nix/phone-app-debug.apk \
    build/nix/wear-app-debug.apk
fi

if [[ "$dry_run" == true ]]; then
  echo "release dry-run passed: $version @ $head_sha"
  exit 0
fi

echo "==> tagging $version"
git tag -a "$version" -m "StarIntel Wear OS $version"
git push origin "refs/tags/$version"
echo "pushed $version; GitHub Release CI now owns artifact publication"

if [[ "$wait_for_release" == false ]]; then
  exit 0
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "gh not found; not waiting for release publication"
  exit 0
fi

repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)"
if [[ -z "$repo" ]]; then
  echo "gh is not authenticated for this repository; not waiting for release publication"
  exit 0
fi

echo "==> waiting for GitHub release $version"
for _ in $(seq 1 90); do
  if gh release view "$version" --repo "$repo" >/dev/null 2>&1; then
    echo "release published: $version"
    gh release view "$version" --repo "$repo" --json url --jq .url
    exit 0
  fi
  sleep 10
done

echo "error: release was not visible after 15 minutes; inspect GitHub Actions" >&2
exit 1
