"""AutoHost Python SDK — public surface (contract; implement in sdk-python/autohost/*.py)."""
from typing import Any, Callable, Mapping, TypedDict
from . import errors as errors, sel as sel, flow as flow, device as device, ui as ui, notify as notify
from . import http as http, files as files, android as android
from .errors import (AutoHostError as AutoHostError, NeedsUser as NeedsUser, Retryable as Retryable, Fatal as Fatal,
    CapabilityUnavailable as CapabilityUnavailable, PermissionDenied as PermissionDenied, ScopeViolation as ScopeViolation,
    Cancelled as Cancelled, Timeout as Timeout, NotFound as NotFound, StaleNode as StaleNode,
    ObscuredByWindow as ObscuredByWindow, InvalidArgs as InvalidArgs, ScreenshotBlocked as ScreenshotBlocked,
    RateLimited as RateLimited, TextInputRejected as TextInputRejected)

API_LEVEL: int

class CapabilityStatus(TypedDict):
    available: bool
    reason: str | None          # reason code from errors.json when unavailable
    provider: str | None        # active provider id
    chain: list[str]            # ordered provider chain

def capabilities() -> dict[str, CapabilityStatus]: ...
def prefer(*providers: str) -> None: ...

class Run:
    id: str
    attempt: int
    trigger: str                # manual | schedule | event | external | chain | surface | script
    started_at: str             # ISO-8601 UTC

class Trigger:
    type: str
    payload: dict[str, Any]     # never contains secrets

class KV:
    def __getitem__(self, key: str) -> Any: ...
    def __setitem__(self, key: str, value: Any) -> None: ...
    def get(self, key: str, default: Any = None) -> Any: ...
    def delete(self, key: str) -> None: ...
    def keys(self, prefix: str = "") -> list[str]: ...

class Result:
    code: str
    data: dict[str, Any]

class Evidence:
    def screenshot(self, tag: str) -> str | None: ...        # path, or None if unavailable
    def ui_dump(self, tag: str) -> str: ...                  # compact dump saved as evidence
    def note(self, tag: str, text: str) -> None: ...

class Ctx:
    config: Mapping[str, Any]           # task-wide settings (read-only)
    profile: Mapping[str, Any]          # active profile, non-secret fields (read-only)
    run: Run
    trigger: Trigger
    state: dict[str, Any]               # checkpointed by the host after each stage / save()
    profile_store: KV                   # persistent per profile (non-secret)
    task_store: KV                      # persistent per task (non-secret)
    evidence: Evidence
    def secret(self, name: str) -> str: ...                 # only declared x-secret fields; never log
    def result(self, code: str, data: Mapping[str, Any] | None = None) -> Result: ...
    def progress(self, fraction: float, message: str = "") -> None: ...
    def now(self) -> str: ...                                # ISO-8601 UTC
    def minutes_since(self, iso: str) -> float: ...
    def sleep(self, seconds: float) -> None: ...             # cancellable
    def next_run(self, after_minutes: int | None = None, at: str | None = None) -> None: ...
    def save(self) -> None: ...
    def once(self, name: str, fn: Callable[[], Any]) -> Any: ... # run fn only if not completed in this run's checkpoint

ctx: Ctx

class _Log:
    def debug(self, msg: str, **data: Any) -> None: ...
    def info(self, msg: str, **data: Any) -> None: ...
    def warn(self, msg: str, **data: Any) -> None: ...
    def error(self, msg: str, **data: Any) -> None: ...
log: _Log
