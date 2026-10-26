"""android.*: raw Android bridge. Needs the android.api.direct grant AND the raw_api switch (P8-12)."""
from __future__ import annotations

from typing import Any


def authorize() -> dict[str, Any]:
    """Broker handshake: confirm grant + switch; unlocks system_service/jclass for this run."""
    ...


def is_authorized() -> bool: ...


def system_service(name: str) -> Any: ...


def jclass(name: str) -> Any: ...
