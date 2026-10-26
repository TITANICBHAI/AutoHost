"""sms.*: device.sms — the inbox, budget-railed sends and wait_for (P10-08).

DANGEROUS by design: needs the device.sms grant AND the `sms` switch (Settings → Developer &
security). Premium destinations and short codes are refused; at most 10 sends per run; delivery
receipts are never claimed (delivered is always false — the platform does not let us verify it).
`full` flavor only (core cannot hold SMS permissions at all).
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_BODY = 480
MAX_SENDS_PER_RUN = 10
MAX_WAIT_MS = 300_000


def read_inbox(filter: str | None = None, limit: int | None = None) -> dict[str, Any]:
    """Newest inbox messages, optionally body-filtered, scoped to the grant's senders list."""
    args: dict[str, Any] = {}
    if filter is not None:
        args["filter"] = filter
    if limit is not None:
        args["limit"] = int(limit)
    return current().call("device.sms", "read_inbox", args)


def send(address: str, body: str, *, allow_short: bool = False) -> dict[str, Any]:
    """Send one SMS (≤480 chars, ≤3 parts). Returns {sent, delivered:false, error?}."""
    if not address or not address.strip():
        raise InvalidArgs("address is required")
    if not body:
        raise InvalidArgs("body is required")
    if len(body) > MAX_BODY:
        raise InvalidArgs(f"body exceeds {MAX_BODY} characters (send parts explicitly instead)")
    return current().call("device.sms", "send", {
        "address": address, "body": body, "allow_short": bool(allow_short),
    })


def wait_for(contains: str | None = None, *, from_: str | None = None,
             timeout_ms: int = 30_000) -> dict[str, Any]:
    """Poll the inbox for a matching message. Returns {sender, body, received_ms}."""
    if not 1_000 <= int(timeout_ms) <= MAX_WAIT_MS:
        raise InvalidArgs(f"timeout_ms must be 1_000..{MAX_WAIT_MS}")
    args: dict[str, Any] = {"timeout_ms": int(timeout_ms)}
    if contains is not None:
        args["contains"] = contains
    if from_ is not None:
        args["from"] = from_
    return current().call("device.sms", "wait_for", args)
