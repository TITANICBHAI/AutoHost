"""intents.*: start activities and broadcasts behind the grant's allowlist (capability device.intent).

The policy is fail-closed: open_url accepts http/https only; open_settings accepts known page
names; start_activity/broadcast need the action or package in the grant scope. Results are honest
about being unverified — foreground verification is device.app.launch's job.
"""
from __future__ import annotations

from ._core import current

SETTINGS_PAGES = (
    "wifi", "bluetooth", "display", "sound", "applications", "battery", "date", "locale",
    "input", "accessibility", "notification_listener", "exact_alarms", "app_details", "settings",
)


def open_url(url: str) -> dict:
    return current().call("device.intent", "open_url", {"url": url})


def open_settings(page: str) -> dict:
    return current().call("device.intent", "open_settings", {"page": page})


def start_activity(
    action: str | None = None,
    component: str | None = None,
    data: str | None = None,
    extras: dict | None = None,
) -> dict:
    args: dict = {}
    if action is not None:
        args["action"] = action
    if component is not None:
        args["component"] = component
    if data is not None:
        args["data"] = data
    if extras:
        args["extras"] = {str(k): v for k, v in extras.items()}
    return current().call("device.intent", "start_activity", args)


def broadcast(action: str, extras: dict | None = None) -> dict:
    args: dict = {"action": action}
    if extras:
        args["extras"] = {str(k): v for k, v in extras.items()}
    return current().call("device.intent", "broadcast", args)
