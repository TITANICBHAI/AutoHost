"""SimTransport: the object the SDK talks to. It returns broker envelopes, exactly like the real bridge."""
from __future__ import annotations

from .device import FakeDevice, SimError


class SimTransport:
    def __init__(self, device: FakeDevice) -> None:
        self.device = device

    def call(self, capability: str, method: str, args: dict) -> dict:
        try:
            return {"ok": True, "result": self.device.dispatch(capability, method, args)}
        except SimError as exc:
            err: dict = {"code": exc.code, "message": exc.message}
            if exc.retryable is not None:
                err["retryable"] = exc.retryable
            if exc.fix_action is not None:
                err["fixAction"] = exc.fix_action
            return {"ok": False, "error": err}

    def emit(self, event: dict) -> None:
        self.device.events.append(event)

    def describe(self) -> dict:
        return self.device.describe()
