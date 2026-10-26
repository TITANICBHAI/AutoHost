"""net.*: outbound WebSocket client (net.ws, P8-08) and webhook status (net.server).

Domain allowlists are enforced by the broker like net.http. Handles are run-scoped and closed
when the run ends.
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current


class ws:
    """Minimal WebSocket client surface (RFC 6455, text frames)."""

    @staticmethod
    def connect(url: str, subprotocols: list[str] | None = None, timeout_ms: int = 10_000) -> str:
        if not url.startswith(("ws://", "wss://")):
            raise InvalidArgs("url must start with ws:// or wss://")
        res = current().call("net.ws", "connect", {
            "url": url, "subprotocols": subprotocols, "timeout_ms": int(timeout_ms),
        })
        return str(res["handle"])

    @staticmethod
    def send(handle: str, text: str) -> bool:
        if not handle:
            raise InvalidArgs("handle is required")
        return bool(current().call("net.ws", "send", {"handle": handle, "text": text}).get("sent"))

    @staticmethod
    def recv(handle: str, timeout_ms: int = 60_000) -> dict[str, Any]:
        """Wait for one frame: {"kind": "text", "text": …} or {"kind": "closed"}."""
        if not handle:
            raise InvalidArgs("handle is required")
        if int(timeout_ms) < 0:
            raise InvalidArgs("timeout_ms must be >= 0")
        return current().call("net.ws", "recv", {"handle": handle, "timeout_ms": int(timeout_ms)})

    @staticmethod
    def close(handle: str) -> bool:
        return bool(current().call("net.ws", "close", {"handle": handle}).get("closed"))


def webhook_status() -> dict[str, Any]:
    """Whether the host's webhook endpoint is running (port, LAN binding, hook count)."""
    return current().call("net.server", "status", {})


def webhook_names() -> list[str]:
    """Names of installed `external` trigger rules — what a webhook POST can fire."""
    return list(current().call("net.server", "hook_names", {}).get("names", []))
