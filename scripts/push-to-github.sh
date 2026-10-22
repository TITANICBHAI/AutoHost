#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: scripts/push-to-github.sh https://github.com/OWNER/REPO.git [branch]" >&2
  echo "Requires GITHUB_PERSONAL_ACCESS_TOKEN to be set as a Replit Secret." >&2
}

repo_url="${1:-}"
if [[ -z "$repo_url" ]]; then
  usage
  exit 2
fi

if [[ ! "$repo_url" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(\.git)?$ ]]; then
  echo "Use a plain HTTPS GitHub repository URL without embedded credentials." >&2
  exit 2
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Run this command from inside the project's Git working tree." >&2
  exit 2
fi

branch="${2:-$(git branch --show-current)}"
if [[ -z "$branch" ]] || ! git check-ref-format --branch "$branch" >/dev/null 2>&1; then
  echo "A valid branch is required; detached HEAD is not supported." >&2
  exit 2
fi

if [[ -z "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]]; then
  echo "GITHUB_PERSONAL_ACCESS_TOKEN is not set. Add it in Replit Secrets; do not put it in the command or repository URL." >&2
  exit 2
fi

askpass="$(mktemp "${TMPDIR:-/tmp}/github-askpass.XXXXXX")"
trap 'rm -f "$askpass"' EXIT
chmod 700 "$askpass"
cat >"$askpass" <<'ASKPASS'
#!/bin/sh
case "$1" in
  *Username*|*username*) printf '%s\n' 'x-access-token' ;;
  *Password*|*password*) printf '%s\n' "$GITHUB_PERSONAL_ACCESS_TOKEN" ;;
  *) exit 1 ;;
esac
ASKPASS

echo "Pushing the current commit to $repo_url (branch: $branch)."
GIT_ASKPASS="$askpass" \
SSH_ASKPASS="$askpass" \
GIT_TERMINAL_PROMPT=0 \
git -c credential.helper= push "$repo_url" "HEAD:refs/heads/$branch"
