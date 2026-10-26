"""autohost_sim: a fake device that speaks the broker protocol, so task code runs on a PC without a phone.

It is a test double for the broker, not a model of Android. Real behaviour is verified on hardware only.
"""
from .device import FakeDevice, SimError, VirtualClock
from .runner import Outcome, run_task
from .transport import SimTransport
from .js_host import JsOutcome, run_js_task

__all__ = ["FakeDevice", "SimError", "VirtualClock", "Outcome", "run_task", "SimTransport",
           "JsOutcome", "run_js_task"]
