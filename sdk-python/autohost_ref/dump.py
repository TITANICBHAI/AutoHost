"""Compact UI dump: the canonical text form of a UI snapshot (plan section 7.6).

    App: <label> (<package>)  Activity: <name>  Keyboard: visible|hidden  Focused: '<text>'
    1. ShortClass: "resource-id" "text or desc" [flag,flag] (l,t,r,b)
      2. Child: ...            (two spaces of indent per level of depth)

Rules (shared with the Kotlin CompactDump through testing/golden/compact_dump.json):
- indices are 1-based, depth-first, and count only nodes that pass the filter;
- text falls back to the content description; password fields never show text;
- quotes become \\", newlines become \\n, values longer than 80 characters end with an ellipsis;
- the default filter keeps on-screen nodes of at least 5 px in each dimension that are at least 1 % visible.
"""
from __future__ import annotations

from typing import Any

FLAG_ORDER = ("clickable", "checked", "editable", "scrollable", "focused", "selected")
MAX_VALUE = 80
DEFAULT_FILTER = {"on_screen": True, "min_size": 5, "min_visible_ratio": 0.01}


def _clip(value: str) -> str:
    value = value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return value if len(value) <= MAX_VALUE else value[: MAX_VALUE - 1] + "\u2026"


def _visible_ratio(bounds: list, screen: tuple) -> float:
    l, t, r, b = bounds
    area = max(0, r - l) * max(0, b - t)
    if area == 0:
        return 0.0
    il, it = max(l, 0), max(t, 0)
    ir, ib = min(r, screen[0]), min(b, screen[1])
    return (max(0, ir - il) * max(0, ib - it)) / area


def _keep(node: dict, flt: dict | None, screen: tuple) -> bool:
    if flt is None:
        return True
    l, t, r, b = node.get("bounds", [0, 0, 0, 0])
    if min(r - l, b - t) < flt.get("min_size", 0):
        return False
    ratio = _visible_ratio(node.get("bounds", [0, 0, 0, 0]), screen)
    if flt.get("on_screen", False) and ratio <= 0:
        return False
    return ratio >= flt.get("min_visible_ratio", 0.0)


def _shown_text(node: dict) -> str:
    if node.get("password"):
        return ""
    return str(node.get("text") or node.get("desc") or "")


def compact(nodes: list[dict], *, label: str = "", package: str = "", activity: str = "",
            keyboard: str = "hidden", screen: tuple = (1080, 2400), flt: Any = "default") -> str:
    """nodes: flat list in depth-first order, each with cls, id, text, desc, bounds, flags (list), depth (int)."""
    flt = DEFAULT_FILTER if flt == "default" else flt
    focused = next((n for n in nodes if "focused" in n.get("flags", [])), None)
    focus_text = _clip(_shown_text(focused) or focused.get("id", "")) if focused else ""
    out = [f"App: {label or package or 'none'} ({package or 'none'})  Activity: {activity or 'none'}  "
           f"Keyboard: {keyboard}  Focused: '{focus_text}'"]
    index = 0
    for node in nodes:
        if not _keep(node, flt, screen):
            continue
        index += 1
        flags = [f for f in FLAG_ORDER if f in node.get("flags", [])]
        l, t, r, b = node.get("bounds", [0, 0, 0, 0])
        short = str(node.get("cls", "View")).split(".")[-1]
        indent = "  " * int(node.get("depth", 0))
        out.append(f'{indent}{index}. {short}: "{_clip(str(node.get("id", "")))}" "{_clip(_shown_text(node))}" '
                   f'[{",".join(flags)}] ({l},{t},{r},{b})')
    return "\n".join(out)
