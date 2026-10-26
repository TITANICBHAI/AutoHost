#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

run() {
  printf '\n==> '
  printf '%q ' "$@"
  printf '\n'
  "$@"
}

echo "== AutoHost Python verification =="
echo "Started: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
python3 --version
if ! python3 -c 'import yaml' >/dev/null 2>&1; then
  echo "ERROR: PyYAML is required by the simulator's YAML-flow tests." >&2
  echo "Install it with: uv pip install --python \"$(command -v python3)\" -r sdk-python/requirements-test.txt" >&2
  exit 1
fi

echo
echo "== Python SDK and simulator suite =="
(cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py")

echo
echo "== Schema and tooling suite =="
run python3 -m unittest discover -s tests -p "test_*.py"
run python3 tools/progress.py --check
run python3 tools/gen_artifacts.py --check
run python3 tools/sync_sdk.py --check

echo
echo "== Golden-vector drift checks =="
run python3 tools/gen_flow_vectors.py
run git diff --exit-code -- testing/golden/flow_vectors.json
run python3 tools/gen_schedule_vectors.py
run git diff --exit-code -- testing/golden/schedule_vectors.json

echo
echo "== Licence and MCP checks =="
run python3 tools/licence_check.py
run python3 tools/mcp_eval.py

echo "Finished: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
