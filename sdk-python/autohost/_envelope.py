"""Broker envelope handling: {"ok": true, "result": ...} or {"ok": false, "error": {...}}."""
from __future__ import annotations

from typing import Any

from .errors import Fatal, from_broker_error


def unwrap(envelope: Any) -> Any:
    if not isinstance(envelope, dict) or "ok" not in envelope:
        raise Fatal("malformed broker envelope")
    if envelope["ok"] is True:
        return envelope.get("result")
    raise from_broker_error(envelope.get("error") or {})
