"""files.*: task-private storage (capability files.private). Paths are relative to the task's private folder."""
from __future__ import annotations

from typing import Any

from ._core import current


class _Private:
    def read(self, path: str, binary: bool = False) -> str | bytes:
        res = current().call("files.private", "read", {"path": path, "binary": bool(binary)})
        return res.get("content", "")

    def write(self, path: str, content: str | bytes) -> None:
        current().call("files.private", "write", {"path": path, "content": content})

    def list(self, path: str = "") -> list[str]:
        return list(current().call("files.private", "list", {"path": path}).get("entries", []))

    def delete(self, path: str) -> None:
        current().call("files.private", "delete", {"path": path})


private = _Private()


class _Shared:
    """files.shared: user-picked SAF trees + MediaStore (capability files.shared).

    Trees must be granted (Permission Review → pick folder); MediaStore ops additionally need
    "media": true in the grant scope plus the storage permission (lazily requested — P8-14).
    Reads over 5 MB truncate honestly (body_b64 + truncated=True).
    """

    def _b64(self, data: str | bytes) -> str:
        import base64
        raw = data.encode("utf-8") if isinstance(data, str) else data
        return base64.b64encode(raw).decode("ascii")

    def _unb64(self, body_b64: str) -> bytes:
        import base64
        return base64.b64decode(body_b64)

    def list(self, tree: str, path: str = "") -> list[dict]:
        res = current().call("files.shared", "list", {"tree": tree, "path": path})
        return list(res.get("entries", []))

    def read(self, tree: str, path: str, binary: bool = False) -> str | bytes:
        res = current().call("files.shared", "read", {"tree": tree, "path": path})
        raw = self._unb64(res.get("body_b64", ""))
        return raw if binary else raw.decode("utf-8", errors="replace")

    def write(self, tree: str, path: str, content: str | bytes) -> int:
        res = current().call("files.shared", "write", {
            "tree": tree, "path": path, "body_b64": self._b64(content),
        })
        return int(res.get("written", 0))

    def delete(self, tree: str, path: str) -> bool:
        return bool(current().call("files.shared", "delete", {"tree": tree, "path": path}).get("deleted"))

    def media_list(self, media_type: str = "files", limit: int = 100) -> list[dict]:
        return list(current().call("files.shared", "media_list", {
            "media_type": media_type, "limit": limit,
        }).get("rows", []))

    def media_read(self, id: str, binary: bool = False) -> str | bytes:
        res = current().call("files.shared", "media_read", {"id": id})
        raw = self._unb64(res.get("body_b64", ""))
        return raw if binary else raw.decode("utf-8", errors="replace")

    def media_write(self, name: str, content: str | bytes, media_type: str = "files") -> str:
        return str(current().call("files.shared", "media_write", {
            "name": name, "body_b64": self._b64(content), "media_type": media_type,
        }).get("id", ""))

    def media_delete(self, id: str) -> bool:
        return bool(current().call("files.shared", "media_delete", {"id": id}).get("deleted"))


shared = _Shared()
