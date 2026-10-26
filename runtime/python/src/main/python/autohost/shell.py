"""shell.*: device.shell — allowlisted commands through the paired shell tier (P9-04).

High-risk by design: needs BOTH the per-task device.shell grant (with a non-empty
`commands` pattern list) AND the global `shell` switch (Settings → Developer &
security, off by default). Shell metacharacters are always refused by the broker.
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current

MAX_TIMEOUT_MS = 120_000
DEFAULT_TIMEOUT_MS = 15_000


def run(command: str, timeout_ms: int | None = None) -> dict[str, Any]:
    """Run one allowlisted shell command on the device's shell tier.

    Returns {stdout, stderr, exit_code (None when the transport cannot know one),
    mode ("shell_v2" | "shell" | "root" | "shizuku"), transport, duration_ms}.
    """
    if not isinstance(command, str) or not command.strip():
        raise InvalidArgs("command is required")
    if len(command) > 4000:
        raise InvalidArgs("command exceeds 4000 characters")
    if timeout_ms is not None and not 1 <= int(timeout_ms) <= MAX_TIMEOUT_MS:
        raise InvalidArgs(f"timeout_ms must be 1..{MAX_TIMEOUT_MS}")
    args: dict[str, Any] = {"command": command}
    if timeout_ms is not None:
        args["timeout_ms"] = int(timeout_ms)
    return current().call("device.shell", "run", args)


def transports() -> dict[str, Any]:
    """Shell transport status: {transports: [{id, describe, state, reason?}]}."""
    return current().call("device.shell", "transports", {})
