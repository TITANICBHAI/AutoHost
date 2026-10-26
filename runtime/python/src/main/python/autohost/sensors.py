"""sensors.*: one-shot sensor reads (P10-07). No permission, no continuous streams."""
from __future__ import annotations

from typing import Any

from ._core import current


def list() -> dict[str, Any]:
    """All sensors with vendor/type. Returns {sensors:[{name,vendor,type}], count}."""
    return current().call("sensors", "list", {})


def read(names: list[str] | None = None) -> dict[str, Any]:
    """Latest one-shot sample per sensor. Returns {samples:[{name, values:[double]}]}."""
    args: dict[str, Any] = {}
    if names is not None:
        args["names"] = [*names]
    return current().call("sensors", "read", args)
