#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPOSITORY_URL="https://github.com:443/TITANICBHAI/AutoHost.git"

fail() {
  printf 'GitHub push failed: %s\n' "$1" >&2
  exit 1
}

command -v git >/dev/null 2>&1 || fail "git is not installed."
[[ -n "${GITHUB_PERSONAL_ACCESS_TOKEN:-}" ]] || fail \
  "Add GITHUB_PERSONAL_ACCESS_TOKEN in Replit Secrets, then run this workflow again."

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || fail \
  "Run this script from inside the AutoHost Git repository."
cd "$repo_root"

branch="$(git branch --show-current)"
[[ -n "$branch" ]] || fail "The repository is in detached HEAD state; switch to a branch first."

# The :443 URL avoids Replit's GitHub URL rewrite while preserving the normal
# Git configuration used for ignore rules and project settings.
export GIT_TERMINAL_PROMPT=0
export GIT_ASKPASS_REQUIRE=force
unset GIT_TRACE GIT_TRACE_CURL GIT_CURL_VERBOSE

# Replit's local tool state is excluded by the project's .gitignore.
git add --all

blocked_file=""
while IFS= read -r -d '' path; do
  case "$path" in
    .env|.env.*)
      case "$path" in
        .env.example|.env.sample|.env.template) ;;
        *) blocked_file="$path" ;;
      esac
      ;;
    *.pem|*.p12|*.pfx|id_rsa|id_ed25519|credentials.json|secrets.json)
      blocked_file="$path"
      ;;
  esac
  [[ -z "$blocked_file" ]] || break
done < <(git diff --cached --name-only -z)

[[ -z "$blocked_file" ]] || fail \
  "A likely secret or private-key file is staged ($blocked_file). Remove it from the repository before pushing."

if git diff --cached --no-ext-diff --unified=0 -- . ':(exclude)scripts/push-to-github.sh' \
  | grep -Eiq -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}'; then
  fail "A likely credential was found in staged changes. Remove it before pushing."
fi

if ! git diff --cached --quiet; then
  timestamp="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  git -c user.name="Replit GitHub Sync" \
      -c user.email="replit-sync@users.noreply.github.com" \
      commit -m "Sync from Replit: $timestamp"
else
  printf 'No file changes to commit; checking for commits to push.\n'
fi

askpass="$(mktemp)"
trap 'rm -f "$askpass"' EXIT
cat >"$askpass" <<'ASKPASS'
#!/bin/sh
case "${1:-}" in
  *Username*|*username*) printf '%s\n' "x-access-token" ;;
  *Password*|*password*) printf '%s\n' "$GITHUB_PERSONAL_ACCESS_TOKEN" ;;
  *) exit 1 ;;
esac
ASKPASS
chmod 700 "$askpass"
export GIT_ASKPASS="$askpass"

printf 'Pushing branch %s to TITANICBHAI/AutoHost…\n' "$branch"
git -c credential.helper= push "$REPOSITORY_URL" "HEAD:refs/heads/$branch"
printf 'Push completed successfully.\n'
