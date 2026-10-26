"""android.*: raw Android bridge (capability android.api.direct, P8-12).

Breaks the sandbox by design — plan §6.1 W2: "Direct Android API access from Python
(jclass, system services). DANGEROUS; needs the android.api.direct grant AND the raw_api
switch (Settings → Developer & security, off by default)".

The gate is a broker handshake: [authorize] asks the host to confirm grant + switch; only then
do the Chaquopy passthroughs work. Without a successful handshake every call raises
CapabilityUnavailable, so scripts fail loudly, never silently (ADR-062).

Device-side mechanics (ADR-062): the worker arms the bridge after a successful handshake by
injecting the Android Context as the module global ``__autohost_context__``; [jclass] rides
Chaquopy's ``java`` module. Both are device-only — in tests/simulator the gate can be proven
but the passthroughs honestly report they need the device worker.
"""
from __future__ import annotations

from typing import Any

from .errors import CapabilityUnavailable
from ._core import current

_AUTHORIZED = False


def authorize() -> dict[str, Any]:
    """Broker handshake: confirm the android.api.direct grant + the raw_api switch.

    Returns the worker marker on success and unlocks [system_service]/[jclass] for this run.
    """
    global _AUTHORIZED
    res = current().call("android.api.direct", "authorize", {})
    _AUTHORIZED = bool(res.get("authorized"))
    return res


def is_authorized() -> bool:
    """Whether the handshake succeeded in this run."""
    return _AUTHORIZED


def _require_gate() -> None:
    if not _AUTHORIZED:
        raise CapabilityUnavailable(
            "raw Android bridge is locked: call android.authorize() first (needs the "
            "android.api.direct grant and the raw_api switch)",
        )


def system_service(name: str) -> Any:
    """Chaquopy passthrough to an Android system service (after authorize)."""
    _require_gate()
    return _context().getSystemService(name)


def jclass(name: str) -> Any:
    """Chaquopy passthrough to a Java class (after authorize)."""
    _require_gate()
    try:
        from java import jclass as _jclass  # type: ignore

        return _jclass(name)
    except Exception as exc:  # pragma: no cover - device-only path
        raise CapabilityUnavailable(
            f"raw bridge jclass needs Chaquopy on the device worker (got: {exc})",
        ) from exc


def _context() -> Any:
    context = globals().get("__autohost_context__")
    if context is None:
        raise CapabilityUnavailable(
            "no Android context in this process: the raw bridge passthroughs run only in "
            "the device worker (ADR-062)",
        )
    return context
