"""Task UI (HTML) — Tier C sandboxed WebView + the autohost JS bridge (plan §10.1).

The task's ui/index.html runs in a WebView with **no network** and **no file access outside the
task bundle**. It may only:
  - call `autohost.invoke(handler, args)` — routed to a @ui.handler below through the :ui worker;
  - read `autohost.state()` — the host-side declarative-UI state map;
  - receive `window.autohostEvent(topic, payload)` pushes (topic "run" carries run status).
"""
from __future__ import annotations

import json
import pathlib

from autohost import ui

COUNTS = pathlib.Path(__file__).resolve().parent / "clicks.json"


def _read() -> int:
    try:
        return int(json.loads(COUNTS.read_text(encoding="utf-8")).get("clicks", 0))
    except Exception:
        return 0


def _write(clicks: int) -> None:
    COUNTS.write_text(json.dumps({"clicks": clicks}), encoding="utf-8")


@ui.handler("increment")
def increment(args: dict) -> dict:
    clicks = _read() + int(args.get("by", 1))
    _write(clicks)
    return {"clicks": clicks}


@ui.handler("reset")
def reset(args: dict) -> dict:
    _write(0)
    return {"clicks": 0}


def main() -> object:
    """A run counts as one click (so schedules can drive the counter too)."""
    clicks = _read() + 1
    _write(clicks)
    return {"clicks": clicks}
