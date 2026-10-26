"""device.*: UI, apps, screen and IME, all through broker capabilities."""
from __future__ import annotations

from typing import Any, Literal, TypedDict

from .._core import current
from ..errors import InvalidArgs
from ..sel import Selector


class Node(TypedDict, total=False):
    ref: str
    pkg: str
    cls: str
    id: str
    text: str
    desc: str
    bounds: list
    flags: list
    index: int


class Snapshot(TypedDict, total=False):
    ts: int
    package: str | None
    activity: str | None
    keyboard: str
    nodes: list
    compact: str
    node_count: int
    empty: bool
    degraded: bool
    degraded_reason: str | None


class ClickResult(TypedDict):
    method: str
    verified: bool


class TextResult(TypedDict):
    outcome: str
    strategy: str


class LaunchResult(TypedDict):
    result: str
    strategy: str


class StopResult(TypedDict):
    result: str
    method: str


def _call(capability: str, method: str, args: dict | None = None) -> dict:
    return current().call(capability, method, args or {})


def _target(target: Any) -> dict:
    if isinstance(target, Selector):
        return {"selector": target.to_json()}
    if isinstance(target, dict) and "ref" in target:
        return {"ref": target["ref"]}
    raise InvalidArgs("target must be a Selector or a node returned by the device API")


class DeviceUi:
    def snapshot(self, compact: bool = False, packages: list[str] | None = None) -> Snapshot:
        args: dict[str, Any] = {"compact": bool(compact)}
        if packages is not None:
            args["scope"] = {"packages": list(packages)}
        return _call("device.ui", "snapshot", args)

    def find(self, selector: Selector, limit: int = 20, within: Node | None = None) -> list[Node]:
        args: dict[str, Any] = {"selector": selector.to_json(), "limit": int(limit)}
        if within is not None:
            args["within"] = within["ref"]
        return _call("device.ui", "find", args).get("nodes", [])

    def wait_for(self, selector: Selector, timeout_ms: int, visible_only: bool = True) -> Node | None:
        args = {"selector": selector.to_json(), "timeout_ms": int(timeout_ms), "visible_only": bool(visible_only)}
        return _call("device.ui", "wait_for", args).get("node")

    def wait_for_settle(self, timeout_ms: int = 3000, stable_ms: int = 300) -> bool:
        args = {"timeout_ms": int(timeout_ms), "stable_ms": int(stable_ms)}
        return bool(_call("device.ui", "wait_for_settle", args).get("settled", False))

    def is_window_updating(self) -> bool:
        return bool(_call("device.ui", "is_window_updating").get("updating", False))

    def dump(self, compact: bool = True) -> str:
        return _call("device.ui", "dump", {"compact": bool(compact)}).get("text", "")

    def click(self, target: Node | Selector, strategy: str = "auto", expect: Selector | None = None,
              retry_if_no_change: bool = False, timeout_ms: int | None = None) -> ClickResult:
        args: dict[str, Any] = {"target": _target(target), "strategy": strategy,
                                "retry_if_no_change": bool(retry_if_no_change)}
        if expect is not None:
            args["expect"] = expect.to_json()
        if timeout_ms is not None:
            args["timeout_ms"] = int(timeout_ms)
        return _call("device.ui", "click", args)

    def long_click(self, target: Node | Selector) -> ClickResult:
        return _call("device.ui", "long_click", {"target": _target(target)})

    def set_text(self, target: Node | Selector, text: str, secret: bool = False, verify: bool = True) -> TextResult:
        args = {"target": _target(target), "text": text, "secret": bool(secret), "verify": bool(verify)}
        return _call("device.ui", "set_text", args)

    def scroll_until(self, selector: Selector, direction: Literal["down", "up", "left", "right"] = "down",
                     max_swipes: int = 10) -> Node | None:
        args = {"selector": selector.to_json(), "direction": direction, "max_swipes": int(max_swipes)}
        return _call("device.ui", "scroll_until", args).get("node")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        _call("device.ui", "swipe", {"x1": int(x1), "y1": int(y1), "x2": int(x2), "y2": int(y2),
                                     "duration_ms": int(duration_ms)})

    def tap(self, x: int, y: int) -> None:
        _call("device.ui", "tap", {"x": int(x), "y": int(y)})

    def press(self, key: str) -> None:
        _call("device.ui", "press", {"key": key})

    def hide_keyboard(self) -> None:
        _call("device.ui", "hide_keyboard")

    def keyboard_visible(self) -> bool:
        return bool(_call("device.ui", "keyboard_visible").get("visible", False))

    def screenshot(self, region: list[int] | None = None, scale: float = 1.0) -> str | None:
        return _call("device.screenshot", "capture", {"region": region, "scale": float(scale)}).get("path")


class DeviceApp:
    def query(self, package: str) -> dict:
        return _call("device.app", "query", {"package": package})

    def list(self, filter: str | None = None) -> list[dict]:  # noqa: A003 - name fixed by the stub
        return _call("device.app", "list", {"filter": filter}).get("packages", [])

    def foreground(self) -> dict:
        return _call("device.app", "foreground")

    def wait_foreground(self, package: str, timeout_ms: int) -> None:
        _call("device.app", "wait_foreground", {"package": package, "timeout_ms": int(timeout_ms)})

    def launch(self, package: str, activity: str | None = None, uri: str | None = None,
               user: int | None = None) -> LaunchResult:
        return _call("device.app", "launch", {"package": package, "activity": activity, "uri": uri, "user": user})

    def stop(self, package: str, strategy: str = "auto") -> StopResult:
        return _call("device.app", "stop", {"package": package, "strategy": strategy})


class DeviceScreen:
    def is_on(self) -> bool:
        return bool(_call("device.screen", "is_on").get("on", False))

    def is_unlocked(self) -> bool:
        return bool(_call("device.screen", "is_unlocked").get("unlocked", False))

    # ---- P8-10: control (W2) — wake best-effort, non-secure keyguard, lock, brightness ----

    def wake(self) -> dict:
        """Wake the screen (best effort). Never bypasses a secure lock."""
        return _call("device.screen", "wake", {})

    def dismiss_keyguard(self) -> dict:
        """Request a non-secure keyguard dismiss; {dismissed: bool, secure: bool}."""
        return _call("device.screen", "dismiss_keyguard", {})

    def lock(self) -> dict:
        """Lock the screen via the accessibility lock-screen action (API 28+ path)."""
        return _call("device.screen", "lock", {})

    def brightness(self, level: int | None = None) -> dict:
        """Read (or set 0-100) screen brightness. Setting needs WRITE_SETTINGS (lazily gated)."""
        args: dict = {}
        if level is not None:
            if not 0 <= int(level) <= 100:
                from ..errors import InvalidArgs
                raise InvalidArgs("level must be 0-100")
            args["level"] = int(level)
        return _call("device.screen", "brightness", args)


class DeviceIme:
    def commit_text(self, text: str, secret: bool = False, clear_first: bool = False) -> TextResult:
        return _call("device.ime", "commit_text", {"text": text, "secret": bool(secret), "clear_first": bool(clear_first)})

    def clipboard_get(self) -> str | None:
        return _call("device.ime", "clipboard_get").get("text")

    def clipboard_set(self, text: str) -> None:
        _call("device.ime", "clipboard_set", {"text": text})


ui = DeviceUi()
app = DeviceApp()
screen = DeviceScreen()
ime = DeviceIme()


def info() -> dict:
    return _call("system", "info")


def settings_read(table: str, key: str) -> dict:
    """Read one system/secure/global setting: {"value": str?, "exists": bool} (P8-11)."""
    if table not in ("system", "secure", "global"):
        raise InvalidArgs("table must be system|secure|global")
    if not key:
        raise InvalidArgs("key is required")
    return _call("device.settings", "read", {"table": table, "key": key})


def settings_read_many(table: str, keys: list[str]) -> dict:
    """Read several keys from one settings table: {"values": {key: value?}}."""
    if table not in ("system", "secure", "global"):
        raise InvalidArgs("table must be system|secure|global")
    return _call("device.settings", "read_many", {"table": table, "keys": list(keys)})


PROTECTED_SETTINGS = {
    "enabled_accessibility_services", "accessibility_enabled", "default_input_method",
    "device_provisioned", "user_setup_complete", "secure_user_id",
}


def settings_write(table: str, key: str, value: str, *, confirm: bool = False) -> dict:
    """Write one setting (P9-10; the W4 gated write path).

    secure/global tables demand confirm=True; keys the self-heal owns are always refused. Returns
    {written, table, key, path ('write_secure'|'shell'), verified} — verified comes from a read-back.
    """
    if table not in ("system", "secure", "global"):
        raise InvalidArgs("table must be system|secure|global")
    if key in PROTECTED_SETTINGS:
        raise InvalidArgs(f"the key '{key}' is protected (owned by AutoHost's self-heal)")
    if (table == "secure" or table == "global") and not confirm:
        raise InvalidArgs(f"writing '{table}' settings is gated: pass confirm=True")
    if not isinstance(value, str) or len(value) > 1000:
        raise InvalidArgs("value must be a string of at most 1000 characters")
    return _call("device.settings", "write", {"table": table, "key": key, "value": value, "confirm": confirm})
