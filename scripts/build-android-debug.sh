#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
# shellcheck source=scripts/android-build-env.sh
source "$ROOT_DIR/scripts/android-build-env.sh"
autohost_prepare_android_build_env

echo "== AutoHost core debug APK and fixture build =="
echo "Started: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
gradle :app:assembleCoreDebug :testing:fixture-app:assembleDebug \
  --console=plain --stacktrace

apk_path="app/build/outputs/apk/core/debug/app-core-debug.apk"
if [[ ! -f "$apk_path" ]]; then
  echo "ERROR: Expected APK was not produced at $apk_path" >&2
  exit 1
fi

echo "APK: $apk_path"
sha256sum "$apk_path"
echo "Finished: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
