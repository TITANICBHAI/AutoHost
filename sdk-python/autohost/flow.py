"""flow: optional toolkit for robust multi-step UI scripts (Screen signatures, transitions, retries, stages)."""
from __future__ import annotations

import functools
from dataclasses import dataclass
from typing import Any, Callable, Literal

from . import device as _device
from ._core import current
from ._ctx import ctx, evidence
from .errors import AutoHostError, Cancelled, InvalidArgs, NeedsUser, Timeout
from .sel import Selector

UNKNOWN: str = "UNKNOWN"


@dataclass(frozen=True)
class RetryPolicy:
    """Declarative retry policy for flow.retry(). Cancellation never retries; a step that raises a
    non-retryable error stops immediately even when attempts remain."""

    times: int = 2
    backoff_ms: int = 500
    backoff_factor: float = 1.0
    on: tuple[type[BaseException], ...] = (AutoHostError,)
    give_up_on: tuple[type[BaseException], ...] = ()


def _norm(value: Any) -> str:
    text = str(value).replace("\u2019", "'").replace("\u2018", "'")
    return " ".join(text.casefold().split())


class Screen:
    """A named screen signature. Matching is substring-based and case-insensitive."""

    def __init__(self, name: str, *, all_text=(), any_text=(), none_text=(), ids=(), desc=(),
                 package: str | None = None, priority: int = 0) -> None:
        self.name = name
        self.all_text = [_norm(x) for x in all_text]
        self.any_text = [_norm(x) for x in any_text]
        self.none_text = [_norm(x) for x in none_text]
        self.ids = list(ids)
        self.desc = [_norm(x) for x in desc]
        self.package = package
        self.priority = int(priority)

    def matches(self, snapshot: dict) -> bool:
        nodes = snapshot.get("nodes") or []
        haystack = [_norm(n["text"]) for n in nodes if n.get("text")]
        haystack += [_norm(n["desc"]) for n in nodes if n.get("desc")]
        ids = {n.get("id") for n in nodes if n.get("id")}

        def present(label: str) -> bool:
            return any(label in h for h in haystack)

        if self.package is not None and snapshot.get("package") != self.package:
            return False
        if not all(present(t) for t in self.all_text):
            return False
        if self.any_text and not any(present(t) for t in self.any_text):
            return False
        if any(present(t) for t in self.none_text):
            return False
        if not all(i in ids for i in self.ids):
            return False
        return all(present(d) for d in self.desc)


def classify_snapshot(screens: list[Screen], snapshot: dict) -> str:
    """Return the matching screen's name, or UNKNOWN when nothing or more than one top-priority screen matches."""
    matches = [s for s in screens if s.matches(snapshot)]
    if not matches:
        return UNKNOWN
    top = max(s.priority for s in matches)
    best = [s for s in matches if s.priority == top]
    return best[0].name if len(best) == 1 else UNKNOWN


def classify(screens: list[Screen]) -> str:
    return classify_snapshot(screens, _device.ui.snapshot(compact=False))


def _reached(expect: Screen | Selector) -> bool:
    if isinstance(expect, Screen):
        return expect.matches(_device.ui.snapshot(compact=False))
    if isinstance(expect, Selector):
        return bool(_device.ui.find(expect, limit=1))
    raise InvalidArgs("expect must be a Screen or a Selector")


def transition(action: Callable[[], Any], expect: Screen | Selector, timeout_ms: int = 10000,
               unknown: Literal["fail", "manual"] = "fail", evidence_on_fail: bool = True) -> None:
    """Run action, then require the expected state. An unexpected state never counts as success."""
    rt = current()
    action()
    for _ in range(max(1, int(timeout_ms) // 500)):
        if _reached(expect):
            return
        rt.sleep(0.5)
    if evidence_on_fail:
        evidence.ui_dump("transition_not_reached")
    message = "expected state was not reached after the action"
    if unknown == "manual":
        raise NeedsUser("UNKNOWN_SCREEN", message, resume_hint="take over, then continue")
    raise Timeout(message, reason="UNKNOWN_SCREEN")


def retry(times: int = 2, backoff_ms: int = 500, on: tuple = (AutoHostError,), policy: RetryPolicy | None = None) -> Callable:
    """Retry a function on retryable errors. Cancellation and non-retryable errors are never retried.

    Pass a [RetryPolicy] for the declarative form (factor backoff, give-up exceptions); the plain keyword
    form stays supported for simple scripts."""

    def decorate(fn: Callable) -> Callable:
        cfg = policy or RetryPolicy(times=times, backoff_ms=backoff_ms, on=on)

        @functools.wraps(fn)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            rt = current()
            backoff = float(cfg.backoff_ms)
            attempt = 0
            while True:
                try:
                    return fn(*args, **kwargs)
                except (Cancelled,) + cfg.give_up_on:
                    raise
                except cfg.on as exc:
                    if not getattr(exc, "retryable", True) or attempt >= cfg.times:
                        raise
                    rt.sleep(backoff / 1000.0)
                    backoff *= cfg.backoff_factor
                    attempt += 1

        return wrapper

    return decorate


def stage(name: str, retries: int = 0, on_resume: Literal["verify", "redo", "manual"] = "verify",
          verify: Callable[[], bool] | None = None) -> Callable:
    """Register a stage (plan §12.2). run_stages() runs registered stages in order.

    Every stage gets a checkpoint record ``{"status", "attempts", "started_at", "ended_at", "error"}``
    persisted atomically with the run state. Completed stages never repeat on a resumed attempt.
    A stage found *in progress* (interrupted mid-run) follows ``on_resume``:

    - ``verify`` — run the task-provided ``verify()`` postcondition; success marks the stage done,
      failure (or a missing check) honestly redoes the body.
    - ``redo``   — always run the body again.
    - ``manual`` — raise NeedsUser so the run pauses as WAITING_USER for the person to resolve.
    """

    def decorate(fn: Callable) -> Callable:
        body = retry(times=retries)(fn) if retries else fn
        current().stages.append({"name": name, "fn": body, "on_resume": on_resume, "verify": verify})
        return fn

    return decorate


def run_stages() -> None:
    rt = current()
    for entry in list(rt.stages):
        name = entry["name"]
        key = f"stage:{name}"
        prev = rt.state.get(key)
        if isinstance(prev, dict) and prev.get("status") == "done":
            continue  # completed stages never repeat (plan §12.3)
        if isinstance(prev, dict) and prev.get("status") in ("running", "failed"):
            policy = entry.get("on_resume") or "verify"
            if policy == "manual":
                raise NeedsUser(
                    "STAGE_UNVERIFIED",
                    f"stage '{name}' did not complete in the previous attempt; resolve it and resume",
                    resume_hint="finish or undo the stage manually, then choose Resume",
                )
            if policy == "verify" and entry.get("verify") is not None:
                if _holds(entry["verify"]):
                    _complete_stage(rt, key, name, int(prev.get("attempts", 0)) + 1, verified=True)
                    continue
                # verification failed → honest redo, the body runs again below
        _run_stage(rt, entry, key, name, prev)


def _holds(check: Callable[[], bool]) -> bool:
    try:
        return bool(check())
    except Exception:
        return False


def _run_stage(rt: Any, entry: dict, key: str, name: str, prev: Any) -> None:
    attempts = (int(prev.get("attempts", 0)) if isinstance(prev, dict) else 0) + 1
    record = {"status": "running", "attempts": attempts, "started_at": rt.now_iso(),
              "ended_at": None, "error": None}
    rt.state[key] = record
    ctx.save()  # the in-progress marker is durable before the body starts
    rt.emit({"type": "stage_begin", "stage": name, "message": f"stage {name} started",
             "data": {"attempt": attempts}})
    try:
        entry["fn"]()
    except Exception as exc:
        failed = dict(record, status="failed", ended_at=rt.now_iso(),
                      error=f"{type(exc).__name__}: {exc}")
        rt.state[key] = failed
        ctx.save()
        rt.emit({"type": "stage_end", "stage": name, "level": "error",
                 "message": f"stage {name} failed", "data": {"attempt": attempts, "ok": False,
                                                             "error": failed["error"]}})
        raise
    _complete_stage(rt, key, name, attempts, verified=False)


def _complete_stage(rt: Any, key: str, name: str, attempts: int, *, verified: bool) -> None:
    prev = rt.state.get(key)
    base = dict(prev) if isinstance(prev, dict) else {}
    rt.state[key] = dict(base, status="done", attempts=attempts, ended_at=rt.now_iso(), error=None)
    ctx.save()
    rt.emit({"type": "stage_end", "stage": name, "message": f"stage {name} done",
             "data": {"attempt": attempts, "ok": True, "verified": verified}})
