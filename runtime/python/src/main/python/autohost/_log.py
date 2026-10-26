"""log: structured log events. Secret values are redacted by the runtime before they leave."""
from __future__ import annotations

from typing import Any

from ._core import current


class _Log:
    def _emit(self, level: str, msg: Any, data: dict) -> None:
        current().emit({"type": "log", "level": level, "message": str(msg), "data": dict(data)})

    def debug(self, msg: Any, **data: Any) -> None:
        self._emit("debug", msg, data)

    def info(self, msg: Any, **data: Any) -> None:
        self._emit("info", msg, data)

    def warn(self, msg: Any, **data: Any) -> None:
        self._emit("warn", msg, data)

    def error(self, msg: Any, **data: Any) -> None:
        self._emit("error", msg, data)


log = _Log()
