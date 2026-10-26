"""calendar.*: query and insert calendar events (P10-06). Windows are capped at 31 days."""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_WINDOW_MS = 31 * 24 * 60 * 60 * 1000


def query(from_ms: int | None = None, to_ms: int | None = None, limit: int | None = None) -> dict[str, Any]:
    """Events overlapping [from_ms, to_ms] (defaults: now → +7 days). Returns {events:[…], count}."""
    args: dict[str, Any] = {}
    if from_ms is not None:
        args["from_ms"] = int(from_ms)
    if to_ms is not None:
        args["to_ms"] = int(to_ms)
    if limit is not None:
        args["limit"] = int(limit)
    return current().call("calendar", "query", args)


def insert(title: str, start_ms: int, end_ms: int, *, location: str | None = None,
           description: str | None = None) -> dict[str, Any]:
    """Insert an event into the primary writable calendar (needs WRITE_CALENDAR). Returns {id}."""
    if not title or not title.strip():
        raise InvalidArgs("title is required")
    if end_ms <= start_ms:
        raise InvalidArgs("end_ms must be after start_ms")
    if start_ms - end_ms > MAX_WINDOW_MS or end_ms - start_ms > MAX_WINDOW_MS:
        raise InvalidArgs("window must be within 31 days")
    args: dict[str, Any] = {"title": title, "start_ms": int(start_ms), "end_ms": int(end_ms)}
    if location is not None:
        args["location"] = location
    if description is not None:
        args["description"] = description
    return current().call("calendar", "insert", args)
