#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'release-build: %s\n' "$*" >&2
  exit 1
}

require_env() {
  local name="$1"
  if [[ -z "${!name-}" ]]; then
    fail "$name is required and must not be empty. Export it before running this script."
  fi
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return
  fi
  fail "sha256sum or shasum is required to record the AAB digest"
}

required_env=(
  KEYSTORE_PATH
  KEYSTORE_PASSWORD
  KEY_ALIAS
  KEY_PASSWORD
)

for name in "${required_env[@]}"; do
  require_env "$name"
done

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  fail "KEYSTORE_PATH does not point to an existing keystore file: $KEYSTORE_PATH"
fi

if [[ ! -r "$KEYSTORE_PATH" ]]; then
  fail "KEYSTORE_PATH is not readable: $KEYSTORE_PATH"
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

printf 'release-build: building Play upload AAB with ./gradlew clean bundleRelease\n'
./gradlew clean bundleRelease

aab_path="$repo_root/app/build/outputs/bundle/release/app-release.aab"
if [[ ! -f "$aab_path" ]]; then
  mapfile -t aab_candidates < <(find "$repo_root/app/build/outputs/bundle/release" -type f -name '*.aab' | sort)
  if [[ "${#aab_candidates[@]}" -ne 1 ]]; then
    fail "expected one release AAB, found ${#aab_candidates[@]}"
  fi
  aab_path="${aab_candidates[0]}"
fi

aab_sha256="$(sha256_file "$aab_path")"

cat <<EOF
release-build: AAB ready
  path: $aab_path
  sha256: $aab_sha256
EOF
