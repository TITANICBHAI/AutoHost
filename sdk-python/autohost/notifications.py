"""notifications.*: read, react to, press and dismiss other apps' notifications (device.notifications).

Needs notification access (Setup shows the check; the fix opens the system settings). When the
task's grant restricts `device.notifications` to a package set, only those packages are visible.
Payloads carry title/text only — never extras or content intents (plan §11.8).
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs


def _str(args: dict, key: str, value: Any) -> dict:
    if value is not None:
        if not isinstance(value, str):
            raise InvalidArgs(f"{key} must be a string")
        args[key] = value
    return args


def list(package: str | None = None, include_ongoing: bool = True) -> list[dict]:
    args = _str({}, "package", package)
    args["include_ongoing"] = bool(include_ongoing)
    # NOTE: `list(...)` inside this module IS this function (shadowed builtin) — never call it here.
    rows = current().call("device.notifications", "list", args).get("notifications", [])
    return [dict(row) for row in rows]


def read(id: str) -> dict:
    if not isinstance(id, str) or not id:
        raise InvalidArgs("id must be a non-empty notification id")
    return current().call("device.notifications", "read", {"id": id})


def press(id: str, action: int | str | None = None) -> dict:
    if not isinstance(id, str) or not id:
        raise InvalidArgs("id must be a non-empty notification id")
    if action is not None and not isinstance(action, (int, str)):
        raise InvalidArgs("action must be an index (int) or a label (str)")
    args: dict = {"id": id}
    if action is not None:
        args["action"] = action
    return current().call("device.notifications", "press", args)


def dismiss(id: str) -> dict:
    if not isinstance(id, str) or not id:
        raise InvalidArgs("id must be a non-empty notification id")
    return current().call("device.notifications", "dismiss", {"id": id})


def dismiss_all() -> dict:
    return current().call("device.notifications", "dismiss_all", {})


def wait_for(
    package: str | None = None,
    title_regex: str | None = None,
    text_regex: str | None = None,
    timeout_ms: int = 10_000,
) -> dict:
    args = _str({}, "package", package)
    _str(args, "title_regex", title_regex)
    _str(args, "text_regex", text_regex)
    if not isinstance(timeout_ms, int) or timeout_ms < 0:
        raise InvalidArgs("timeout_ms must be a non-negative int")
    args["timeout_ms"] = timeout_ms
    return current().call("device.notifications", "wait_for", args)
