"""ui.*: prompts, dialogs, forms and overlays shown to the user while a task interacts with them.

Surfaces (plan §10.2):
- ask / toast        — P5-06, unchanged.
- dialog / form      — a themed dialog (activity); results return to the script. Dismissal and
                       timeout are honest: dialog returns "" and form returns None.
- overlay            — a short-lived text strip over the current app (accessibility overlay). When
                       the accessibility service is off the host answers {"shown": false, ...}
                       instead of pretending.
- console.write      — log-like lines for the task's own UI surface (the Tier B logview widget and
                       the run screen). Emits a `ui.console` event; secrets are redacted like logs.
- handler            — marks a function as a task-UI callback; the host calls it by name when a UI
                       surface (Tier B button, Tier C bridge, notification action) invokes it.
"""
from __future__ import annotations

from typing import Any, Callable, Literal

from ._core import current
from .errors import InvalidArgs

_FIELD_TYPES = frozenset({"text", "number", "switch", "select"})
_MAX_FORM_FIELDS = 24
_MAX_BUTTONS = 4


def ask(title: str, message: str, kind: Literal["confirm", "text", "choice"] = "confirm",
        choices: list[str] | None = None, timeout_ms: int | None = None) -> Any:
    if kind not in ("confirm", "text", "choice"):
        raise InvalidArgs(f"unknown prompt kind: {kind}")
    if kind == "choice" and not choices:
        raise InvalidArgs("a choice prompt needs choices")
    args = {"title": title, "message": message, "kind": kind,
            "choices": list(choices) if choices else None, "timeout_ms": timeout_ms}
    return current().call("ui.prompt", "ask", args)


def toast(text: str) -> None:
    current().call("ui.prompt", "toast", {"text": text})


def _validate_buttons(buttons: list[str] | None) -> list[str]:
    if buttons is None:
        return ["OK"]
    if not isinstance(buttons, list) or not buttons:
        raise InvalidArgs("buttons must be a non-empty list of labels")
    if len(buttons) > _MAX_BUTTONS:
        raise InvalidArgs(f"at most {_MAX_BUTTONS} buttons are supported (got {len(buttons)})")
    for label in buttons:
        if not isinstance(label, str) or not label.strip():
            raise InvalidArgs("every button needs a non-empty label string")
    return list(buttons)


def dialog(title: str, message: str, buttons: list[str] | None = None) -> str:
    """Show a dialog with explicit buttons; return the pressed label, or "" when dismissed/timeout."""
    valid = _validate_buttons(buttons)
    result = current().call("ui.prompt", "dialog",
                            {"title": title, "message": message, "buttons": valid})
    if not isinstance(result, dict) or not result.get("answered"):
        return ""
    button = result.get("button")
    return button if isinstance(button, str) else ""


def _validate_fields(fields: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not isinstance(fields, list) or not fields:
        raise InvalidArgs("form needs a non-empty list of field objects")
    if len(fields) > _MAX_FORM_FIELDS:
        raise InvalidArgs(f"at most {_MAX_FORM_FIELDS} fields are supported (got {len(fields)})")
    seen: set[str] = set()
    clean: list[dict[str, Any]] = []
    for field in fields:
        if not isinstance(field, dict):
            raise InvalidArgs("every form field must be an object")
        name = field.get("name")
        if not isinstance(name, str) or not name.strip():
            raise InvalidArgs("every form field needs a non-empty 'name'")
        if name in seen:
            raise InvalidArgs(f"duplicate form field name: '{name}'")
        seen.add(name)
        ftype = field.get("type", "text")
        if ftype not in _FIELD_TYPES:
            raise InvalidArgs(f"field '{name}': unknown type '{ftype}' (use {sorted(_FIELD_TYPES)})")
        if ftype == "select":
            choices = field.get("choices")
            if not isinstance(choices, list) or not choices:
                raise InvalidArgs(f"field '{name}': a select field needs non-empty choices")
        clean.append(dict(field))
    return clean


def form(title: str, fields: list[dict[str, Any]], timeout_ms: int | None = None) -> dict[str, Any] | None:
    """Show a form; return {name: value} when submitted, or None when dismissed/timeout."""
    valid = _validate_fields(fields)
    args: dict[str, Any] = {"title": title, "fields": valid}
    if timeout_ms is not None:
        args["timeout_ms"] = timeout_ms
    result = current().call("ui.prompt", "form", args)
    if not isinstance(result, dict) or not result.get("answered"):
        return None
    values = result.get("values")
    return dict(values) if isinstance(values, dict) else {}


def overlay(text: str, duration_ms: int = 3000) -> dict[str, Any]:
    """Flash a text strip over the current app. Returns the host's honest verdict: {"shown": bool}."""
    if not isinstance(text, str) or not text.strip():
        raise InvalidArgs("overlay needs non-empty text")
    ms = int(duration_ms)
    if ms < 250 or ms > 30_000:
        raise InvalidArgs("overlay duration_ms must be between 250 and 30000")
    result = current().call("ui.prompt", "overlay", {"text": text, "duration_ms": ms})
    return result if isinstance(result, dict) else {"shown": False}


# ---- P8-10: ui.overlay — floating overlays a run can draw (a11y overlay path) ------------

def highlight(x: int, y: int, w: int, h: int, label: str | None = None, ms: int = 2000) -> dict[str, Any]:
    """Draw a highlight rectangle (absolute pixels) for a duration. Needs the a11y overlay."""
    for name, value in (("x", x), ("y", y), ("w", w), ("h", h)):
        if not isinstance(value, int) or value < 0:
            raise InvalidArgs(f"{name} must be a non-negative int")
    if ms < 0:
        raise InvalidArgs("ms must be >= 0")
    return current().call("ui.overlay", "highlight", {
        "x": int(x), "y": int(y), "w": int(w), "h": int(h), "label": label, "ms": int(ms),
    })


def panel(text: str, corner: str = "top") -> dict[str, Any]:
    """Show a small text panel (run progress) until cleared or the run ends."""
    if not isinstance(text, str) or not text.strip():
        raise InvalidArgs("panel needs non-empty text")
    if corner not in ("top", "bottom"):
        raise InvalidArgs("corner must be top|bottom")
    return current().call("ui.overlay", "panel", {"text": text, "corner": corner})


def overlay_clear() -> dict[str, Any]:
    """Remove this run's overlays."""
    return current().call("ui.overlay", "clear", {})


class console:
    """Script-written lines for the task's own UI surface (Tier B logview, run screen)."""

    LEVELS = ("info", "warn", "error")

    @staticmethod
    def write(text: str, level: str = "info") -> None:
        if level not in console.LEVELS:
            raise InvalidArgs(f"unknown console level '{level}' (use {list(console.LEVELS)})")
        current().emit({"type": "ui.console", "data": {"text": str(text), "level": level}})


def handler(name: str) -> Callable:
    """Mark a function as a task-UI callback. UI surfaces invoke handlers by name.

    The function receives one argument (the call's args dict, possibly empty) and should return a
    JSON-friendly dict — it becomes the call result the UI sees. A returned dict may carry a
    reserved "state" key (a dict); the UI host merges it into the task's declarative-UI state.
    """

    def decorate(fn: Callable) -> Callable:
        fn.__autohost_handler__ = name
        return fn

    return decorate
