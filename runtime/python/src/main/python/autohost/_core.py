"""Per-run runtime. The host (or the simulator in tests) installs one Runtime per run."""
from __future__ import annotations

import datetime as _dt
import time as _time
from typing import Any

from .errors import Cancelled, Fatal, InvalidArgs, NotFound

UTC = _dt.timezone.utc
ISO_FMT = "%Y-%m-%dT%H:%M:%SZ"


class SecretValue(str):
    """A value obtained from ctx.secret(). It IS the plaintext (providers need it — e.g. typing
    into a field), but its provenance is tracked so the runtime can refuse to persist it, and
    repr() never echoes it (X-12 / ADR-099)."""

    def __new__(cls, value: str, name: str = "") -> "SecretValue":
        obj = super().__new__(cls, value)
        obj.name = name
        return obj

    def __repr__(self) -> str:
        return "[REDACTED]"


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

        args = dict(args or {})
        self.check_cancel()
        # X-12 (ADR-099): a secret obtained via ctx.secret() MAY ride call args — that is the one
        # legitimate channel to a provider (e.g. typing into a field). An UNTRACKED secret (the
        # registered value inside a plain string) is refused before it reaches the broker.
        self._refuse_untracked_secrets(args, "args")
        envelope = self.transport.call(capability, method, args)
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
        return SecretValue(self._secrets[name], name=name)

    def _secret_violations(self, value, where: str, *, allow_tracked: bool) -> list:
        """Human-readable violations for a value about to leave the worker.

        A SecretValue (from ctx.secret()) counts as a violation wherever the value would be
        persisted or recorded; a plain string containing a registered secret value always does.
        """
        secrets = {s: name for name, s in self._secrets.items() if isinstance(s, str) and s}
        found: list = []

        def walk(v: Any, where: str) -> None:
            if isinstance(v, SecretValue):
                if not allow_tracked:
                    found.append(f"{where}: a ctx.secret() value ('{v.name or 'unnamed'}') must never be "
                                 "persisted or recorded — keep it in call args only")
                return
            if isinstance(v, str):
                for secret, name in secrets.items():
                    if secret in v:
                        found.append(f"{where}: registered secret '{name}' appears inside a plain string — "
                                     "obtain it via ctx.secret() for provider calls, or redact it for logs")
                return
            if isinstance(v, dict):
                for k, x in v.items():
                    walk(x, f"{where}.{k}")
            elif isinstance(v, (list, tuple)):
                for i, x in enumerate(v):
                    walk(x, f"{where}[{i}]")

        walk(value, where)
        return found

    def _refuse_untracked_secrets(self, value, where: str) -> None:
        problems = self._secret_violations(value, where, allow_tracked=True)
        if problems:
            raise InvalidArgs("; ".join(problems))

    def refuse_secrets(self, value, where: str) -> None:
        """Refuse a value heading for a persistence/recording path (kv, state, checkpoint, result)."""
        problems = self._secret_violations(value, where, allow_tracked=False)
        if problems:
            raise InvalidArgs("; ".join(problems))

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
