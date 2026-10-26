"""http.*: brokered HTTP (capability net.http). Domain allowlists are enforced by the broker."""
from __future__ import annotations

import base64
import json
from typing import Any

from ._core import current


class Response:
    def __init__(self, status: int, headers: dict, text: str | None, body_b64: str | None, truncated: bool) -> None:
        self.status = int(status)
        self.headers = dict(headers or {})
        self.text = text
        self.content = base64.b64decode(body_b64) if body_b64 else (text.encode("utf-8") if text is not None else None)
        self.truncated = bool(truncated)

    def json(self) -> Any:
        if self.text is None:
            raise ValueError("response has no text body")
        return json.loads(self.text)


def request(method: str, url: str, headers: dict[str, str] | None = None, body: str | bytes | None = None,
            timeout: float = 30.0, max_bytes: int | None = None) -> Response:
    if isinstance(body, bytes):
        body = base64.b64encode(body).decode("ascii")
    args = {"method": method.upper(), "url": url, "headers": headers, "body": body,
            "timeout_ms": int(timeout * 1000), "max_bytes": max_bytes}
    res = current().call("net.http", "request", args)
    return Response(res.get("status", 0), res.get("headers", {}), res.get("text"), res.get("body_b64"),
                    res.get("truncated", False))


def get(url: str, headers: dict[str, str] | None = None, timeout: float = 30.0) -> Response:
    return request("GET", url, headers=headers, timeout=timeout)


def post(url: str, body: str | bytes | None = None, headers: dict[str, str] | None = None,
         timeout: float = 30.0) -> Response:
    return request("POST", url, headers=headers, body=body, timeout=timeout)
