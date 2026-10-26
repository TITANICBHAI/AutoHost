"""Per-run runtime. The host (or the simulator in tests) installs one Runtime per run."""
from __future__ import annotations

import datetime as _dt
import time as _time
from typing import Any

from .errors import Cancelled, Fatal, NotFound

UTC = _dt.timezone.utc
ISO_FMT = "%Y-%m-%dT%H:%M:%SZ"


class SystemClock:
    def now(self) -> _dt.datetime:
        return _dt.datetime.now(UTC)

    def sleep(self, seconds: float) -> None:
        _time.sleep(max(0.0, float(seconds)))


class Runtime:
    def __init__(self, transport: Any, *, task_id: str = "unknown", config=None, profile=None,
                 profile_id=None, secrets=None, run_id: str = "run-1", attempt: int = 1,
                 trigger_type: str = "manual", trigger_payload=None, clock=None, state=None) -> None:
        self.transport = transport
        self.task_id = task_id
        self.config = dict(config or {})
        self.profile = dict(profile or {})
        self.profile_id = profile_id
        self._secrets = dict(secrets or {})
        self.clock = clock or SystemClock()
        self.cancelled = False
        self.paused = False
        self.state = dict(state or {})
        self.result = None
        self.next_run_request = None
        self.stages: list = []
        self.preferred: list = []
        self.emitted: list = []
        self.run = {"id": run_id, "attempt": attempt, "trigger": trigger_type, "started_at": self.now_iso()}
        self.trigger = {"type": trigger_type, "payload": dict(trigger_payload or {})}

    # time and cancellation -------------------------------------------------
    def now_iso(self) -> str:
        return self.clock.now().strftime(ISO_FMT)

    def pause(self) -> None:
        self.paused = True

    def resume(self) -> None:
        self.paused = False

    def check_cancel(self) -> None:
        # Blocking while paused is the pause mechanism: every SDK call and sleep re-enters here, so a paused
        # run simply stops progressing until resumed. Cancellation always wins over pausing.
        while self.paused and not self.cancelled:
            self.clock.sleep(0.05)
        if self.cancelled:
            raise Cancelled("run was cancelled")

    def sleep(self, seconds: float) -> None:
        self.check_cancel()
        self.clock.sleep(seconds)
        self.check_cancel()

    # broker calls ------------------------------------------------------------
    def call(self, capability: str, method: str, args=None) -> dict:
        from ._envelope import unwrap  # local import keeps the module graph acyclic

        self.check_cancel()
        envelope = self.transport.call(capability, method, dict(args or {}))
        result = unwrap(envelope)
        return result if isinstance(result, dict) else {}

    def emit(self, event: dict) -> None:
        clean = self.redact(dict(event))
        self.emitted.append(clean)
        emit = getattr(self.transport, "emit", None)
        if callable(emit):
            emit(clean)

    # secrets -------------------------------------------------------------------
    def secret(self, name: str) -> str:
        if name not in self._secrets:
            raise NotFound(f"secret '{name}' is not declared for this profile", fix_action="OPEN_PERMISSION_REVIEW")
        return self._secrets[name]

    def redact(self, value: Any) -> Any:
        secrets = [s for s in self._secrets.values() if isinstance(s, str) and s]

        def walk(v: Any) -> Any:
            if isinstance(v, str):
                for s in secrets:
                    v = v.replace(s, "[REDACTED]")
                return v
            if isinstance(v, dict):
                return {k: walk(x) for k, x in v.items()}
            if isinstance(v, list):
                return [walk(x) for x in v]
            return v

        return walk(value)


_current: Runtime | None = None


def install(rt: Runtime) -> Runtime:
    global _current
    _current = rt
    return rt


def uninstall() -> None:
    global _current
    _current = None


def current() -> Runtime:
    if _current is None:
        raise Fatal("no run is active; SDK calls only work inside a run")
    return _current
