#!/usr/bin/env bash

PYTHON_TEST_ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$PYTHON_TEST_ENV_DIR/tool-cache.sh"

autohost_prepare_python_test_env() {
  local python_base python_version cache_root env_dir env_python

  if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: Python 3 is required. Add the Python module to the Replit environment." >&2
    return 1
  fi

  python_base="$(command -v python3)"
  python_version="$("$python_base" -c 'import sys; print(".".join(map(str, sys.version_info[:3])))')"
  if "$python_base" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)'; then
    :
  else
    echo "ERROR: AutoHost tests require Python 3.10 or newer; found $python_version." >&2
    return 1
  fi

  cache_root="$(autohost_tool_cache_dir "$ROOT_DIR")"
  env_dir="$cache_root/python-test-$python_version"
  env_python="$env_dir/bin/python"

  if [[ ! -x "$env_python" ]] \
    || ! "$env_python" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' \
    || [[ "$("$env_python" -c 'import sys; print(".".join(map(str, sys.version_info[:3])))' 2>/dev/null || true)" != "$python_version" ]]; then
    echo "Creating isolated Python test environment at $env_dir"
    rm -rf -- "$env_dir"
    if command -v uv >/dev/null 2>&1; then
      UV_CACHE_DIR="$cache_root/uv-cache" uv venv --python "$python_base" "$env_dir"
    else
      "$python_base" -m venv "$env_dir"
    fi
  fi

  mkdir -p "$cache_root/pip-cache" "$cache_root/uv-cache"
  if command -v uv >/dev/null 2>&1; then
    echo "Installing Python test requirements from sdk-python/requirements-test.txt..."
    UV_CACHE_DIR="$cache_root/uv-cache" uv pip install --python "$env_python" \
      -r "$ROOT_DIR/sdk-python/requirements-test.txt"
  else
    echo "Installing Python test requirements from sdk-python/requirements-test.txt with pip..."
    PIP_CACHE_DIR="$cache_root/pip-cache" "$env_python" -m pip install \
      --disable-pip-version-check \
      -r "$ROOT_DIR/sdk-python/requirements-test.txt"
  fi

  AUTOHOST_TEST_PYTHON="$env_python"
  export AUTOHOST_TEST_PYTHON
  echo "Using Python $python_version from $AUTOHOST_TEST_PYTHON"
}
