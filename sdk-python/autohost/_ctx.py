"""ctx: the per-run context. Reads and writes go through the broker (or the simulator in tests)."""
from __future__ import annotations

import datetime as _dt
from types import MappingProxyType
from typing import Any, Callable

from ._core import ISO_FMT, UTC, current
from .errors import AutoHostError, Cancelled, InvalidArgs

_MISSING = object()


class _Obj:
    def __init__(self, **fields: Any) -> None:
        self.__dict__.update(fields)

    def __repr__(self) -> str:
        return f"{type(self).__name__}({self.__dict__!r})"


class Result:
    def __init__(self, code: str, data: dict | None = None) -> None:
        self.code = str(code)
        self.data = dict(data or {})

    def __repr__(self) -> str:
        return f"Result(code={self.code!r}, data={self.data!r})"


class KV:
    """Persistent key-value store. scope is "profile" or "task"; non-secret values only."""

    def __init__(self, scope: str) -> None:
        self._scope = scope

    def _lookup(self, key: str) -> Any:
        res = current().call("store", "get", {"scope": self._scope, "key": key})
        return res["value"] if "value" in res else _MISSING

    def get(self, key: str, default: Any = None) -> Any:
        value = self._lookup(key)
        return default if value is _MISSING else value

    def __getitem__(self, key: str) -> Any:
        value = self._lookup(key)
        if value is _MISSING:
            raise KeyError(key)
        return value

    def __setitem__(self, key: str, value: Any) -> None:
        current().call("store", "set", {"scope": self._scope, "key": key, "value": value})

    def __contains__(self, key: str) -> bool:
        return self._lookup(key) is not _MISSING

    def delete(self, key: str) -> None:
        current().call("store", "delete", {"scope": self._scope, "key": key})

    def keys(self, prefix: str = "") -> list[str]:
        return list(current().call("store", "list", {"scope": self._scope, "prefix": prefix}).get("keys", []))


class Evidence:
    """Evidence written to the run's evidence folder. Every call is also recorded as an event."""

    def screenshot(self, tag: str) -> str | None:
        rt = current()
        try:
            res = rt.call("device.screenshot", "capture", {"tag": tag})
        except Cancelled:
            raise
        except AutoHostError as exc:
            rt.emit({"type": "log", "level": "warn", "message": "screenshot unavailable",
                     "data": {"tag": tag, "code": exc.code}})
            return None
        path = res.get("path")
        rt.emit({"type": "evidence", "data": {"kind": "screenshot", "tag": tag, "path": path}})
        return path

    def ui_dump(self, tag: str) -> str:
        rt = current()
        text = rt.call("device.ui", "dump", {"compact": True}).get("text", "")
        rt.emit({"type": "evidence", "data": {"kind": "ui_dump", "tag": tag, "text": text}})
        return text

    def note(self, tag: str, text: str) -> None:
        current().emit({"type": "evidence", "data": {"kind": "note", "tag": tag, "text": str(text)}})


evidence = Evidence()


class Ctx:
    @property
    def config(self):
        return MappingProxyType(current().config)

    @property
    def profile(self):
        return MappingProxyType(current().profile)

    @property
    def run(self):
        rt = current()
        return _Obj(id=rt.run["id"], attempt=rt.run["attempt"], trigger=rt.run["trigger"],
                    started_at=rt.run["started_at"])

    @property
    def trigger(self):
        rt = current()
        return _Obj(type=rt.trigger["type"], payload=MappingProxyType(rt.trigger["payload"]))

    @property
    def state(self) -> dict:
        return current().state

    @property
    def profile_store(self) -> KV:
        return KV("profile")

    @property
    def task_store(self) -> KV:
        return KV("task")

    @property
    def evidence(self) -> Evidence:
        return evidence

    def secret(self, name: str) -> str:
        return current().secret(name)

    def result(self, code: str, data: dict | None = None) -> Result:
        res = Result(code, data)
        current().result = res
        return res

    def progress(self, fraction: float, message: str = "") -> None:
        current().emit({"type": "progress", "message": str(message), "data": {"fraction": float(fraction)}})

    def now(self) -> str:
        return current().now_iso()

    def minutes_since(self, iso: str) -> float:
        then = _dt.datetime.strptime(iso, ISO_FMT).replace(tzinfo=UTC)
        return (current().clock.now() - then).total_seconds() / 60.0

    def sleep(self, seconds: float) -> None:
        current().sleep(seconds)

    def next_run(self, after_minutes: int | None = None, at: str | None = None) -> None:
        if (after_minutes is None) == (at is None):
            raise InvalidArgs("give exactly one of after_minutes or at")
        rt = current()
        request = {"after_minutes": after_minutes, "at": at}
        rt.next_run_request = request
        rt.emit({"type": "next_run", "data": request})

    def save(self) -> None:
        rt = current()
        rt.call("state", "set", {"state": dict(rt.state)})

    def once(self, name: str, fn: Callable[[], Any]) -> Any:
        """Run fn only if this name has not completed in the run's checkpoint; store and return its value."""
        rt = current()
        key = f"once:{name}"
        if key in rt.state:
            return rt.state[key]
        value = fn()
        rt.state[key] = value
        self.save()
        return value


ctx = Ctx()
