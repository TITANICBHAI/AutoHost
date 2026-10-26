"""Worker-side entry point: run(run_spec_json, bridge) -> outcome JSON string.

The Kotlin `BaseTaskWorkerService` starts Chaquopy, builds a `PyBridge(broker, runToken)` and calls
`autohost._runtime.run(specJson, bridge)` on a worker thread. This module:

1. parses the RunSpec (schemas/broker.schema.json#/$defs/RunSpec),
2. builds a `Runtime` whose transport is the Kotlin bridge,
3. imports the task's entry module from the task directory and calls `main()`,
4. emits a final `{"type": "finished", "data": <outcome>}` event,
5. returns the outcome as JSON so the host can log/verify it.

Outcome shape: {"status": "SUCCEEDED|FAILED|CANCELLED|WAITING_USER", "reason"?, "message"?,
"traceback"?, "result": {"code": ..., "data": ...}}. A script bug is a FAILED run with a traceback,
never a worker crash.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import traceback
from pathlib import Path
from typing import Any

from . import _core
from ._core import Runtime, install, uninstall
from ._ctx import Result
from .errors import AutoHostError, Cancelled, NeedsUser


class BridgeTransport:
    """Adapts the Kotlin PyBridge (JSON strings over AIDL) to the Runtime transport protocol."""

    def __init__(self, bridge: Any) -> None:
        self._bridge = bridge

    def call(self, capability: str, method: str, args: dict) -> dict:
        raw = self._bridge.call(capability, method, json.dumps(dict(args or {})))
        return json.loads(raw)

    def emit(self, event: dict) -> None:
        self._bridge.emit(json.dumps(event))

    def describe(self) -> dict:
        return json.loads(self._bridge.describe())


def make_runtime(spec: dict, bridge: Any) -> Runtime:
    trigger = spec.get("trigger") or {}
    return Runtime(
        BridgeTransport(bridge),
        task_id=spec.get("taskId") or "unknown",
        config=spec.get("config") or {},
        profile=spec.get("profile") or {},
        profile_id=spec.get("profileId"),
        run_id=spec.get("runId") or "run-1",
        attempt=spec.get("attempt") or 1,
        trigger_type=trigger.get("type") or "manual",
        trigger_payload=trigger.get("payload") or {},
        state=spec.get("checkpoint"),
    )


def _sanitize(name: str) -> str:
    return "".join(ch if ch.isalnum() else "_" for ch in name)


def load_entry_module(task_path: str, entry: str, module_name: str | None = None):
    """Import the task's entry file as a module. Raises `EntryNotFound` (an AutoHostError) when missing.

    Shared by `execute_with_runtime` (runs) and `run_ui_handler` (task-UI callbacks) so both load task
    code identically. sys.path stays the caller's business (run() and the simulator both manage it).
    """
    task_dir = str(Path(task_path))
    entry_path = Path(task_dir) / entry
    if not entry_path.is_file():
        raise EntryNotFound(f"could not load entry: {entry}")
    spec = importlib.util.spec_from_file_location(
        module_name or ("autohost_task_" + _sanitize(Path(task_dir).name)), entry_path)
    if spec is None or spec.loader is None:
        raise EntryNotFound(f"could not load entry: {entry}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EntryNotFound(AutoHostError):
    reason = "ENTRY_NOT_FOUND"

    def __init__(self, message: str) -> None:
        super().__init__(message, reason="ENTRY_NOT_FOUND")


def execute_with_runtime(rt: Runtime, task_path: str, entry: str) -> dict:
    """Install [rt], import the entry module from [task_path], run main(), and classify the outcome.

    Shared by the Android worker (`_runtime.run`) and the simulator (`autohost_sim.runner`) so both hosts
    classify failures identically.
    """
    install(rt)
    module_name = "autohost_task_" + _sanitize(rt.task_id)
    task_dir = str(Path(task_path))
    sys.path.insert(0, task_dir)
    try:
        module = load_entry_module(task_dir, entry, module_name)
        returned = module.main()
        if rt.cancelled:
            # A task that cancelled itself (or was cancelled) without raising still counts as cancelled.
            return {"status": "CANCELLED", "reason": "USER_CANCELLED", "message": "run was cancelled"}
        result = returned if isinstance(returned, Result) else rt.result
        if result is None:
            result = Result("OK", {})
        return {"status": "SUCCEEDED", "result": {"code": result.code, "data": dict(result.data)}}
    except Cancelled as exc:
        return {"status": "CANCELLED", "reason": "USER_CANCELLED", "message": str(exc)}
    except NeedsUser as exc:
        outcome: dict = {"status": "WAITING_USER", "reason": exc.reason, "message": str(exc)}
        if exc.resume_hint:
            outcome["resume_hint"] = exc.resume_hint
        return outcome
    except AutoHostError as exc:
        outcome = {"status": "FAILED", "reason": exc.reason or exc.code, "message": str(exc)}
        if exc.details:
            outcome["details"] = dict(exc.details)
        return outcome
    except Exception as exc:  # a script bug is a FAILED run with a traceback, never a worker crash
        return {
            "status": "FAILED",
            "reason": "EXCEPTION",
            "message": f"{type(exc).__name__}: {exc}",
            "traceback": traceback.format_exc(),
        }
    finally:
        if task_dir in sys.path:
            sys.path.remove(task_dir)
        uninstall()


def run(run_spec_json: str, bridge: Any) -> str:
    """Host entry point. Returns the outcome JSON string (also emitted as the `finished` event)."""
    spec = json.loads(run_spec_json)
    rt = make_runtime(spec, bridge)
    outcome = execute_with_runtime(rt, spec.get("taskPath") or "", spec.get("entry") or "main.py")
    try:
        rt.emit({"type": "finished", "data": outcome})
    except Exception:
        # If even the finished event cannot be delivered, hand the failure back to the host on stdout of run().
        outcome = {
            "status": "FAILED",
            "reason": "FINISHED_EVENT_FAILED",
            "message": "could not deliver the finished event to the host",
            "traceback": traceback.format_exc(),
        }
    return json.dumps(outcome)


def _handlers_of(module: Any) -> dict[str, Any]:
    """Collect @ui.handler functions in declaration order; the first registration of a name wins."""
    found: dict[str, Any] = {}
    for value in vars(module).values():
        name = getattr(value, "__autohost_handler__", None)
        if isinstance(name, str) and callable(value) and name not in found:
            found[name] = value
    return found


def run_ui_handler(handler_spec_json: str, bridge: Any) -> str:
    """UI-handler entry point (P7-03, plan §5.2/§10.1): run one `@ui.handler` call to completion.

    Called by the `:ui` worker (`UiHandlerWorkerService`) — a warm worker process separate from run
    workers, so a task-UI callback never queues behind a long run. HandlerSpec (JSON):
    {taskId, taskPath, entry, handler, args?, config?, profile?, profileId?, runToken?}.

    The outcome JSON has the run outcome shape: SUCCEEDED with result.data = the handler's returned
    dict ({} when it returns None), or the standard FAILED/CANCELLED/WAITING_USER classification.
    A handler must return a JSON-friendly dict; anything else is a FAILED HANDLER_RESULT, because
    the result is forwarded verbatim to the UI surface that made the call.
    """
    spec = json.loads(handler_spec_json)
    trigger = {"type": "ui", "payload": {"handler": spec.get("handler") or "",
                                         "args": dict(spec.get("args") or {})}}
    rt = Runtime(
        BridgeTransport(bridge),
        task_id=spec.get("taskId") or "unknown",
        config=spec.get("config") or {},
        profile=spec.get("profile") or {},
        profile_id=spec.get("profileId"),
        run_id=spec.get("runId") or ("ui-" + _sanitize(str(spec.get("handler") or "handler"))),
        trigger_type="ui",
        trigger_payload=trigger["payload"],
    )
    task_dir = str(Path(spec.get("taskPath") or ""))
    sys.path.insert(0, task_dir)
    try:
        install(rt)
        outcome = _invoke_handler(rt, task_dir, spec)
        return json.dumps(outcome)
    finally:
        if task_dir in sys.path:
            sys.path.remove(task_dir)
        uninstall()


def _invoke_handler(rt: Runtime, task_dir: str, spec: dict) -> dict:
    name = spec.get("handler") or ""
    try:
        module = load_entry_module(task_dir, spec.get("entry") or "main.py")
        handler = _handlers_of(module).get(name)
        if handler is None:
            return {
                "status": "FAILED",
                "reason": "HANDLER_NOT_FOUND",
                "message": f"no @ui.handler('{name}') in {spec.get('entry') or 'main.py'}",
            }
        args = dict(spec.get("args") or {})
        returned = handler(args)
        if rt.cancelled:
            return {"status": "CANCELLED", "reason": "USER_CANCELLED", "message": "run was cancelled"}
        if returned is None:
            return {"status": "SUCCEEDED", "result": {"code": "OK", "data": {}}}
        if isinstance(returned, Result):
            return {"status": "SUCCEEDED", "result": {"code": returned.code, "data": dict(returned.data)}}
        if not isinstance(returned, dict):
            return {
                "status": "FAILED",
                "reason": "HANDLER_RESULT",
                "message": f"handler '{name}' must return a JSON-friendly dict (got {type(returned).__name__})",
            }
        return {"status": "SUCCEEDED", "result": {"code": "OK", "data": dict(returned)}}
    except Cancelled as exc:
        return {"status": "CANCELLED", "reason": "USER_CANCELLED", "message": str(exc)}
    except NeedsUser as exc:
        outcome: dict = {"status": "WAITING_USER", "reason": exc.reason, "message": str(exc)}
        if exc.resume_hint:
            outcome["resume_hint"] = exc.resume_hint
        return outcome
    except AutoHostError as exc:
        outcome = {"status": "FAILED", "reason": exc.reason or exc.code, "message": str(exc)}
        if exc.details:
            outcome["details"] = dict(exc.details)
        return outcome
    except Exception as exc:  # a handler bug is a FAILED call with a traceback, never a worker crash
        return {
            "status": "FAILED",
            "reason": "EXCEPTION",
            "message": f"{type(exc).__name__}: {exc}",
            "traceback": traceback.format_exc(),
        }


# --- control surface called by the host (Kotlin) between SDK calls -------------------------------

def request_pause() -> None:
    try:
        _core.current().paused = True
    except Exception:
        pass  # no active run yet: nothing to pause


def request_resume() -> None:
    try:
        rt = _core.current()
        rt.paused = False
    except Exception:
        pass


def request_cancel() -> None:
    try:
        rt = _core.current()
        rt.cancelled = True
        rt.paused = False  # unblock a paused run so it can observe the cancellation
    except Exception:
        pass
