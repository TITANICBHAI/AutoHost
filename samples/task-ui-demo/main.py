"""Task UI Demo — Tier B declarative UI + @ui.handler callbacks (plan §10.1).

The UI tab renders ui/main.json. Every button there dispatches an action:
  - call(handler, args)  -> runs one of the @ui.handler functions below in the :ui worker
                            (30 s cap) and returns its dict to the UI;
  - run / run_stage      -> starts a real run (main());
  - navigate             -> jumps to a task-detail tab;
  - confirm              -> two-step dialog, then the nested action.

Handlers must return a JSON-friendly dict. A reserved "state" key merges into the UI's
{{state.*}} scope, so a handler can update the screen without a full run.
"""
from __future__ import annotations

import json
import pathlib

from autohost import ctx, log, ui

STORE = pathlib.Path(__file__).resolve().parent / "counters.json"


def _read_counts() -> dict:
    try:
        return json.loads(STORE.read_text(encoding="utf-8"))
    except Exception:
        return {"greetings": 0, "items": []}


def _write_counts(counts: dict) -> None:
    STORE.write_text(json.dumps(counts), encoding="utf-8")


@ui.handler("greet")
def greet(args: dict) -> dict:
    """Bound to the Greet button: reads {{input.who}} from the renderer's inputs."""
    who = args.get("who") or "world"
    counts = _read_counts()
    counts["greetings"] = counts.get("greetings", 0) + 1
    _write_counts(counts)
    message = f"Hello, {who}! ({counts['greetings']} greetings so far)"
    ui.console.write(f"greeted {who}", level="info")
    return {"message": message, "state": {"greetings": counts["greetings"]}}


@ui.handler("add_item")
def add_item(args: dict) -> dict:
    """Adds a row to the table through a state patch."""
    label = args.get("new_item") or f"item {len(_read_counts().get('items', [])) + 1}"
    counts = _read_counts()
    items = list(counts.get("items", []))
    items.append(label)
    counts["items"] = items[-20:]  # cap the demo table
    _write_counts(counts)
    return {"added": label, "state": {"items": items}}


@ui.handler("reset")
def reset(args: dict) -> dict:
    """Bound to a confirm-wrapped button: clears everything."""
    _write_counts({"greetings": 0, "items": []})
    log.info("demo counters reset")
    return {"message": "Counters cleared.", "state": {"greetings": 0, "items": []}}


def main() -> ctx.Result:
    """A run records one console line (visible in the UI tab's logview) and reports state."""
    counts = _read_counts()
    ctx.progress(0.5, "recording demo state")
    return ctx.result("ok", {"greetings": counts.get("greetings", 0), "items": counts.get("items", [])})
