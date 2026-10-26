#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
# shellcheck source=scripts/python-test-env.sh
source "$ROOT_DIR/scripts/python-test-env.sh"
autohost_prepare_python_test_env
PYTHON_BIN="$AUTOHOST_TEST_PYTHON"

run() {
  printf '\n==> '
  printf '%q ' "$@"
  printf '\n'
  "$@"
}

echo "== AutoHost Python verification =="
echo "Started: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
"$PYTHON_BIN" --version

echo
echo "== Python SDK and simulator suite =="
(cd sdk-python && "$PYTHON_BIN" -m unittest discover -s tests -t tests -p "test_*.py")

echo
echo "== Schema and tooling suite =="
run "$PYTHON_BIN" -m unittest discover -s tests -p "test_*.py"
run "$PYTHON_BIN" tools/progress.py --check
run "$PYTHON_BIN" tools/gen_artifacts.py --check
run "$PYTHON_BIN" tools/sync_sdk.py --check

echo
echo "== Golden-vector drift checks =="
run "$PYTHON_BIN" tools/gen_flow_vectors.py
run git diff --exit-code -- testing/golden/flow_vectors.json
run "$PYTHON_BIN" tools/gen_schedule_vectors.py
run git diff --exit-code -- testing/golden/schedule_vectors.json

echo
echo "== Licence and MCP checks =="
run "$PYTHON_BIN" tools/licence_check.py
run "$PYTHON_BIN" tools/mcp_eval.py

echo "Finished: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
