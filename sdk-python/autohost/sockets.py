"""net.socket / net.reverse — the P10 W3 riders.

socket: raw TCP client + one-shot UDP with the net.http-style domain allowlist; handles are
run-scoped and closed when the run ends.

reverse: the device dials OUT to a control/reporting host (plan §6.5) — nothing listens on the
device. Off by default: needs the net.reverse grant AND the `net_reverse` switch. The token
rides the FIRST frame (never the URL — URLs are logged).
"""
from __future__ import annotations

import base64
from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_PAYLOAD = 5 * 1024 * 1024
MAX_UDP = 65_527


class socket:
    """Raw TCP/UDP with domain-allowlist enforcement (like net.http)."""

    @staticmethod
    def tcp_connect(host: str, port: int, timeout_ms: int | None = None) -> str:
        if not host:
            raise InvalidArgs("host is required")
        if not 1 <= int(port) <= 65535:
            raise InvalidArgs("port must be 1..65535")
        args: dict[str, Any] = {"host": host, "port": int(port)}
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        return str(current().call("net.socket", "tcp_connect", args)["handle"])

    @staticmethod
    def tcp_send(handle: str, data: bytes) -> int:
        if not handle:
            raise InvalidArgs("handle is required")
        if len(data) > MAX_PAYLOAD:
            raise InvalidArgs(f"payload exceeds {MAX_PAYLOAD} bytes")
        res = current().call("net.socket", "tcp_send", {
            "handle": handle, "data_b64": base64.b64encode(data).decode(),
        })
        return int(res["sent"])

    @staticmethod
    def tcp_recv(handle: str, *, max_bytes: int | None = None, timeout_ms: int | None = None) -> bytes:
        """Receive up to max_bytes. Empty bytes mean the peer closed."""
        if not handle:
            raise InvalidArgs("handle is required")
        args: dict[str, Any] = {"handle": handle}
        if max_bytes is not None:
            args["max_bytes"] = int(max_bytes)
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        res = current().call("net.socket", "tcp_recv", args)
        if res.get("closed"):
            return b""
        return base64.b64decode(res["data_b64"])

    @staticmethod
    def tcp_close(handle: str) -> bool:
        if not handle:
            raise InvalidArgs("handle is required")
        return bool(current().call("net.socket", "tcp_close", {"handle": handle}).get("closed"))

    @staticmethod
    def udp_send(host: str, port: int, data: bytes) -> int:
        if not host:
            raise InvalidArgs("host is required")
        if not 1 <= int(port) <= 65535:
            raise InvalidArgs("port must be 1..65535")
        if len(data) > MAX_UDP:
            raise InvalidArgs(f"udp payload exceeds {MAX_UDP} bytes")
        res = current().call("net.socket", "udp_send", {
            "host": host, "port": int(port), "data_b64": base64.b64encode(data).decode(),
        })
        return int(res["sent"])

    @staticmethod
    def udp_recv(port: int, *, max_bytes: int | None = None, timeout_ms: int | None = None) -> dict[str, Any]:
        """Bounded recv on an ephemeral local port. Returns {data(bytes), count, from, port}."""
        if not 1 <= int(port) <= 65535:
            raise InvalidArgs("port must be 1..65535")
        args: dict[str, Any] = {"port": int(port)}
        if max_bytes is not None:
            args["max_bytes"] = int(max_bytes)
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        res = current().call("net.socket", "udp_recv", args)
        return {
            "data": base64.b64decode(res["data_b64"]),
            "count": res["count"],
            "from": res.get("from"),
            "port": res.get("port"),
        }


class reverse:
    """Device-initiated WebSocket to a PC host (control/reporting). Token first frame."""

    @staticmethod
    def open(url: str, token: str, *, timeout_ms: int | None = None) -> str:
        if not url.startswith(("ws://", "wss://")):
            raise InvalidArgs("url must start with ws:// or wss://")
        if not token:
            raise InvalidArgs("token is required")
        args: dict[str, Any] = {"url": url, "token": token}
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        return str(current().call("net.reverse", "open", args)["handle"])

    @staticmethod
    def send(handle: str, text: str) -> bool:
        if not handle:
            raise InvalidArgs("handle is required")
        return bool(current().call("net.reverse", "send", {"handle": handle, "text": text}).get("sent"))

    @staticmethod
    def recv(handle: str, timeout_ms: int | None = None) -> dict[str, Any]:
        """{"text": …} or {"closed": true}."""
        if not handle:
            raise InvalidArgs("handle is required")
        args: dict[str, Any] = {"handle": handle}
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        return current().call("net.reverse", "recv", args)

    @staticmethod
    def close(handle: str) -> bool:
        if not handle:
            raise InvalidArgs("handle is required")
        return bool(current().call("net.reverse", "close", {"handle": handle}).get("closed"))
