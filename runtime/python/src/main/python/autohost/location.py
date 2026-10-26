"""location.*: one-shot fixes (P10-07). last_known is honest about staleness; background runs
need 'Allow all the time' — the provider reports BACKGROUND_LOCATION instead of degrading silently."""
from __future__ import annotations

from typing import Any

from ._core import current as _current
from .errors import InvalidArgs

MAX_TIMEOUT_MS = 60_000
DEFAULT_TIMEOUT_MS = 15_000


def last_known() -> dict[str, Any]:
    """The most recent cached fix (stale:true). May raise CAPABILITY_UNAVAILABLE(NO_LOCATION_FIX)."""
    return _current().call("location", "last_known", {})


def current(timeout_ms: int | None = None) -> dict[str, Any]:
    """One fresh fix within the timeout (falls back to last-known with stale:true)."""
    args: dict[str, Any] = {}
    if timeout_ms is not None:
        if not 1_000 <= int(timeout_ms) <= MAX_TIMEOUT_MS:
            raise InvalidArgs(f"timeout_ms must be 1_000..{MAX_TIMEOUT_MS}")
        args["timeout_ms"] = int(timeout_ms)
    return _current().call("location", "current", args)
