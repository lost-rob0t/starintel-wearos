#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  update-starintel [--source master|main|tagged|tag:NAME] [options]
  update-starintel --tag NAME [options]
  update-starintel --latest-tag [options]
  update-starintel --check [--source ...]
  update-starintel --list-tags

Source modes:
  --source master     Track the remote master branch. If this repository has no
                      master branch, follow the remote default branch (currently main).
  --source main       Track refs/heads/main explicitly.
  --source tagged     Install the highest version-like tag.
  --source tag:NAME   Install exactly NAME.
  --tag NAME          Alias for --source tag:NAME.
  --latest-tag        Alias for --source tagged.

Targets:
  --watch SERIAL      Install Wear app + watch face on this adb serial.
                      ANDROID_SERIAL is used when SERIAL is omitted.
  --phone SERIAL      Also install the matching Android companion on SERIAL.
  --no-watch          Do not install to a watch (use with --phone or --build-only).
  --build-only        Resolve and build the selected source, but do not install.

Safety / inspection:
  --check             Resolve the selected source and compare with updater state only.
  --resolve-only      Print the immutable resolved commit and exit.
  --list-tags         List available tags from the update remote.
  --allow-downgrade   Pass adb's downgrade flag when intentionally selecting an older tag.
  --keep-worktree     Keep the temporary checkout and print its path.
  -h, --help          Show this help.

Environment:
  STARINTEL_UPDATE_REMOTE   Git remote URL/path. Default:
                            https://github.com/lost-rob0t/starintel-wearos.git
  STARINTEL_UPDATE_STATE    Override updater state file.
  ANDROID_SERIAL            Default watch adb serial.

Examples:
  nix run .#update -- --source master --watch 10.50.50.69:5555
  nix run .#update -- --latest-tag --watch 10.50.50.69:5555
  nix run .#update -- --tag v0.2.0 --watch 10.50.50.69:5555
  nix run .#update -- --check --source master
EOF
}

remote="${STARINTEL_UPDATE_REMOTE:-https://github.com/lost-rob0t/starintel-wearos.git}"
state_file="${STARINTEL_UPDATE_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/starintel-wearos/update-state}"
source_mode="master"
watch_serial="${ANDROID_SERIAL:-}"
phone_serial=""
install_watch=1
install_phone=0
build_only=0
check_only=0
resolve_only=0
list_tags=0
allow_downgrade=0
keep_worktree=0

while (($#)); do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || { echo "error: --source needs a value" >&2; exit 2; }
      source_mode="$2"
      shift 2
      ;;
    --tag)
      [[ $# -ge 2 ]] || { echo "error: --tag needs a tag name" >&2; exit 2; }
      source_mode="tag:$2"
      shift 2
      ;;
    --latest-tag)
      source_mode="tagged"
      shift
      ;;
    --watch)
      [[ $# -ge 2 ]] || { echo "error: --watch needs an adb serial" >&2; exit 2; }
      watch_serial="$2"
      shift 2
      ;;
    --phone)
      [[ $# -ge 2 ]] || { echo "error: --phone needs an adb serial" >&2; exit 2; }
      phone_serial="$2"
      install_phone=1
      shift 2
      ;;
    --no-watch)
      install_watch=0
      shift
      ;;
    --build-only)
      build_only=1
      shift
      ;;
    --check)
      check_only=1
      shift
      ;;
    --resolve-only)
      resolve_only=1
      shift
      ;;
    --list-tags)
      list_tags=1
      shift
      ;;
    --allow-downgrade)
      allow_downgrade=1
      shift
      ;;
    --keep-worktree)
      keep_worktree=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

for command in git mktemp awk sort tail date; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "error: required command is missing: $command" >&2
    exit 1
  }
done

list_remote_tags() {
  git ls-remote --tags --refs "$remote" \
    | awk '{ sub("refs/tags/", "", $2); print $2 }' \
    | sort -V
}

if ((list_tags)); then
  list_remote_tags
  exit 0
fi

validate_tag() {
  local candidate="$1"
  if [[ -z "$candidate" || ! "$candidate" =~ ^[A-Za-z0-9][A-Za-z0-9._/+~-]*$ ]]; then
    echo "error: invalid tag name: $candidate" >&2
    exit 2
  fi
}

resolve_master_branch() {
  local explicit
  explicit="$(git ls-remote --heads "$remote" refs/heads/master | awk 'NR == 1 { print $2 }')"
  if [[ -n "$explicit" ]]; then
    printf '%s\n' "master"
    return
  fi

  local default_branch
  default_branch="$({ git ls-remote --symref "$remote" HEAD || true; } \
    | awk '$1 == "ref:" { sub("refs/heads/", "", $2); print $2; exit }')"
  if [[ -z "$default_branch" ]]; then
    echo "error: remote has neither refs/heads/master nor a discoverable default branch" >&2
    exit 1
  fi
  printf '%s\n' "$default_branch"
}

resolved_kind=""
resolved_name=""
case "$source_mode" in
  master)
    resolved_kind="branch"
    resolved_name="$(resolve_master_branch)"
    ;;
  main)
    resolved_kind="branch"
    resolved_name="main"
    ;;
  tagged)
    resolved_kind="tag"
    resolved_name="$(list_remote_tags | tail -n 1)"
    if [[ -z "$resolved_name" ]]; then
      echo "error: no tags exist on update remote" >&2
      exit 1
    fi
    ;;
  tag:*)
    resolved_kind="tag"
    resolved_name="${source_mode#tag:}"
    validate_tag "$resolved_name"
    ;;
  *)
    echo "error: unsupported source mode: $source_mode" >&2
    echo "hint: use master, main, tagged, or tag:NAME" >&2
    exit 2
    ;;
esac

workdir="$(mktemp -d -t starintel-wearos-update.XXXXXXXX)"
cleanup() {
  if ((keep_worktree)); then
    echo "kept update worktree: $workdir" >&2
  else
    rm -rf "$workdir"
  fi
}
trap cleanup EXIT

git -C "$workdir" init -q
git -C "$workdir" remote add origin "$remote"

if [[ "$resolved_kind" == "branch" ]]; then
  if ! git -C "$workdir" fetch -q --depth=1 origin \
    "refs/heads/$resolved_name:refs/remotes/origin/$resolved_name"; then
    echo "error: branch does not exist on update remote: $resolved_name" >&2
    exit 1
  fi
  resolved_commit="$(git -C "$workdir" rev-parse "refs/remotes/origin/$resolved_name^{commit}")"
  immutable_ref="refs/heads/$resolved_name"
else
  if ! git -C "$workdir" fetch -q --depth=1 origin \
    "refs/tags/$resolved_name:refs/tags/$resolved_name"; then
    echo "error: tag does not exist on update remote: $resolved_name" >&2
    exit 1
  fi
  resolved_commit="$(git -C "$workdir" rev-parse "refs/tags/$resolved_name^{commit}")"
  immutable_ref="refs/tags/$resolved_name"
fi

printf 'source=%s\n' "$source_mode"
printf 'resolved_ref=%s\n' "$immutable_ref"
printf 'commit=%s\n' "$resolved_commit"

if ((resolve_only)); then
  exit 0
fi

installed_commit=""
if [[ -f "$state_file" ]]; then
  installed_commit="$(awk -F= '$1 == "commit" { print $2; exit }' "$state_file")"
fi

if ((check_only)); then
  if [[ -n "$installed_commit" && "$installed_commit" == "$resolved_commit" ]]; then
    echo "status=up-to-date"
  else
    echo "status=update-available"
    [[ -n "$installed_commit" ]] && echo "installed_commit=$installed_commit"
  fi
  exit 0
fi

command -v nix >/dev/null 2>&1 || {
  echo "error: nix is required to build/install updates" >&2
  exit 1
}

git -C "$workdir" checkout -q --detach "$resolved_commit"

printf 'Building StarIntel from %s (%s)\n' "$immutable_ref" "$resolved_commit"
(
  cd "$workdir"
  nix run --no-update-lock-file .#build-all
)

if ((build_only)); then
  echo "Build completed; install skipped."
  exit 0
fi

if ((install_watch == 0 && install_phone == 0)); then
  echo "error: no install target selected; use --watch, --phone, or --build-only" >&2
  exit 2
fi

install_env=()
if ((allow_downgrade)); then
  install_env+=(STARINTEL_INSTALL_ALLOW_DOWNGRADE=1)
fi

if ((install_watch)); then
  echo "Installing matching Wear app + watch face..."
  if [[ -n "$watch_serial" ]]; then
    (
      cd "$workdir"
      env "${install_env[@]}" ANDROID_SERIAL="$watch_serial" \
        nix run --no-update-lock-file .#install-watch -- "$watch_serial"
    )
  else
    (
      cd "$workdir"
      env "${install_env[@]}" nix run --no-update-lock-file .#install-watch
    )
  fi
fi

if ((install_phone)); then
  echo "Installing matching Android companion..."
  (
    cd "$workdir"
    env "${install_env[@]}" nix run --no-update-lock-file .#install-phone -- "$phone_serial"
  )
fi

mkdir -p "$(dirname "$state_file")"
state_tmp="${state_file}.tmp.$$"
{
  printf 'source=%s\n' "$source_mode"
  printf 'resolved_ref=%s\n' "$immutable_ref"
  printf 'commit=%s\n' "$resolved_commit"
  if [[ "$resolved_kind" == "tag" ]]; then
    printf 'tag=%s\n' "$resolved_name"
  fi
  printf 'updated_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$state_tmp"
mv "$state_tmp" "$state_file"

echo "Update installed successfully."
echo "state=$state_file"
