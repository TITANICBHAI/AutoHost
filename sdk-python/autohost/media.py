"""media.*: device-wide MediaStore rows (P10-06) — distinct from files.shared's tree-scoped media ops."""
from __future__ import annotations

import base64
from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_INSERT_BYTES = 10 * 1024 * 1024
MAX_READ_BYTES = 10 * 1024 * 1024
_KINDS = ("image", "video", "audio")


def _kind(kind: str | None) -> str | None:
    if kind is None:
        return None
    if kind not in _KINDS:
        raise InvalidArgs("kind must be image, video or audio")
    return kind


def list(kind: str | None = None, limit: int | None = None) -> dict[str, Any]:
    """List media rows (newest first). Returns {media:[{id,kind,display_name,size_bytes,added_ms}], count}."""
    args: dict[str, Any] = {}
    k = _kind(kind)
    if k is not None:
        args["kind"] = k
    if limit is not None:
        args["limit"] = int(limit)
    return current().call("media", "list", args)


def read(id: int) -> bytes:
    """Read one media item (10 MB cap). Returns the raw bytes."""
    res = current().call("media", "read", {"id": int(id)})
    return base64.b64decode(res["data_b64"])


def insert(kind: str, display_name: str, data: bytes) -> dict[str, Any]:
    """Insert bytes as an image/video/audio item (10 MB cap). Returns {id}."""
    _kind(kind)
    if not display_name or "/" in display_name:
        raise InvalidArgs("display_name is required and must not contain '/'")
    if len(data) > MAX_INSERT_BYTES:
        raise InvalidArgs(f"media exceeds {MAX_INSERT_BYTES} bytes")
    return current().call("media", "insert", {
        "kind": kind, "display_name": display_name,
        "data_b64": base64.b64encode(data).decode(),
    })


def delete(id: int) -> dict[str, Any]:
    """Delete a media row."""
    return current().call("media", "delete", {"id": int(id)})
