"""clipboard.*: read (focus-gated) and set the system clipboard (capability device.clipboard).

Android 10+ lets an app read the clipboard only while it has window focus; background reads go
through the AutoHost keyboard (`device.ime.clipboard_get`, selected via Setup). `set` always works.
"""
from __future__ import annotations

from ._core import current
from .errors import InvalidArgs

_MAX_SET = 1_000_000


def get() -> dict:
    """{text, focused, empty?}; raises CapabilityUnavailable with a hint when not focused."""
    return current().call("device.clipboard", "get", {})


def set(text: str) -> None:
    if not isinstance(text, str):
        raise InvalidArgs("clipboard text must be a string")
    if len(text) > _MAX_SET:
        raise InvalidArgs(f"clipboard text exceeds the {_MAX_SET}-character cap")
    current().call("device.clipboard", "set", {"text": text})
