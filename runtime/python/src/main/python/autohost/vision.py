"""vision.*: vision — OCR, template match, QR decode (P10-05).

Tier T4 contract: every result carries ``hint: true`` — vision output is an advisory signal,
never verified UI state. OCR text may contain secrets that happen to be on screen; results are
never auto-evidenced.
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

REGION_KEYS = ("left", "top", "width", "height")


def _region(region: dict[str, int] | None) -> dict[str, int] | None:
    if region is None:
        return None
    if not isinstance(region, dict) or not all(k in region for k in REGION_KEYS):
        raise InvalidArgs("region must be {left, top, width, height}")
    return {k: int(region[k]) for k in REGION_KEYS}


def _source_args(source: str, path: str | None, region: dict[str, int] | None) -> dict[str, Any]:
    if source not in ("screen", "file"):
        raise InvalidArgs("source must be 'screen' or 'file'")
    if source == "file" and not path:
        raise InvalidArgs("path is required when source is 'file'")
    args: dict[str, Any] = {"source": source}
    if path is not None:
        args["path"] = path
    r = _region(region)
    if r is not None:
        args["region"] = r
    return args


def ocr(source: str = "screen", path: str | None = None,
        region: dict[str, int] | None = None) -> dict[str, Any]:
    """Recognize text in the current screen or an image file.

    Returns {hint:true, engine, source, width, height, duration_ms, lines:[{text, confidence?, box?}]}.
    """
    return current().call("vision", "ocr", _source_args(source, path, region))


def template_match(needle_path: str, *, source: str = "screen", path: str | None = None,
                   region: dict[str, int] | None = None, threshold: float | None = None,
                   stride: int | None = None) -> dict[str, Any]:
    """Zero-mean NCC template match of a needle image over the source.

    Returns {hint:true, found, score, box:{left,top,width,height}, threshold, stride, source, duration_ms}.
    Not rotation/scale invariant.
    """
    if not needle_path:
        raise InvalidArgs("needle_path is required")
    if threshold is not None and not 0.5 <= float(threshold) <= 1.0:
        raise InvalidArgs("threshold must be within 0.5..1.0")
    if stride is not None and not 1 <= int(stride) <= 32:
        raise InvalidArgs("stride must be within 1..32")
    args = _source_args(source, path, region)
    args["needle_path"] = needle_path
    if threshold is not None:
        args["threshold"] = float(threshold)
    if stride is not None:
        args["stride"] = int(stride)
    return current().call("vision", "template_match", args)


def qr_decode(source: str = "screen", path: str | None = None,
              region: dict[str, int] | None = None) -> dict[str, Any]:
    """Decode a QR code from the screen or an image file.

    Returns {hint:true, engine, source, found, value?, duration_ms}.
    """
    return current().call("vision", "qr_decode", _source_args(source, path, region))
