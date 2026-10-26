"""audio.*: device.audio — volume, ringer, DND status (honest), TTS, vibrate, flashlight (P8-09)."""
from __future__ import annotations

from typing import Any

from .errors import InvalidArgs
from ._core import current

STREAMS = ("music", "ring", "notification", "alarm")
RINGER_MODES = ("normal", "silent", "vibrate")
TONES = ("beep", "confirm", "alert")


def volume(stream: str = "music", level: int | None = None) -> dict[str, Any]:
    """Read (or set with level 0-100) a stream volume."""
    if stream not in STREAMS:
        raise InvalidArgs("stream must be one of music|ring|notification|alarm")
    if level is not None and not 0 <= int(level) <= 100:
        raise InvalidArgs("level must be 0-100")
    return current().call("device.audio", "volume", {"stream": stream, "level": level})


def ringer(mode: str | None = None) -> dict[str, Any]:
    """Ringer mode read; set to normal/silent/vibrate where the device allows."""
    if mode is not None and mode not in RINGER_MODES:
        raise InvalidArgs("mode must be normal|silent|vibrate")
    return current().call("device.audio", "ringer", {"mode": mode})


def dnd() -> dict[str, Any]:
    """Do-not-disturb state (read-only in v1; changes need notification-policy access)."""
    return current().call("device.audio", "dnd", {})


def speak(text: str, language: str | None = None, queue: bool = False) -> dict[str, Any]:
    """TTS the text (default engine, best effort; the result is queued, not verified)."""
    if not text:
        raise InvalidArgs("text is required")
    return current().call("device.audio", "speak", {"text": text, "language": language, "queue": bool(queue)})


def vibrate(ms: int = 200) -> dict[str, Any]:
    """Vibrate for a duration (0-10000 ms)."""
    if not 0 <= int(ms) <= 10_000:
        raise InvalidArgs("ms must be 0-10000")
    return current().call("device.audio", "vibrate", {"ms": int(ms)})


def flashlight(on: bool) -> dict[str, Any]:
    """Torch on/off; raises CAPABILITY_UNAVAILABLE when the camera is in use."""
    return current().call("device.audio", "flashlight", {"on": bool(on)})


def play_sound(tone: str = "beep") -> dict[str, Any]:
    """Play a short built-in tone (best effort)."""
    if tone not in TONES:
        raise InvalidArgs("tone must be beep|confirm|alert")
    return current().call("device.audio", "play_sound", {"tone": tone})
