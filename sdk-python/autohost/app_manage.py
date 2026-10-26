"""app_manage.*: device.app.manage — install/clear/grant/force-stop through the shell tier (P9-09).

DANGEROUS by design: needs BOTH the per-task device.app.manage grant AND the global `app_manage`
switch. Destructive methods (clear_state, install, uninstall) demand confirm=true — the SDK
requires it as an explicit keyword, it is never defaulted.
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current

CONFIRM_DETAIL = "this method is destructive: pass confirm=True"


def force_stop(package: str) -> dict[str, Any]:
    """Verified force-stop chain: shell `am force-stop` first, then the T1/T0 fallbacks.

    Returns {result: VERIFIED|BEST_EFFORT|FAILED, method, exit_code?}.
    """
    if not isinstance(package, str) or not package.strip():
        raise InvalidArgs("package is required")
    return current().call("device.app.manage", "force_stop", {"package": package})


def clear_state(package: str, *, confirm: bool = False) -> dict[str, Any]:
    """`pm clear` — destroys the app's data. confirm=True is mandatory."""
    if not confirm:
        raise InvalidArgs(CONFIRM_DETAIL)
    return current().call("device.app.manage", "clear_state", {"package": package, "confirm": True})


def grant_permission(package: str, permission: str) -> dict[str, Any]:
    """`pm grant` — runtime permission grant via the shell uid (applied, no readback claim)."""
    if not isinstance(package, str) or not package.strip() or not isinstance(permission, str) or "." not in permission:
        raise InvalidArgs("package and a fully-qualified permission are required")
    return current().call("device.app.manage", "grant_permission", {"package": package, "permission": permission})


def revoke_permission(package: str, permission: str) -> dict[str, Any]:
    """`pm revoke` — the revoke twin of grant_permission."""
    if not isinstance(package, str) or not package.strip() or not isinstance(permission, str) or "." not in permission:
        raise InvalidArgs("package and a fully-qualified permission are required")
    return current().call("device.app.manage", "revoke_permission", {"package": package, "permission": permission})


def install(path: str, *, confirm: bool = False, mode: str | None = None) -> dict[str, Any]:
    """Install an APK from an absolute device path.

    mode "pm" streams through the shell tier; mode "installer" drives the system installer UI with
    the a11y auto-accept gate (honest CONFIRMED/WAITING_USER outcomes, never a package readback).
    """
    if not confirm:
        raise InvalidArgs(CONFIRM_DETAIL)
    if not isinstance(path, str) or not path.startswith("/"):
        raise InvalidArgs("path must be an absolute device path")
    args: dict[str, Any] = {"path": path, "confirm": True}
    if mode is not None:
        if mode not in ("pm", "installer"):
            raise InvalidArgs("mode must be pm|installer")
        args["mode"] = mode
    return current().call("device.app.manage", "install", args)


def uninstall(package: str, *, confirm: bool = False) -> dict[str, Any]:
    """`pm uninstall` — confirm=True is mandatory."""
    if not confirm:
        raise InvalidArgs(CONFIRM_DETAIL)
    return current().call("device.app.manage", "uninstall", {"package": package, "confirm": True})
