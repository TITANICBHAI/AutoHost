"""AutoHost SDK exceptions.

Broker codes and retryability come from schemas/errors.json (brokerErrors).
tests/test_errors.py fails if this module drifts from that file.
"""
from __future__ import annotations


class AutoHostError(Exception):
    """Base class. `code` is a broker error code; `retryable` tells the stage/run policy whether a retry may help."""

    code: str = "INTERNAL"
    retryable: bool = False

    def __init__(self, message: str = "", *, code=None, retryable=None, reason=None,
                 fix_action=None, details=None) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = str(code)
        if retryable is not None:
            self.retryable = bool(retryable)
        self.reason = reason
        self.fix_action = fix_action
        self.details = dict(details or {})


class Retryable(AutoHostError):
    """Raised by a script to ask the stage/run policy for a retry."""

    code = "INTERNAL"
    retryable = True


class Fatal(AutoHostError):
    code = "INTERNAL"
    retryable = False


class NeedsUser(AutoHostError):
    """The run becomes WAITING_USER. `reason` is a reason code from errors.json (e.g. SECURITY_CHALLENGE)."""

    code = "NEEDS_USER"
    retryable = False

    def __init__(self, reason: str, message: str, resume_hint: str | None = None) -> None:
        super().__init__(message, reason=reason)
        self.resume_hint = resume_hint


class PermissionDenied(AutoHostError):
    code = "PERMISSION_DENIED"
    retryable = False


class ScopeViolation(AutoHostError):
    code = "SCOPE_VIOLATION"
    retryable = False


class CapabilityUnavailable(AutoHostError):
    code = "CAPABILITY_UNAVAILABLE"
    retryable = False


class Timeout(AutoHostError):
    code = "TIMEOUT"
    retryable = True


class NotFound(AutoHostError):
    code = "NOT_FOUND"
    retryable = False


class StaleNode(AutoHostError):
    code = "STALE_NODE"
    retryable = True


class ObscuredByWindow(AutoHostError):
    code = "OBSCURED_BY_WINDOW"
    retryable = True


class NoActiveWindow(AutoHostError):
    code = "NO_ACTIVE_WINDOW"
    retryable = True


class ScreenshotBlocked(AutoHostError):
    code = "SCREENSHOT_BLOCKED"
    retryable = False


class TextInputRejected(AutoHostError):
    code = "TEXT_INPUT_REJECTED"
    retryable = True


class TextInputUnverified(AutoHostError):
    code = "TEXT_INPUT_UNVERIFIED"
    retryable = False


class InvalidArgs(AutoHostError):
    code = "INVALID_ARGS"
    retryable = False


class RateLimited(AutoHostError):
    code = "RATE_LIMITED"
    retryable = True


class Cancelled(AutoHostError):
    code = "CANCELLED"
    retryable = False


BROKER_CODE_CLASSES: dict[str, type[AutoHostError]] = {
    "PERMISSION_DENIED": PermissionDenied,
    "SCOPE_VIOLATION": ScopeViolation,
    "CAPABILITY_UNAVAILABLE": CapabilityUnavailable,
    "NEEDS_SETUP": CapabilityUnavailable,
    "INSECURE_FEATURE_DISABLED": PermissionDenied,
    "TIMEOUT": Timeout,
    "NOT_FOUND": NotFound,
    "STALE_NODE": StaleNode,
    "OBSCURED_BY_WINDOW": ObscuredByWindow,
    "NO_ACTIVE_WINDOW": NoActiveWindow,
    "SCREENSHOT_BLOCKED": ScreenshotBlocked,
    "PROJECTION_NOT_GRANTED": CapabilityUnavailable,
    "TEXT_INPUT_REJECTED": TextInputRejected,
    "TEXT_INPUT_UNVERIFIED": TextInputUnverified,
    "INVALID_ARGS": InvalidArgs,
    "RATE_LIMITED": RateLimited,
    "CANCELLED": Cancelled,
    "IDLE_TIMEOUT": Fatal,
    "HOST_TOO_OLD": Fatal,
    "TRIGGER_RULE_INVALID": InvalidArgs,
    "INTERNAL": Fatal,
}


def from_broker_error(err: dict) -> AutoHostError:
    """Turn a broker error object into the matching exception. Unknown codes become Fatal."""
    code = str(err.get("code") or "INTERNAL")
    cls = BROKER_CODE_CLASSES.get(code, Fatal)
    return cls(
        str(err.get("message") or code),
        code=code,
        retryable=err.get("retryable"),
        reason=err.get("reason"),
        fix_action=err.get("fixAction"),
        details=err.get("details"),
    )
