"""tasks.*: tasks.run — start another task/profile and await its result (chaining, P8-11).

Chains are depth-limited by the host (CHAIN_DEPTH) and count toward the normal locks; the target
task must be granted in this task's tasks.run scope (Permission Review).
"""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current


def run(task: str, profile: str | None = None, args: dict[str, Any] | None = None,
        timeout_ms: int | None = None) -> dict[str, Any]:
    """Run a task (optionally with a profile) and wait for its terminal status.

    The answer is {run_id, status, result?}. `result` is the child's final {code, data} as JSON text when it set
    one; autohost.macros.run decodes it for you. `args` reach a flow only through the names its `env` declares.
    """
    if not task:
        raise InvalidArgs("task is required")
    call_args: dict[str, Any] = {"task": task, "profile": profile}
    if args is not None:
        call_args["args"] = dict(args)
    if timeout_ms is not None:
        call_args["timeout_ms"] = int(timeout_ms)
    return current().call("tasks.run", "run", call_args)
