"""AutoHost Python SDK. Stdlib only. Scripts import what they need: ctx, log, device, sel, flow, ..."""
from __future__ import annotations

from . import (android, app_manage, audio, browser, calendar, camera, clipboard, contacts, devicectl, device,
               errors, events, files, flow, http, intents, location, logcat, media, microphone, net,
               notifications, notify, packs, power, sel, sensors, shell, sms, sockets, tasks, telephony, ui, vision)
from ._core import current
from ._ctx import ctx
from ._log import log

API_LEVEL = 1


def capabilities() -> dict:
    """Live availability per capability on this device: {capability: {available, reason, provider, chain}}."""
    return dict(current().transport.describe())


def prefer(*providers: str) -> None:
    """Ask the broker to prefer these providers (for example "shell") when several can serve a call."""
    current().preferred = list(providers)


__all__ = ["android", "app_manage", "audio", "browser", "calendar", "camera", "clipboard", "contacts", "ctx",
           "devicectl", "device", "errors", "events", "files", "flow", "http",
           "intents", "location", "log", "media", "microphone", "notifications", "notify", "packs", "sel", "sensors",
           "shell", "sms", "sockets", "tasks", "telephony", "ui", "vision",
           "capabilities", "prefer", "API_LEVEL"]
