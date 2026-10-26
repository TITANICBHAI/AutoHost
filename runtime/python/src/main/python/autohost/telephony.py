"""telephony.*: device.telephony — call state and dialer-owned calls (P10-08).

DANGEROUS; full flavor only; needs the grant AND the `telephony` switch. place_call goes
through ACTION_CALL: the user sees the dialer and can cancel; the provider never claims the
call connected (verified:false always).
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs


def call_state() -> dict[str, Any]:
    """{state: 'idle'|'ringing'|'offhook', number?} — number only when ringing and readable."""
    return current().call("device.telephony", "call_state", {})


def place_call(number: str, *, allow_short: bool = False) -> dict[str, Any]:
    """Open the dialer for number (premium/short-code rails apply). Returns {placed, verified:false}."""
    if not number or not number.strip():
        raise InvalidArgs("number is required")
    return current().call("device.telephony", "place_call", {
        "number": number, "allow_short": bool(allow_short),
    })
