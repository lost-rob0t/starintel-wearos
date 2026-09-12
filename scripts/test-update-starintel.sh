#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
updater="$root/scripts/update-starintel.sh"

tmp="$(mktemp -d -t starintel-update-test.XXXXXXXX)"
trap 'rm -rf "$tmp"' EXIT

work="$tmp/work"
remote="$tmp/remote.git"
mkdir -p "$work"
git -C "$work" init -q -b main
git -C "$work" config user.name "StarIntel CI"
git -C "$work" config user.email "ci@starintel.invalid"
printf 'one\n' >"$work/file"
git -C "$work" add file
git -C "$work" commit -qm "one"
first="$(git -C "$work" rev-parse HEAD)"
git -C "$work" tag v0.1.0

printf 'two\n' >>"$work/file"
git -C "$work" commit -qam "two"
second="$(git -C "$work" rev-parse HEAD)"
git -C "$work" tag v0.2.0
git -C "$work" tag master-channel
git -C "$work" tag v0.3.0-rc1

git clone -q --bare "$work" "$remote"
git -C "$remote" symbolic-ref HEAD refs/heads/main

master_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --source master)"
grep -q '^resolved_ref=refs/heads/main$' <<<"$master_output"
grep -q "^commit=$second$" <<<"$master_output"

main_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --source main)"
grep -q '^resolved_ref=refs/heads/main$' <<<"$main_output"
grep -q "^commit=$second$" <<<"$main_output"

exact_tag_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --tag v0.1.0)"
grep -q '^resolved_ref=refs/tags/v0.1.0$' <<<"$exact_tag_output"
grep -q "^commit=$first$" <<<"$exact_tag_output"

# Automatic tagged mode ignores the rolling master channel and prereleases.
latest_tag_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --latest-tag)"
grep -q '^resolved_ref=refs/tags/v0.2.0$' <<<"$latest_tag_output"
grep -q "^commit=$second$" <<<"$latest_tag_output"

# Exact-tag mode still permits an intentional prerelease selection.
prerelease_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --tag v0.3.0-rc1)"
grep -q '^resolved_ref=refs/tags/v0.3.0-rc1$' <<<"$prerelease_output"
grep -q "^commit=$second$" <<<"$prerelease_output"

# If a real master branch appears later, --source master must prefer it over HEAD/main.
git -C "$work" branch master "$first"
git -C "$work" push -q "$remote" master
master_output="$(STARINTEL_UPDATE_REMOTE="$remote" bash "$updater" --resolve-only --source master)"
grep -q '^resolved_ref=refs/heads/master$' <<<"$master_output"
grep -q "^commit=$first$" <<<"$master_output"

state="$tmp/state"
printf 'commit=%s\n' "$second" >"$state"
check_output="$(STARINTEL_UPDATE_REMOTE="$remote" STARINTEL_UPDATE_STATE="$state" bash "$updater" --check --source main)"
grep -q '^status=up-to-date$' <<<"$check_output"

printf 'commit=%s\n' "$first" >"$state"
check_output="$(STARINTEL_UPDATE_REMOTE="$remote" STARINTEL_UPDATE_STATE="$state" bash "$updater" --check --source main)"
grep -q '^status=update-available$' <<<"$check_output"

echo "update-starintel-tests: OK"
