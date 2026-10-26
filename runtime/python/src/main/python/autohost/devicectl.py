"""devicectl.*: device.radio / device.display / device.location.mock — T3 device control (P9-10).

Radios toggle through the shell tier (`svc`, settings + broadcast) and report the exit code
honestly (verified:false — no readback claim). Display control rides `cmd uimode` / `settings put`.
Mock location is the appop on AutoHost itself, verified against `appops get`; it needs the
`mock_location` switch on top of the per-task grant.
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current

RADIOS = ("wifi", "bluetooth", "mobile_data", "airplane")
ORIENTATIONS = ("auto", "portrait", "landscape", "reverse_portrait", "reverse_landscape")


def _radio(radio: str, enable: bool) -> dict[str, Any]:
    if radio not in RADIOS:
        raise InvalidArgs(f"radio must be one of {'|'.join(RADIOS)}")
    return current().call("device.radio", "set", {"radio": radio, "enable": bool(enable)})


def set_wifi(enable: bool) -> dict[str, Any]:
    """Toggle Wi-Fi (`svc wifi`). verified:false — the act is honest about having no readback."""
    return _radio("wifi", enable)


def set_bluetooth(enable: bool) -> dict[str, Any]:
    """Toggle Bluetooth (`svc bluetooth`; only works up to API 32)."""
    return _radio("bluetooth", enable)


def set_mobile_data(enable: bool) -> dict[str, Any]:
    """Toggle mobile data (`svc data`)."""
    return _radio("mobile_data", enable)


def set_airplane(enable: bool) -> dict[str, Any]:
    """Toggle airplane mode (global write + the connectivity broadcast, two steps)."""
    return _radio("airplane", enable)


def _display(args: dict[str, Any]) -> dict[str, Any]:
    return current().call("device.display", args.pop("_method"), args)


def set_dark_mode(mode: str) -> dict[str, Any]:
    """`cmd uimode night yes|no|auto`."""
    if mode not in ("yes", "no", "auto"):
        raise InvalidArgs("mode must be yes|no|auto")
    return _display({"_method": "set_dark_mode", "mode": mode})


def set_orientation(orientation: str) -> dict[str, Any]:
    """Lock the orientation or re-enable auto-rotate."""
    if orientation not in ORIENTATIONS:
        raise InvalidArgs(f"orientation must be one of {'|'.join(ORIENTATIONS)}")
    return _display({"_method": "set_orientation", "orientation": orientation})


def set_font_scale(scale: float) -> dict[str, Any]:
    """`settings put system font_scale` (0.5..2.0)."""
    if not 0.5 <= float(scale) <= 2.0:
        raise InvalidArgs("scale must be 0.5..2.0")
    return _display({"_method": "set_font_scale", "scale": str(scale)})


def set_mock_location(enable: bool) -> dict[str, Any]:
    """Allow/deny the mock-location appop for app.autohost (the only targetable package)."""
    return current().call("device.location.mock", "set", {"enable": bool(enable)})
