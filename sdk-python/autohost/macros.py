"""macros.*: run a macro by its id, with named inputs, and read back what it returned (INT-D).

A macro is a task, so this is tasks.run with friendlier edges: inputs are checked here (text, numbers and
true/false only), the answer comes back as a small object, and a run that did not succeed raises by default so a
script never carries on after a failed step. The macro must be named in this task's `tasks.run` grant (Permission
Review). It takes only the variables it lists in its own `env` block; any other name is ignored by the host and
shows in the run log. What the macro hands back is whatever it set with `setResult`: a code and some data.

    from autohost import macros
    macros.run("gemini-open")
    idea = macros.run("gemini-copy-idea").data["idea"]
    macros.run("gemini-send", {"prompt": "Make a short video about " + idea})
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import Any

from . import tasks
from .errors import Fatal, InvalidArgs, NeedsUser, Timeout

_PLAIN = (str, int, float, bool)


@dataclass(frozen=True)
class MacroResult:
    """What a macro run came back with. `data` is what the macro set with `setResult`; `text` is the raw answer."""

    run_id: str
    status: str
    code: str | None = None
    data: dict[str, Any] = field(default_factory=dict)
    text: str | None = None

    @property
    def ok(self) -> bool:
        return self.status == "SUCCEEDED"


def _decode(text: str | None) -> tuple[str | None, dict[str, Any]]:
    """The host hands the child's final {code, data} back as JSON text; anything else decodes to nothing."""
    if not text:
        return None, {}
    try:
        value = json.loads(text)
    except ValueError:
        return None, {}
    if not isinstance(value, dict):
        return None, {}
    code = value.get("code")
    data = value.get("data")
    return (str(code) if code is not None else None), (dict(data) if isinstance(data, dict) else {})


def _inputs(inputs: dict[str, Any] | None) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for name, value in (inputs or {}).items():
        if not isinstance(name, str) or not name:
            raise InvalidArgs("input names must be non-empty text")
        if value is None:
            continue  # left out, so the macro keeps its own default
        if not isinstance(value, _PLAIN) or (isinstance(value, float) and not math.isfinite(value)):
            raise InvalidArgs("input %r must be text, a number or true/false" % name)
        out[name] = value
    return out


def run(macro: str, inputs: dict[str, Any] | None = None, *, profile: str | None = None,
        timeout_ms: int | None = None, check: bool = True) -> MacroResult:
    """Run a macro by id with named inputs and wait for it to finish.

    With check=True (the default) a macro that did not succeed raises instead of returning: Timeout when it was
    still running at the deadline, NeedsUser when it is waiting for you, Fatal for anything else. With check=False
    the result comes back for you to look at.
    """
    if not macro:
        raise InvalidArgs("macro is required")
    args = _inputs(inputs)
    raw = tasks.run(macro, profile=profile, args=args or None, timeout_ms=timeout_ms)
    text = raw.get("result")
    code, data = _decode(text if isinstance(text, str) else None)
    result = MacroResult(run_id=str(raw.get("run_id", "")), status=str(raw.get("status", "")),
                         code=code, data=data, text=text if isinstance(text, str) else None)
    if check and not result.ok:
        if raw.get("await") == "timeout" or result.status == "RUNNING":
            raise Timeout("macro %r was still running when the wait ended" % macro)
        if result.status == "WAITING_USER":
            raise NeedsUser("NEEDS_INPUT", "macro %r is waiting for you" % macro)
        raise Fatal("macro %r ended %s" % (macro, result.status or "without a status"))
    return result
