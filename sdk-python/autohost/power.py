"""power.*: device.power — battery state, task-scoped wake lock, keep-awake (P8-09)."""
from __future__ import annotations

from typing import Any

from ._core import current


def battery() -> dict[str, Any]:
    """Battery level, charging state and plug type."""
    return current().call("device.power", "battery", {})


def wake_lock(hold: bool = True, reason: str | None = None) -> dict[str, Any]:
    """Acquire (or release) the task-scoped partial wake lock; auto-released at run end."""
    return current().call("device.power", "wake_lock", {"hold": bool(hold), "reason": reason})


def keep_awake(on: bool = True) -> dict[str, Any]:
    """Keep the screen on for this run (best effort; pre-flight ScreenGuard still applies)."""
    return current().call("device.power", "keep_awake", {"on": bool(on)})
