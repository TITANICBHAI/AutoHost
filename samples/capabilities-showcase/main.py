"""Capabilities Showcase — an honest availability sweep (plan §18 P8-15).

Demonstrates every Phase-8 capability with its real result on THIS device: each section reports
what worked, what was refused, and the exact error code the host returned — never a fake success.
Config switches sections on/off; every section is also a copy-pasteable snippet for new tasks.

Requires (all optional in the manifest): the capabilities below. The manifest marks them
optional so the sweep runs everywhere and reports NEEDS_SETUP/refusals honestly instead of
being blocked by the CompatReport.
"""
from autohost import (audio, clipboard, ctx, device, files, log, net, notifications, power,
                      shell, tasks, ui)
from autohost import events as _events
from autohost import intents as _intents
from autohost.errors import AutoHostError


def main():
    results = {}
    sections = {
        "clipboard": sweep_clipboard,
        "intents": sweep_intents,
        "notifications": sweep_notifications,
        "events": sweep_events,
        "audio": sweep_audio,
        "power": sweep_power,
        "screen_control": sweep_screen,
        "settings_read": sweep_settings,
        "files_shared": sweep_files,
        "net_ws": sweep_net_ws,
        "webhooks": sweep_webhooks,
        "chaining": sweep_chaining,
        "shell_tier": sweep_shell_tier,
    }
    configured = ctx.config.get("sections", [])
    enabled = set(configured) if configured else set(sections)
    for name, sweep in sections.items():
        if name not in enabled:
            continue
        log.info("showcase section", section=name)
        try:
            results[name] = sweep()
        except AutoHostError as exc:
            results[name] = {"ok": False, "code": exc.code, "message": str(exc)}
        except Exception as exc:  # unexpected → honest INTERNAL-style row
            results[name] = {"ok": False, "code": "INTERNAL", "message": repr(exc)}
    ctx.result("showcase_complete", results)


def sweep_clipboard():
    clipboard.set("AutoHost showcase")
    return {"ok": True, "read_back": clipboard.get()["text"], "note": "background reads need the IME path"}


def sweep_intents():
    rows = []
    for page in ("wifi", "volume"):
        try:
            dispatched = _intents.open_settings(page)["dispatched"]
            rows.append({"page": page, "dispatched": dispatched})
        except AutoHostError as exc:
            rows.append({"page": page, "code": exc.code})
    return {"ok": True, "pages": rows}


def sweep_notifications():
    rows = notifications.list()
    return {"ok": True, "visible": len(rows), "note": "enable AutoHost in notification-listener settings"}


def sweep_events():
    recent = _events.recent(limit=5)
    return {"ok": True, "recent": [row["kind"] for row in recent]}


def sweep_audio():
    vol = audio.volume("music")
    audio.play_sound("beep")
    return {"ok": True, "music_percent": vol["percent"], "beeped": True}


def sweep_power():
    state = power.battery()
    held = power.wake_lock(reason="showcase")
    power.wake_lock(hold=False)
    return {"ok": True, "battery": state, "wake_lock_roundtrip": held["held"]}


def sweep_screen():
    level = device.screen.brightness()["level"]
    wake = device.screen.wake()
    return {"ok": True, "brightness": level, "wake_strategy": wake.get("strategy")}


def sweep_settings():
    row = device.settings_read("global", "device_provisioned")
    return {"ok": True, "exists": row["exists"], "value": row.get("value")}


def sweep_files():
    """SAF section: needs a picked folder in the grant; MediaStore needs the storage permission."""
    tree = ctx.config.get("tree_uri", "")
    if not tree:
        return {"ok": True, "skipped": "set config tree_uri (or use Fix \u2192 PICK_FOLDER) to sweep SAF"}
    files.shared.write(tree, "showcase.txt", f"AutoHost {ctx.now()}")
    return {"ok": True, "content": files.shared.read(tree, "showcase.txt")}


def sweep_net_ws():
    url = ctx.config.get("ws_url", "")
    if not url:
        return {"ok": True, "skipped": "set config ws_url to sweep net.ws"}
    handle = net.ws.connect(url, timeout_ms=5000)
    net.ws.send(handle, "autohost showcase")
    frame = net.ws.recv(handle, timeout_ms=5000)
    net.ws.close(handle)
    return {"ok": True, "frame_kind": frame["kind"]}


def sweep_webhooks():
    status = net.webhook_status()
    return {"ok": True, "running": status["running"], "port": status["port"], "hooks": net.webhook_names()}


def sweep_chaining():
    target = ctx.config.get("chain_target", "")
    if not target:
        return {"ok": True, "skipped": "set config chain_target to sweep tasks.run"}
    outcome = tasks.run(target, timeout_ms=60000)
    return {"ok": True, "status": outcome["status"], "run_id": outcome["run_id"]}


def sweep_shell_tier():
    """P9 shell tier: needs the shell switch AND a non-empty allowlist in the grant."""
    rows = shell.transports()["transports"]
    states = {row["id"]: row["state"] for row in rows}
    command = ctx.config.get("shell_command", "")
    if not command:
        return {"ok": True, "transports": states, "skipped": "set config shell_command to run one"}
    result = shell.run(command, timeout_ms=15000)
    return {"ok": True, "transports": states, "exit_code": result.get("exit_code"),
            "mode": result["mode"], "transport": result["transport"],
            "stdout_head": result["stdout"][:80]}
