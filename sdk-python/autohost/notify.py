"""notify.*: notifications posted by the script (capability notify.post).

`actions` attaches up to three buttons; each action is {"label": str, "handler": str, "args"?: dict}
and routes a tap to the task's @ui.handler of that name (30 s cap, plan §10.3).
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

_MAX_ACTIONS = 3


def _validate_actions(actions: list[dict[str, Any]] | None) -> list[dict[str, Any]] | None:
    if actions is None:
        return None
    if not isinstance(actions, list):
        raise InvalidArgs("actions must be a list of {label, handler} objects")
    if len(actions) > _MAX_ACTIONS:
        raise InvalidArgs(f"at most {_MAX_ACTIONS} notification actions are supported (got {len(actions)})")
    clean: list[dict[str, Any]] = []
    for action in actions:
        if not isinstance(action, dict):
            raise InvalidArgs("every notification action must be an object")
        label = action.get("label")
        handler = action.get("handler")
        if not isinstance(label, str) or not label.strip():
            raise InvalidArgs("every notification action needs a non-empty 'label'")
        if not isinstance(handler, str) or not handler.strip():
            raise InvalidArgs(f"action '{label}': 'handler' must be the name of a @ui.handler")
        entry: dict[str, Any] = {"label": label, "handler": handler}
        if "args" in action:
            if not isinstance(action["args"], dict):
                raise InvalidArgs(f"action '{label}': 'args' must be an object")
            entry["args"] = dict(action["args"])
        clean.append(entry)
    return clean


def post(title: str, text: str, channel: str | None = None, actions: list[dict[str, Any]] | None = None,
         progress: float | None = None) -> int:
    args = {"title": title, "text": text, "channel": channel,
            "actions": _validate_actions(actions), "progress": progress}
    return int(current().call("notify.post", "post", args).get("id", 0))
