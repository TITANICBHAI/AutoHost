"""Test helpers: put the SDK and the repo on sys.path, and build fake devices with a runtime installed."""
import sys
from pathlib import Path

SDK = Path(__file__).resolve().parents[1]
ROOT = Path(__file__).resolve().parents[2]
SAMPLES = ROOT / "samples"
SCHEMAS = ROOT / "schemas"
for p in (SDK, ROOT):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from autohost._core import Runtime, install  # noqa: E402
from autohost_sim import FakeDevice, SimTransport, VirtualClock  # noqa: E402

PRESS_SCREENS = {
    "home": {"package": "com.example.app",
             "nodes": [{"id": "fx_title", "text": "Example app", "cls": "android.widget.TextView"},
                       {"id": "fx_btn_continue", "text": "Continue", "cls": "android.widget.Button", "clickable": True}],
             "on_click": {"fx_btn_continue": "done"}},
    "done": {"package": "com.example.app",
             "nodes": [{"id": "fx_done", "text": "Done", "cls": "android.widget.TextView"}]},
}


def press_device(**kw):
    return FakeDevice(PRESS_SCREENS, launch_map={"com.example.app": "home"}, clock=VirtualClock(), **kw)


def runtime_for(device, *, config=None, profile=None, secrets=None):
    rt = Runtime(SimTransport(device), config=config, profile=profile, profile_id=device.profile_id,
                 secrets=secrets, clock=device.clock)
    return install(rt)
