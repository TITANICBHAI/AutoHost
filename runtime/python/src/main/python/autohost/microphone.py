"""microphone.*: duration-capped recording (P10-07). Max 60 s, default 10 s; the recording
indicator stays on — nothing here records invisibly."""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_MS = 60_000
DEFAULT_MS = 10_000


def record(duration_ms: int | None = None) -> dict[str, Any]:
    """Record AAC audio. Returns {path, duration_ms, size_bytes}."""
    args: dict[str, Any] = {}
    if duration_ms is not None:
        if not 500 <= int(duration_ms) <= MAX_MS:
            raise InvalidArgs(f"duration_ms must be 500..{MAX_MS}")
        args["duration_ms"] = int(duration_ms)
    return current().call("microphone", "record", args)
