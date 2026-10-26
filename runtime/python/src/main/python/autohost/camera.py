"""camera.*: one-shot photo capture (P10-07). The shutter is visible; no preview, no stealth."""
from __future__ import annotations

from typing import Any

from ._core import current


def capture_photo(front: bool = False) -> dict[str, Any]:
    """Capture one JPEG (needs CAMERA). Returns {path, width, height, size_bytes}."""
    return current().call("camera", "capture_photo", {"front": bool(front)})
