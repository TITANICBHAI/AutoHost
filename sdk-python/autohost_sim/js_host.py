"""run_js_task: execute a JavaScript task under node against a FakeDevice (P11-03, ADR-077).

The JS surface (the prelude) is the SAME source the device runtime loads into QuickJS; this
module serves the SAME op table the Kotlin `JsBridge` serves (call/emit/sleep/ctx/state_save/
secret/clock/result/next_run/capabilities — one table, docs/SDK_JS.md), backed by a real
`Runtime` over the SimTransport. Config, secrets, redaction, cancellation and the outcome
classification mirror `autohost._runtime.execute_with_runtime` branch-for-branch, so a JS task
fails in the simulator exactly like it would on the device.

The node process runs `js/node_shim.cjs <prelude.js> <entry.js>`; the prelude is the
byte-identical copy of `runtime/js/src/main/resources/autohost/js/prelude.js` (a drift test
pins the two together). Tests without node on PATH are skipped — the sandbox/CI ships node.
"""
from __future__ import annotations

import json
import shutil
import select
import subprocess
import tempfile
import time
from dataclasses import dataclass, field as dataclass_field
from pathlib import Path
from typing import Any

from autohost._core import Runtime
from autohost._ctx import Result
from autohost.errors import AutoHostError, Cancelled

from .device import FakeDevice
from .transport import SimTransport

NODE = shutil.which("node")
SHIM = Path(__file__).resolve().parent / "js" / "node_shim.cjs"
PRELUDE = Path(__file__).resolve().parent / "js" / "prelude.js"

#: Wall-clock guard for one JS run in the simulator (the FakeDevice clock is virtual; this only
#: bounds a wedged shim/host loop so a suite can never hang).
HOST_DEADLINE_S = 120.0

#: Upper bound for one bridge sleep (mirrors JsBridge.MAX_SLEEP_SECONDS).
MAX_SLEEP_SECONDS = 3600.0


@dataclass
class JsOutcome:
    status: str
    reason: str | None = None
    message: str = ""
    result: Result | None = None
    events: list = dataclass_field(default_factory=list)
    traceback: str | None = None


def run_js_task(task_dir: str | Path, entry: str = "main.js", *, device: FakeDevice,
                config: dict | None = None, profile: dict | None = None, profile_id: str = "p1",
                secrets: dict | None = None, trigger_type: str = "manual",
                trigger_payload: dict | None = None,
                cancel_after_s: float | None = None) -> JsOutcome:
    """Runs the task's JS entry under node against [device]; returns the classified outcome."""
    if NODE is None:
        raise RuntimeError("node is not available on PATH; JS sim runs need node (see docs/SDK_JS.md)")
    task_dir = Path(task_dir)
    manifest: dict[str, Any] = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
    device.profile_id = profile_id
    # P11-04 (ADR-080): the same pack injection the Python/flow hosts get (ctx.config.packs).
    from .runner import resolve_packs
    config = resolve_packs(manifest, dict(config or {}), device)

    rt = Runtime(SimTransport(device), task_id=manifest["id"], config=config, profile=profile,
                 profile_id=profile_id, secrets=secrets, clock=device.clock,
                 trigger_type=trigger_type, trigger_payload=trigger_payload)
    if cancel_after_s is not None:
        previous = device.clock.on_tick

        def stop_when_due(clock: Any) -> None:
            if previous:
                previous(clock)
            if clock.slept >= cancel_after_s:
                rt.cancelled = True

        device.clock.on_tick = stop_when_due

    raw = _serve(rt, task_dir, entry)
    return _classify(rt, raw)


# --- the op server (the Python twin of Kotlin JsBridge) ------------------------------------------

def _serve(rt: Runtime, task_dir: Path, entry: str) -> dict | None:
    """Runs the shim, serves one op per line, returns the `done` outcome (or None on a timeout)."""
    entry_file = task_dir / entry
    with tempfile.TemporaryFile() as stderr:
        proc = subprocess.Popen(
            [NODE, str(SHIM), str(PRELUDE), str(entry_file)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
            cwd=str(task_dir), text=True, encoding="utf-8", bufsize=1,
        )
        raw_outcome: dict | None = None
        try:
            end = time.monotonic() + HOST_DEADLINE_S
            fd = proc.stdout.fileno()  # type: ignore[union-attr]
            while True:
                remaining = end - time.monotonic()
                if remaining <= 0:
                    proc.kill()
                    return {"status": "error", "error": {
                        "kind": "exception",
                        "message": f"AutoHostError: js host deadline exceeded ({HOST_DEADLINE_S:.0f}s)",
                        "stack": None,
                    }}
                ready, _, _ = select.select([fd], [], [], min(remaining, 1.0))
                if not ready:
                    if proc.poll() is not None:
                        break  # shim died without a done line
                    continue
                line = proc.stdout.readline()  # type: ignore[union-attr]
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                frame = json.loads(line)
                op = frame.get("op") or ""
                if op == "done":
                    raw_outcome = frame.get("outcome")
                    break
                payload = frame.get("payload")
                reply = _op(rt, op, payload)
                proc.stdin.write(json.dumps(reply) + "\n")  # type: ignore[union-attr]
                proc.stdin.flush()  # type: ignore[union-attr]
        finally:
            try:
                proc.stdin.close()  # type: ignore[union-attr]
            except Exception:
                pass
            try:
                proc.wait(timeout=10)
            except Exception:
                proc.kill()
        if raw_outcome is None and proc.returncode not in (0, None):
            stderr.seek(0)
            tail = stderr.read().decode("utf-8", "replace").strip()[-800:]
            raw_outcome = {"status": "error", "error": {
                "kind": "exception",
                "message": f"AutoHostError: js shim exited {proc.returncode}",
                "stack": tail or None,
            }}
    return raw_outcome


def _op(rt: Runtime, op: str, payload: Any) -> dict:
    try:
        result = _serve_op(rt, op, payload)
        return {"ok": True, "result": result}
    except Cancelled as exc:
        return {"ok": False, "error": {"code": "CANCELLED", "message": str(exc) or "run was cancelled",
                                       "retryable": False}}
    except AutoHostError as exc:
        err: dict[str, Any] = {"code": exc.code, "message": str(exc), "retryable": exc.retryable}
        if exc.reason:
            err["reason"] = exc.reason
        if exc.fix_action:
            err["fixAction"] = exc.fix_action
        if exc.details:
            err["details"] = dict(exc.details)
        return {"ok": False, "error": err}
    except Exception as exc:  # a host bug must not kill the shim: typed INTERNAL reply
        return {"ok": False, "error": {"code": "INTERNAL", "message": f"{type(exc).__name__}: {exc}",
                                       "retryable": False}}


def _serve_op(rt: Runtime, op: str, payload: Any) -> Any:
    p = dict(payload or {})
    if op == "call":
        return rt.call(p.get("capability") or "", p.get("method") or "", p.get("args") or {})
    if op == "emit":
        rt.emit(dict(p))
        return None
    if op == "sleep":
        seconds = float(p.get("seconds") or 0.0)
        rt.sleep(min(max(seconds, 0.0), MAX_SLEEP_SECONDS))
        return None
    if op == "ctx":
        what = p.get("what") or ""
        if what == "config":
            return dict(rt.config)
        if what == "profile":
            return dict(rt.profile)
        if what == "run":
            return {"id": rt.run["id"], "attempt": rt.run["attempt"],
                    "trigger": {"type": rt.trigger["type"], "payload": dict(rt.trigger["payload"])},
                    "started_at": rt.run["started_at"]}
        if what == "trigger":
            return {"type": rt.trigger["type"], "payload": dict(rt.trigger["payload"])}
        if what == "state":
            return dict(rt.state)
        raise AutoHostError(f"unknown ctx '{what}'", code="INVALID_ARGS")
    if op == "state_save":
        rt.state = dict(p)
        rt.call("state", "set", {"state": dict(rt.state)})
        return None
    if op == "secret":
        return rt.secret(str(p.get("name") or ""))
    if op == "clock":
        return {"now_iso": rt.now_iso()}
    if op == "result":
        rt.result = Result(str(p.get("code") or "OK"), dict(p.get("data") or {}))
        return None
    if op == "next_run":
        after = p.get("after_minutes")
        at = p.get("at")
        if (after is None) == (at is None):
            raise AutoHostError("give exactly one of after_minutes or at", code="INVALID_ARGS")
        request = {"after_minutes": after, "at": at}
        rt.next_run_request = request
        rt.emit({"type": "next_run", "data": request})
        return None
    if op == "capabilities":
        return dict(rt.transport.describe())
    raise AutoHostError(f"unknown bridge op '{op}'", code="INVALID_ARGS")


# --- classification (mirrors execute_with_runtime branch-for-branch) ------------------------------

def _classify(rt: Runtime, raw: dict | None) -> JsOutcome:
    if raw is None:
        return JsOutcome("FAILED", "EXCEPTION", "the JS host produced no outcome", events=list(rt.emitted))
    error = raw.get("error") or {}
    kind = error.get("kind") or ""
    if raw.get("status") == "ok":
        if rt.cancelled:
            return JsOutcome("CANCELLED", "USER_CANCELLED", "run was cancelled", events=list(rt.emitted))
        value = raw.get("value")
        result = None
        if isinstance(value, dict) and isinstance(value.get("__result__"), dict):
            marked = value["__result__"]
            result = Result(marked.get("code", "OK"), marked.get("data") or {})
        elif rt.result is not None:
            result = rt.result
        else:
            result = Result("OK", {})
        return JsOutcome("SUCCEEDED", result=result, events=list(rt.emitted))
    if kind == "cancelled":
        return JsOutcome("CANCELLED", "USER_CANCELLED", str(error.get("message") or "run was cancelled"),
                         events=list(rt.emitted))
    if kind == "needs_user":
        return JsOutcome("WAITING_USER", error.get("reason") or "NEEDS_USER",
                         str(error.get("message") or ""), events=list(rt.emitted))
    if kind == "entry":
        return JsOutcome("FAILED", "ENTRY_NOT_FOUND",
                         str(error.get("message") or "no main() found in the JS entry"),
                         events=list(rt.emitted))
    if kind == "autohost":
        reason = error.get("reason") or error.get("code") or "INTERNAL"
        return JsOutcome("FAILED", reason, str(error.get("message") or reason), events=list(rt.emitted))
    # exception (script bug) — a traceback rides the outcome like any other runtime
    return JsOutcome("FAILED", "EXCEPTION", str(error.get("message") or "the script threw an error"),
                     events=list(rt.emitted),
                     traceback=(error.get("stack") or None))
