"""events.*: a read-only window into the host's EventHub ring buffer (capability device.events).

Live *delivery* is the trigger rules' job (a task declares an event trigger and the host starts it);
`recent()` lets a running task inspect what just happened on the device (plan §11.8).
"""
from __future__ import annotations

from ._core import current


def recent(kinds: list[str] | None = None, limit: int = 50) -> list[dict]:
    args: dict = {"limit": int(limit)}
    if kinds:
        args["kinds"] = [str(k) for k in kinds]
    return list(current().call("device.events", "recent", args).get("events", []))
