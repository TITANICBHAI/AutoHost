"""logcat.*: device.logcat — bounded logcat snapshots through the shell tier (P9-10).

Dev-grade and off by default: needs BOTH the per-task device.logcat grant AND the global `logcat`
switch.
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current

MAX_LINES = 5000
DEFAULT_MAX_LINES = 500


def snapshot(
    *,
    tag: str | None = None,
    level: str | None = None,
    contains: str | None = None,
    package: str | None = None,
    max_lines: int | None = None,
    since_ms: int | None = None,
) -> dict[str, Any]:
    """Bounded `logcat -d -v epoch` snapshot with parse-side filters.

    Returns {lines: [{ts?, level, tag, pid?, message}], count, parsed, package?}. A package filter
    resolves the app's pid first; when the app is not running the snapshot is empty (honest).
    """
    args: dict[str, Any] = {}
    if tag is not None:
        if not isinstance(tag, str) or not tag.strip():
            raise InvalidArgs("tag must be a non-empty string")
        args["tag"] = tag
    if level is not None:
        if level not in ("V", "D", "I", "W", "E", "F"):
            raise InvalidArgs("level must be one of V|D|I|W|E|F")
        args["level"] = level
    if contains is not None:
        args["contains"] = contains
    if package is not None:
        args["package"] = package
    if max_lines is not None:
        if not 1 <= int(max_lines) <= MAX_LINES:
            raise InvalidArgs(f"max_lines must be 1..{MAX_LINES}")
        args["max_lines"] = int(max_lines)
    if since_ms is not None:
        if since_ms < 0:
            raise InvalidArgs("since_ms must be >= 0")
        args["since_ms"] = int(since_ms)
    return current().call("device.logcat", "snapshot", args)


def clear() -> dict[str, Any]:
    """`logcat -c` — clears the main buffer. Returns {cleared: bool}."""
    return current().call("device.logcat", "clear", {})
