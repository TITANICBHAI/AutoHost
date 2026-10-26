#!/usr/bin/env bash

# Shared cache location for downloaded toolchains. Never place generated SDK,
# Gradle, Python environment, or package-cache files inside the Git checkout.
autohost_tool_cache_dir() {
  local repo_root="$1"
  local candidate="${AUTOHOST_TOOL_CACHE:-${XDG_CACHE_HOME:-${HOME:-/tmp}/.cache}/autohost}"

  candidate="$(readlink -m -- "$candidate")"
  case "$candidate" in
    "$repo_root"|"$repo_root"/*)
      candidate="$(readlink -m -- "${HOME:-/tmp}/.cache/autohost")"
      ;;
  esac

  case "$candidate" in
    "$repo_root"|"$repo_root"/*)
      candidate="$(readlink -m -- "${TMPDIR:-/tmp}/autohost-cache-${UID:-user}")"
      ;;
  esac

  case "$candidate" in
    "$repo_root"|"$repo_root"/*)
      echo "ERROR: Could not select a tool cache outside the Git checkout." >&2
      return 1
      ;;
  esac

  mkdir -p -- "$candidate"
  printf '%s\n' "$candidate"
}
