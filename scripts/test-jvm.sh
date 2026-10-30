#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
# shellcheck source=scripts/android-build-env.sh
source "$ROOT_DIR/scripts/android-build-env.sh"
trap autohost_cleanup_android_build_env EXIT
autohost_prepare_android_build_env

run_gradle() {
  printf '\n==> gradle'
  printf ' %q' "$@"
  printf ' --console=plain --stacktrace\n'
  "$AUTOHOST_GRADLE_BIN" "$@" --console=plain --stacktrace
}

echo "== AutoHost JVM verification =="
echo "Started: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"

# Keep the initial build in the module order documented in HANDOFF.md §5 so
# the first failing module is clear in the Replit console.
run_gradle :runtime:js:test
run_gradle :core:engine:test
run_gradle :core:model:test :core:engine:test
run_gradle :capabilities:a11y:test
run_gradle :capabilities:notifications:test :capabilities:clipboard:test :capabilities:intents:test
run_gradle :capabilities:files:test :capabilities:audio:test :capabilities:net:test :capabilities:rawbridge:test
run_gradle :capabilities:screen:test
run_gradle :capabilities:shell:test
run_gradle :capabilities:browser:test :capabilities:vision:test
run_gradle :capabilities:data:test :capabilities:net:test
run_gradle :core:broker:test :core:devapi:test
run_gradle test

echo "Finished: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
