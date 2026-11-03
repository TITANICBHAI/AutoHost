#!/usr/bin/env bash
# Workaround for AGP 8.9.x + Compose: mergeReleaseComposeMapping is SKIPPED,
# so mapping.txt never lands in app/build/outputs/mapping/release/ and
# bundleRelease fails. Copy mapping from intermediates, then run bundleRelease
# without clean (to reuse minifyReleaseWithR8 output from a previous failed run).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

SRC=app/build/intermediates/mapping/release/minifyReleaseWithR8/mapping.txt
DST_DIR=app/build/outputs/mapping/release
DST="$DST_DIR/mapping.txt"

if [[ ! -f "$SRC" ]]; then
  echo "release-bundle-only: missing $SRC — run ./scripts/release-build.sh first" >&2
  exit 1
fi

mkdir -p "$DST_DIR"
cp "$SRC" "$DST"
echo "release-bundle-only: copied mapping ($(wc -l < "$DST") lines)"

./gradlew bundleRelease 2>&1 | tee -a /tmp/closepaw-release-build.log
