"""FakeDevice: a scripted screen graph behind the broker capability surface."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from .match import select

def base64_decode(value: str) -> bytes:
    import base64
    try:
        return base64.b64decode(value)
    except Exception:
        return b""

UTC = timezone.utc


class VirtualClock:
    """Time only moves when something sleeps. on_tick runs after every advance (used to inject cancellation)."""

    def __init__(self, start: datetime | None = None, on_tick: Callable | None = None) -> None:
        self._now = start or datetime(2026, 10, 23, 12, 0, 0, tzinfo=UTC)
        self.on_tick = on_tick
        self.slept = 0.0

    def now(self) -> datetime:
        return self._now

    def sleep(self, seconds: float) -> None:
        self._now += timedelta(seconds=float(seconds))
        self.slept += float(seconds)
        if self.on_tick:
            self.on_tick(self)


class SimError(Exception):
    def __init__(self, code: str, message: str = "", retryable: bool | None = None,
                 fix_action: str | None = None) -> None:
        super().__init__(message or code)
        self.code = code
        self.message = message or code
        self.retryable = retryable
        self.fix_action = fix_action


_CAPABILITIES = ["device.ui", "device.app", "device.screenshot", "device.screen", "device.ime", "net.http",
                 "notify.post", "ui.prompt", "store", "state", "files.private", "system",
                 "device.notifications", "device.clipboard", "device.intent", "device.events"]


def _normalize(spec: dict) -> dict:
    return {
        "package": spec.get("package"),
        "nodes": [dict(n) for n in spec.get("nodes", [])],
        "hidden_nodes": [dict(n) for n in spec.get("hidden_nodes", [])],
        "reveal_after": spec.get("reveal_after", float("inf")),
        "on_click": dict(spec.get("on_click", {})),
    }


class FakeDevice:
    def __init__(self, screens: dict[str, dict], *, launch_map: dict[str, str], start: str | None = None,
                 clock: VirtualClock | None = None, http_routes: dict | None = None,
                 prompt_answers: dict | None = None, screenshot: bool = True,
                 installed: list[str] | None = None,
                 installed_packs: dict[str, dict] | None = None) -> None:
        self.screens = {name: _normalize(spec) for name, spec in screens.items()}
        self.launch_map = dict(launch_map)
        self.current: str | None = start
        self.history: list[str] = []
        self.swipes = 0
        self.clock = clock or VirtualClock()
        self.events: list[dict] = []
        self.kv: dict[tuple, Any] = {}
        self.state_blob: dict = {}
        self.notifications: list[dict] = []
        self.http_routes = dict(http_routes or {})
        self.prompt_answers = dict(prompt_answers or {})
        self.prompts_asked: list[dict] = []
        self.screenshot_enabled = screenshot
        self.installed = set(installed or launch_map.keys())
        # P11-04 (ADR-080): installed app packs — pack id → the AppPacks.Pack payload the host
        # would resolve at run start. The runner injects the referenced ones into config.packs.
        self.installed_packs: dict[str, dict] = dict(installed_packs or {})
        self.files: dict[str, Any] = {}
        # P8 part 2: granted SAF trees {tree_uri: {relpath: bytes-or-str}} + media rows.
        self.trees: dict[str, dict] = {}
        self.media_rows: list[dict] = []
        self.media_permission = True
        self.clipboard: str | None = None
        self.screenshots: list[dict] = []
        self.calls: list[tuple] = []
        self.profile_id = "p1"
        # P8 part 1: notifications buffer, hub event ring, focus flag, dispatched intents.
        self.system_notifications: list[dict] = []
        self.hub_events: list[dict] = []
        self.clipboard_focused = True
        self.intents_dispatched: list[dict] = []
        # P8 part 2 state: media scope flag, ws endpoints/records, audio/power/overlay/settings,
        # chain results for tasks.run, raw-bridge gate flags.
        self.media_scope = True
        self.ws_endpoints: dict[str, list] = {}
        self.ws_sent: list[dict] = []
        self.ws_scope_domains: list[str] | None = None
        self.audio_state = {"music": (8, 15, False), "ring": (5, 8, False),
                            "notification": (5, 8, False), "alarm": (6, 8, False)}
        self.ringer_mode = "normal"
        self.dnd_on = False
        self.tts_log: list[dict] = []
        self.vibrate_log: list[int] = []
        self.torch_on = False
        self.torch_available = True
        self.played_tones: list[str] = []
        self.battery_state = {"level": 80, "charging": False, "plugged": "none", "health": "good"}
        self.wake_lock_held = False
        self.keep_awake = False
        self.a11y_overlay_connected = True
        self.overlays: list[dict] = []
        self.settings_table: dict = {}
        self.chain_results: dict[str, dict] = {}
        self.raw_api_switch = False
        self.raw_bridge_authorized = False
        self.hook_names: list[str] = []
        # P8-10 screen control state.
        self.screen_on = True
        self.keyguard_secured = False
        self.write_settings_granted = False
        self.brightness_level = 60
        # P9 shell tier state: None = no device.shell grant; list = allowlist patterns.
        self.shell_allowlist: list[str] | None = None
        self.shell_connected = False
        self.shell_awaiting_authorization = False
        self.shell_commands: list[str] = []
        # P9 part 2 state: capability grants + insecure-feature switches (two-level gate story),
        # provider call records, injected logcat lines, radio/display state, settings write path.
        self.grants: set[str] = set()
        self.switches: set[str] = set()
        self.app_manage_calls: list[dict] = []
        self.logcat_lines: list[dict] = []
        self.logcat_cleared = 0
        self.device_control_calls: list[dict] = []
        self.radio_state: dict[str, bool] = {"wifi": True, "bluetooth": True, "mobile_data": True, "airplane": False}
        self.display_state: dict[str, str] = {"night": "auto", "orientation": "auto", "font_scale": "1.0"}
        self.mock_location_appop: str = "deny"
        self.settings_write_path = "write_secure"
        self.settings_protected_write_blocked = 0
        # P10 part 1: browser tier (webview pages/a11y link/CDP draft) + vision engines.
        self.web_pages: dict[str, dict] = {}          # page_id -> page record
        self.web_pages_opened: list[dict] = []        # every open/navigate attempt (url, profile)
        self.web_cookies: dict[str, dict[str, str]] = {}  # profile -> {name: value}
        self.web_clicks: list[tuple[str, str]] = []   # (page_id, selector)
        self.web_typed: list[dict] = []               # {page_id, selector} (text never recorded)
        self.cookies_cleared: list[str] = []
        self.a11y_browser: dict | None = None         # {package, lines, challenge}
        self.cdp_state: dict | None = None            # {evaluate_value, cookies}
        self.cdp_closed = 0
        self.ocr_lines: list[dict] = []
        self.qr_value: str | None = None
        self.template_result: dict | None = None      # canned {found, score, box}
        self._web_registry: dict[str, dict] = {}      # url -> page definition (add_web_page)
        self._web_page_seq = 0                        # monotonic page-id allocator (ULID on device)
        # P10 part 2: personal data, hardware, sms/telephony, net riders.
        self.contacts_rows: list[dict] = []           # {name, phone, email}
        self.calendar_events: list[dict] = []         # {title, start_ms, end_ms, location, description}
        self.media_rows: list[dict] = []              # {id, kind, display_name, size_bytes, added_ms}
        self.contacts_inserted = 0
        self.calendar_inserted = 0
        self.media_deleted: list[int] = []
        self.loc_fix: dict | None = None              # canned fix
        self.loc_background = True
        self.captures: list[dict] = []                # camera calls
        self.recordings: list[dict] = []              # mic calls
        self.sensor_list: list[dict] = []             # {name, vendor, type}
        self.sensor_samples: dict[str, list[float]] = {}
        self.sms_inbox: list[dict] = []               # {sender, body, received_ms, read}
        self.sms_sent: list[dict] = []                # {address, body}
        self.sms_sends = 0
        self.call_state_state = "idle"                # idle|ringing|offhook
        self.calls_placed: list[str] = []
        self.tcp_connections: list[dict] = []         # {host, port}
        self.socket_domains: list[str] | None = None  # None = unrestricted net.socket grant
        self.tcp_recv_data: bytes | None = None
        self.udp_sends: list[dict] = []
        self.udp_recv_canned: bytes | None = None
        self.reverse_opened: list[dict] = []          # {url} (token never stored)
        self.browser_domains: list[str] | None = None # None = unrestricted grant; [] = deny-all

    # ---- P9 part 2 test helpers ----------------------------------------------
    def add_grants(self, *grants: str, switches: tuple[str, ...] = ()) -> None:
        """Grants capabilities (and optionally flips insecure-feature switches) for the run."""
        self.grants.update(grants)
        self.switches.update(switches)

    def add_logcat(self, lines: list[dict]) -> None:
        """Test helper: seeds synthetic logcat entries {ts?, level, tag, pid?, message}."""
        self.logcat_lines.extend(dict(line) for line in lines)

    # ---- P10 part 1 helpers ---------------------------------------------------

    def add_browser_grant(self, domains: list[str] | None) -> None:
        """Grant browser.open/browser.read with a domains scope (None = unrestricted object)."""
        self.grants.update({"browser.open", "browser.read"})
        self.browser_domains = domains

    def add_web_page(self, url: str, *, title: str = "", body_text: str = "",
                     challenge: dict | None = None, values: dict[str, list[str]] | None = None,
                     cookies: dict[str, str] | None = None) -> None:
        """Register a simulated page: exact-URL match settles open/navigate.

        challenge = {"kind": "captcha", "matched": "url:/recaptcha/"} rides the result;
        values maps CSS selector -> extracted values; cookies seed the profile jar.
        """
        self._web_registry[url] = {
            "title": title, "body_text": body_text, "challenge": challenge,
            "values": dict(values or {}), "cookies": dict(cookies or {}),
        }

    def add_a11y_browser(self, package: str = "com.android.chrome", lines: list[str] | None = None,
                         challenge: dict | None = None) -> None:
        """Simulate the user's browser foreground for the browser.a11y link."""
        self.a11y_browser = {"package": package, "lines": list(lines or []), "challenge": challenge}

    def add_cdp(self, *, evaluate_value: Any = None, cookies: list[dict] | None = None) -> None:
        """Bring the draft CDP route up (shell_live=True) with canned results."""
        self.cdp_state = {"evaluate_value": evaluate_value, "cookies": list(cookies or [])}

    def add_ocr(self, lines: list[str]) -> None:
        self.ocr_lines = [{"text": line} for line in lines]

    def add_qr(self, value: str | None) -> None:
        self.qr_value = value

    def set_template_result(self, *, found: bool, score: float, box: tuple[int, int, int, int]) -> None:
        self.template_result = {"found": found, "score": score, "box": dict(zip(("left", "top", "width", "height"), box))}

    # ---- P10 part 2 helpers -----------------------------------------------------

    def add_contacts(self, rows: list[dict]) -> None:
        self.contacts_rows.extend(dict(row) for row in rows)

    def add_events(self, events: list[dict]) -> None:
        self.calendar_events.extend(dict(event) for event in events)

    def add_media(self, rows: list[dict]) -> None:
        self.media_rows.extend(dict(row) for row in rows)

    def set_location(self, fix: dict | None, *, background: bool = True) -> None:
        self.loc_fix = fix
        self.loc_background = background

    def add_sensors(self, sensors: list[dict], samples: dict[str, list[float]] | None = None) -> None:
        self.sensor_list.extend(dict(sensor) for sensor in sensors)
        self.sensor_samples.update(samples or {})

    def add_sms(self, messages: list[dict]) -> None:
        self.sms_inbox.extend(dict(message) for message in messages)

    def set_call_state(self, state: str) -> None:
        self.call_state_state = state

    def add_socket_grant(self, domains: list[str] | None) -> None:
        """Grant net.socket with a domains scope (None = unrestricted object)."""
        self.grants.add("net.socket")
        self.socket_domains = domains

    # ---- P8 test helpers ----------------------------------------------------
    def inject_notification(self, id: str, pkg: str, title: str = "", text: str = "",
                            actions: list[str] | None = None, ongoing: bool = False) -> dict:
        notif = {"id": id, "package": pkg, "title": title, "text": text,
                 "actions": [{"index": i, "label": label} for i, label in enumerate(actions or [])],
                 "ongoing": ongoing}
        self.system_notifications = [n for n in self.system_notifications if n["id"] != id]
        self.system_notifications.append(notif)
        return notif

    def emit_event(self, kind: str, payload: dict | None = None, pkg: str | None = None) -> None:
        """Pushes one event into the hub ring (mirrors the real EventHub dispatch path)."""
        self.hub_events.append({"kind": kind, "package": pkg, "payload": dict(payload or {}), "at_ms": 0})

    # ---- P9 shell tier ------------------------------------------------------

    def add_shell_tier(self, allowlist: list[str] | None = None, *, awaiting: bool = False) -> None:
        """Test helper: pairs a fake shell transport with an optional allowlist grant.

        allowlist None = no device.shell grant at all (PERMISSION_DENIED on run);
        an empty list = a grant with an empty allowlist (deny-all, SCOPE_VIOLATION).
        """
        self.shell_allowlist = allowlist
        self.shell_connected = True
        self.shell_awaiting_authorization = awaiting

    @staticmethod
    def _shell_glob_match(pattern: str, command: str) -> bool:
        """Mirrors CommandAllowlist.matches (`*` any run, `?` one non-space, case-sensitive)."""
        def at(p: int, v: int) -> bool:
            if p == len(pattern):
                return v == len(command)
            c = pattern[p]
            if c == "*":
                return any(at(p + 1, skip) for skip in range(v, len(command) + 1))
            if c == "?":
                return v < len(command) and command[v] != " " and at(p + 1, v + 1)
            return v < len(command) and pattern[p] == command[v] and at(p + 1, v + 1)
        return at(0, 0)

    def _shell_verdict(self, command: str) -> str | None:
        """None = allowed; else the denial reason string (mirrors CommandAllowlist.verdict)."""
        if not command.strip():
            return "command is empty"
        if len(command) > 4000:
            return "command exceeds 4000 characters"
        for ch in ";|`()<>\n\r&":
            if ch in command:
                return f"command contains the forbidden shell metacharacter '{ch}'"
        if "$" in command:
            return "command contains '$' (parameter expansion is not permitted)"
        if self.shell_allowlist is None:
            return "the run has no device.shell grant"
        if not self.shell_allowlist:
            return "the device.shell grant has an empty allowlist"
        if not any(self._shell_glob_match(p, command) for p in self.shell_allowlist):
            return "command is not in the device.shell allowlist"
        return None

    def _device_shell(self, method: str, a: dict) -> Any:
        if method == "run":
            command = a.get("command")
            if not isinstance(command, str) or not command.strip():
                raise SimError("INVALID_ARGS", '"command" is required')
            timeout = a.get("timeout_ms", 15000)
            if not 1 <= int(timeout) <= 120000:
                raise SimError("INVALID_ARGS", "timeout_ms must be 1..120000")
            if not self.shell_connected:
                raise SimError("NEEDS_SETUP", "no shell transport is connected (SHELL_TIER_NOT_PAIRED)")
            verdict = self._shell_verdict(command)
            if verdict is not None:
                if self.shell_allowlist is None:
                    raise SimError("PERMISSION_DENIED", verdict)
                raise SimError("SCOPE_VIOLATION", verdict)
            self.shell_commands.append(command)
            return {"stdout": f"[sim] {command}", "stderr": "", "exit_code": 0,
                    "mode": "shell_v2", "transport": "adb.tcp", "duration_ms": 1}
        if method == "transports":
            if self.shell_allowlist is None:
                raise SimError("PERMISSION_DENIED", "the run has no device.shell grant")
            state = ("awaiting_authorization" if self.shell_awaiting_authorization
                     else ("live" if self.shell_connected else "needs_setup"))
            reason = "SHELL_AWAITING_AUTHORIZATION" if self.shell_awaiting_authorization else "SHELL_TIER_NOT_PAIRED"
            return {"transports": [{"id": "adb.tcp", "describe": "127.0.0.1:5555 (sim)",
                                    "state": state, "reason": reason}]}
        raise SimError("INVALID_ARGS", f"unknown device.shell method '{method}'")

    # ---- P9 part 2 handlers --------------------------------------------------

    def _two_level_gate(self, capability: str, feature: str) -> None:
        """Fail-closed two-level gate: grant AND switch, mirroring the broker (plan §14.5)."""
        if capability not in self.grants:
            raise SimError("PERMISSION_DENIED", f"the run has no {capability} grant")
        if feature not in self.switches:
            raise SimError("INSECURE_FEATURE_DISABLED", f"the {feature} switch is off")

    def _shell_required(self) -> None:
        if not self.shell_connected:
            raise SimError("NEEDS_SETUP", "no shell transport is connected (SHELL_TIER_NOT_PAIRED)")

    def _device_app_manage(self, method: str, a: dict) -> Any:
        self._two_level_gate("device.app.manage", "app_manage")
        pkg = a.get("package") or a.get("path") or ""
        if method == "force_stop":
            self._shell_required()
            self.app_manage_calls.append({"method": method, "package": pkg})
            return {"result": "VERIFIED", "method": "shell", "exit_code": 0}
        if method in ("clear_state", "uninstall"):
            if not a.get("confirm"):
                raise SimError("SCOPE_VIOLATION", "this method is destructive: pass confirm=true")
            self._shell_required()
            self.app_manage_calls.append({"method": method, "package": pkg})
            return {"result": "VERIFIED", "method": "pm_clear" if method == "clear_state" else "pm_uninstall"}
        if method in ("grant_permission", "revoke_permission"):
            permission = a.get("permission", "")
            if "." not in permission:
                raise SimError("INVALID_ARGS", "permission must be a fully-qualified name (android.permission.X)")
            self._shell_required()
            self.app_manage_calls.append({"method": method, "package": pkg, "permission": permission})
            return {"result": "VERIFIED", "method": "pm_grant" if method == "grant_permission" else "pm_revoke", "verified": False}
        if method == "install":
            if not a.get("confirm"):
                raise SimError("SCOPE_VIOLATION", "this method is destructive: pass confirm=true")
            path = a.get("path", "")
            if not path.startswith("/"):
                raise SimError("INVALID_ARGS", "path must be an absolute device path")
            if not path.lower().endswith(".apk"):
                raise SimError("INVALID_ARGS", "path must point to an .apk file")
            self._shell_required()
            self.app_manage_calls.append({"method": "install", "path": path, "mode": a.get("mode", "pm")})
            if a.get("mode") == "installer":
                return {"result": "CONFIRMED", "method": "installer", "verified": False}
            return {"result": "VERIFIED", "method": "pm"}
        raise SimError("INVALID_ARGS", f"unknown device.app.manage method '{method}'")

    def _device_logcat(self, method: str, a: dict) -> Any:
        self._two_level_gate("device.logcat", "logcat")
        self._shell_required()
        if method == "clear":
            self.logcat_cleared += 1
            self.logcat_lines = []
            return {"cleared": True}
        if method == "snapshot":
            max_lines = int(a.get("max_lines", 500))
            if not 1 <= max_lines <= 5000:
                raise SimError("INVALID_ARGS", "max_lines must be 1..5000")
            entries = list(self.logcat_lines)
            if a.get("tag"):
                entries = [e for e in entries if e.get("tag") == a["tag"]]
            if a.get("level"):
                order = "VDIWEF"
                min_i = order.index(a["level"])
                entries = [e for e in entries if e.get("level", "I") in order and order.index(e["level"]) >= min_i]
            if a.get("contains"):
                needle = a["contains"].lower()
                entries = [e for e in entries if needle in e.get("message", "").lower() or needle in e.get("tag", "").lower()]
            if a.get("package") is not None:
                # Mirror the real provider: pidof first; an unknown pid answers an honest empty.
                pid = next((e["pid"] for e in entries if e.get("pid") is not None and a["package"] in e.get("message", "")), None)
                if pid is None:
                    return {"lines": [], "count": 0, "package": a["package"], "package_pid": False}
                entries = [e for e in entries if e.get("pid") == pid]
            if a.get("since_ms") is not None:
                entries = [e for e in entries if e.get("ts", 0) >= a["since_ms"]]
            entries = entries[-max_lines:]
            self.calls.append(("device.logcat", "snapshot", dict(a)))
            return {"lines": [dict(e) for e in entries], "count": len(entries), "parsed": len(self.logcat_lines)}
        raise SimError("INVALID_ARGS", f"unknown device.logcat method '{method}'")

    def _device_radio(self, method: str, a: dict) -> Any:
        if "device.radio" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no device.radio grant")
        if method != "set":
            raise SimError("INVALID_ARGS", f"unknown device.radio method '{method}'")
        radio = a.get("radio")
        if radio not in self.radio_state:
            raise SimError("INVALID_ARGS", "radio must be wifi|bluetooth|mobile_data|airplane")
        self.radio_state[radio] = bool(a.get("enable"))
        self.device_control_calls.append({"capability": "device.radio", **a})
        return {"radio": radio, "enable": bool(a.get("enable")), "applied": True, "verified": False}

    def _device_display(self, method: str, a: dict) -> Any:
        if "device.display" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no device.display grant")
        if method == "set_dark_mode":
            mode = a.get("mode")
            if mode not in ("yes", "no", "auto"):
                raise SimError("INVALID_ARGS", "mode must be yes|no|auto")
            self.display_state["night"] = mode
        elif method == "set_orientation":
            if a.get("orientation") not in ("auto", "portrait", "landscape", "reverse_portrait", "reverse_landscape"):
                raise SimError("INVALID_ARGS", "orientation must be auto|portrait|landscape|reverse_portrait|reverse_landscape")
            self.display_state["orientation"] = a["orientation"]
        elif method == "set_font_scale":
            try:
                scale = float(a.get("scale", ""))
            except ValueError:
                raise SimError("INVALID_ARGS", "scale must be numeric")
            if not 0.5 <= scale <= 2.0:
                raise SimError("INVALID_ARGS", "scale out of range")
            self.display_state["font_scale"] = str(a["scale"])
        else:
            raise SimError("INVALID_ARGS", f"unknown device.display method '{method}'")
        self.device_control_calls.append({"capability": "device.display", **a})
        return {"applied": True}

    def _device_mock_location(self, method: str, a: dict) -> Any:
        self._two_level_gate("device.location.mock", "mock_location")
        if method != "set":
            raise SimError("INVALID_ARGS", f"unknown device.location.mock method '{method}'")
        self.mock_location_appop = "allow" if a.get("enable") else "deny"
        self.device_control_calls.append({"capability": "device.location.mock", **a})
        return {"enable": bool(a.get("enable")), "applied": True, "verified": True, "appop_package": "app.autohost"}

    # ---- P10 part 1 handlers ---------------------------------------------------

    def _browser_perm(self, method: str) -> None:
        needed = "browser.read" if method in ("extract", "cookies") else "browser.open"
        if needed not in self.grants and "browser.open" not in self.grants:
            raise SimError("PERMISSION_DENIED", f"the run has no {needed} grant")

    def _web_domain_verdict(self, url: str) -> str | None:
        """None = allowed; else the denial reason (mirrors BrowserPolicy/NetPolicy semantics)."""
        scheme = url.split(":", 1)[0] if ":" in url else ""
        if scheme == "task":
            rel = url[5:].lstrip("/")
            if not rel or ".." in rel.split("/"):
                return "task: paths must stay inside the task bundle"
            return None
        if scheme not in ("http", "https"):
            return f"only http, https and task: URLs are allowed (got '{scheme}')"
        host = url.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0].lower()
        domains = self.browser_domains
        if domains is None:
            return None
        if not domains:
            return host
        for pattern in domains:
            if pattern == "*":
                return None
            if pattern.startswith("*."):
                suffix = pattern[2:]
                if host == suffix or host.endswith("." + suffix):
                    return None
            elif host == pattern:
                return None
        return host

    def _browser_webview(self, method: str, a: dict) -> Any:
        self._browser_perm(method)
        timeout = a.get("timeout_ms", 20000)
        if not 1 <= int(timeout) <= 120000:
            raise SimError("INVALID_ARGS", "timeout_ms must be 1..120000")
        if method in ("open", "navigate"):
            url = a.get("url", "")
            if not url.strip():
                raise SimError("INVALID_ARGS", '"url" is required')
            if a.get("challenge") not in (None, "raise", "report"):
                raise SimError("INVALID_ARGS", "challenge must be 'raise' or 'report'")
            verdict = self._web_domain_verdict(url)
            if verdict is not None:
                raise SimError("SCOPE_VIOLATION", verdict)
            self.web_pages_opened.append({"url": url, "profile": a.get("profile", "run")})
            if method == "open" and len(self.web_pages) >= 4:
                raise SimError("SCOPE_VIOLATION", "at most 4 pages per run")
            spec = self._web_registry.get(url, {"title": url, "body_text": "", "challenge": None,
                                                "values": {}, "cookies": {}})
            self._web_page_seq += 1
            page_id = a.get("page_id") or f"pg-{self._web_page_seq}"
            profile = a.get("profile", "run")
            page = self.web_pages.setdefault(page_id, {"page_id": page_id, "profile": profile, "values": {}})
            page.update({"url": url, "title": spec["title"], "load_state": "loaded",
                         "input_mode": "real", "body_sample": spec["body_text"], "values": spec["values"]})
            for name, value in spec.get("cookies", {}).items():
                self.web_cookies.setdefault(profile, {})[name] = value
            result = {"page_id": page_id, "url": url, "title": spec["title"], "load_state": "loaded",
                      "profile": profile, "input_mode": "real"}
            if spec.get("challenge"):
                result["challenge"] = dict(spec["challenge"])
            return result
        page_id = a.get("page_id", "")
        page = self.web_pages.get(page_id)
        if page is None:
            if method == "pages":
                return {"pages": [dict(p, values=None) for p in self.web_pages.values()]}
            raise SimError("NOT_FOUND", f"unknown page_id '{page_id}'")
        if method == "click":
            selector = a.get("selector", "")
            if not selector:
                raise SimError("INVALID_ARGS", '"selector" is required')
            self.web_clicks.append((page_id, selector))
            found = selector in page.get("values", {})
            return {"clicked": found, "found": found, "input_mode": "real"}
        if method == "type":
            selector = a.get("selector", "")
            if not selector:
                raise SimError("INVALID_ARGS", '"selector" is required')
            self.web_typed.append({"page_id": page_id, "selector": selector})  # text never recorded
            found = selector in page.get("values", {})
            return {"typed": found, "found": found, "input_mode": "real"}
        if method == "extract":
            selector = a.get("selector", "")
            values = page.get("values", {}).get(selector, [])
            return {"values": values, "count": len(values), "truncated": False}
        if method == "cookies":
            jar = self.web_cookies.get(page.get("profile", "run"), {})
            return {"cookies": [{"name": n, "value": v, "domain": None, "path": None, "expires_at_ms": None}
                                for n, v in jar.items()]}
        if method == "clear_cookies":
            self.web_cookies.pop(page.get("profile", "run"), None)
            self.cookies_cleared.append(page.get("profile", "run"))
            return {"cleared": True}
        if method == "pages":
            return {"pages": [dict(p, values=None) for p in self.web_pages.values()]}
        if method == "close":
            self.web_pages.pop(page_id, None)
            return {"closed": True}
        raise SimError("INVALID_ARGS", f"unknown browser.webview method '{method}'")

    def _browser_a11y(self, method: str, a: dict) -> Any:
        self._browser_perm(method)
        if method in ("cookies", "clear_cookies"):
            raise SimError("CAPABILITY_UNAVAILABLE",
                           "COOKIES_UNSUPPORTED_LINK: the a11y link cannot read browser storage; use browser.webview for cookies")
        link = self.a11y_browser
        if link is None:
            raise SimError("NEEDS_SETUP", "the accessibility service is not connected (ACCESSIBILITY_SERVICE_OFF)")
        if method == "open":
            url = a.get("url", "")
            if not url.strip():
                raise SimError("INVALID_ARGS", '"url" is required')
            verdict = self._web_domain_verdict(url)
            if verdict is not None:
                raise SimError("SCOPE_VIOLATION", verdict)
            if url.startswith("task:"):
                raise SimError("SCOPE_VIOLATION", "task: URLs only resolve inside the in-app browser")
            result = {"package": link["package"],
                      "page": {"url": url, "load_state": "foreground", "input_mode": "a11y"}}
            if link.get("challenge"):
                result["challenge"] = dict(link["challenge"])
            return result
        if method == "click":
            selector = a.get("selector", "")
            if not selector:
                raise SimError("INVALID_ARGS", '"selector" is required')
            found = any(selector.lower() in line.lower() for line in link["lines"])
            return {"clicked": found, "found": found, "input_mode": "a11y"}
        if method == "type":
            return {"typed": True, "found": True, "input_mode": "a11y"}
        if method == "extract":
            return {"values": list(link["lines"]), "count": len(link["lines"]), "truncated": False,
                    "input_mode": "a11y"}
        if method == "page":
            result = {"package": link["package"], "visible_sample_chars": sum(len(x) for x in link["lines"])}
            if link.get("challenge"):
                result["challenge"] = dict(link["challenge"])
            return result
        if method == "close":
            return {"closed": True, "input_mode": "a11y"}
        raise SimError("INVALID_ARGS", f"unknown browser.a11y method '{method}'")

    def _browser_cdp(self, method: str, a: dict) -> Any:
        self._browser_perm(method)
        if method == "status":
            return {"shell_live": self.cdp_state is not None or self.shell_connected,
                    "route": "adb.forward.localabstract" if (self.cdp_state or self.shell_connected) else "unavailable",
                    "verified_on_hardware": False,
                    "note": "draft: UNVERIFIED-on-hardware until spike S27 measures the route"}
        if self.cdp_state is None and not self.shell_connected:
            raise SimError("NEEDS_SETUP", "no shell transport is connected (SHELL_TIER_NOT_PAIRED)")
        if method == "navigate":
            url = a.get("url", "")
            if not url.strip():
                raise SimError("INVALID_ARGS", '"url" is required')
            if url.startswith("task:"):
                raise SimError("SCOPE_VIOLATION", "task: URLs do not resolve over CDP; use browser.webview")
            verdict = self._web_domain_verdict(url)
            if verdict is not None:
                raise SimError("SCOPE_VIOLATION", verdict)
            return {"url": url, "title": url, "input_mode": "cdp"}
        if method == "evaluate":
            expression = a.get("expression", "")
            if not expression:
                raise SimError("INVALID_ARGS", '"expression" is required')
            return {"result": {"type": "string", "value": self.cdp_state["evaluate_value"]}}
        if method == "screenshot":
            import base64
            return {"data_b64": base64.b64encode(b"sim-png").decode(), "format": "png"}
        if method == "cookies":
            return {"cookies": list(self.cdp_state["cookies"])}
        if method == "close":
            self.cdp_closed += 1
            return {"closed": True}
        raise SimError("INVALID_ARGS", f"unknown browser.cdp method '{method}'")

    def _vision(self, method: str, a: dict) -> Any:
        if "vision.read" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no vision.read grant")
        source = a.get("source", "screen")
        if source not in ("screen", "file"):
            raise SimError("INVALID_ARGS", 'source must be "screen" or "file"')
        if source == "file" and not a.get("path"):
            raise SimError("INVALID_ARGS", '"path" is required when source is "file"')
        region = a.get("region")
        if region is not None:
            if not all(k in region for k in ("left", "top", "width", "height")):
                raise SimError("INVALID_ARGS", "region must be {left, top, width, height}")
            if region["width"] <= 0 or region["height"] <= 0:
                raise SimError("INVALID_ARGS", "region width/height must be positive")
            if region["left"] < 0 or region["top"] < 0:
                raise SimError("INVALID_ARGS", "region left/top must be non-negative")
        if method == "ocr":
            return {"hint": True, "engine": "sim.latin", "source": source,
                    "width": 1080, "height": 2340, "duration_ms": 1,
                    "lines": [dict(line) for line in self.ocr_lines]}
        if method == "template_match":
            needle = a.get("needle_path", "")
            if not needle:
                raise SimError("INVALID_ARGS", '"needle_path" is required')
            threshold = a.get("threshold", 0.92)
            if not 0.5 <= float(threshold) <= 1.0:
                raise SimError("INVALID_ARGS", "threshold must be within 0.5..1.0")
            stride = a.get("stride", 4)
            if not 1 <= int(stride) <= 32:
                raise SimError("INVALID_ARGS", "stride must be within 1..32")
            canned = self.template_result or {"found": False, "score": 0.0,
                                              "box": {"left": 0, "top": 0, "width": 0, "height": 0}}
            return {"hint": True, **canned, "threshold": float(threshold), "stride": int(stride),
                    "source": source, "duration_ms": 1}
        if method == "qr_decode":
            found = self.qr_value is not None
            result = {"hint": True, "engine": "sim.qr", "source": source, "found": found,
                      "duration_ms": 1}
            if found:
                result["value"] = self.qr_value
            return result
        raise SimError("INVALID_ARGS", f"unknown vision method '{method}'")

    # ---- P10 part 2 handlers -----------------------------------------------------

    def _contacts(self, method: str, a: dict) -> Any:
        if "contacts.read" not in self.grants and "contacts.write" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no contacts grant")
        if method == "query":
            limit = int(a.get("limit", 100))
            if not 1 <= limit <= 500:
                raise SimError("INVALID_ARGS", "limit must be 1..500")
            flt = a.get("filter")
            rows = [r for r in self.contacts_rows if flt is None or flt.lower() in (r.get("name") or "").lower()]
            return {"contacts": [dict(r) for r in rows[:limit]], "count": min(len(rows), limit)}
        if method == "insert":
            name = a.get("name", "")
            if not name.strip():
                raise SimError("INVALID_ARGS", "name must not be blank")
            self.contacts_inserted += 1
            return {"id": 1000 + self.contacts_inserted}
        raise SimError("INVALID_ARGS", f"unknown contacts method '{method}'")

    def _calendar(self, method: str, a: dict) -> Any:
        if "calendar.read" not in self.grants and "calendar.write" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no calendar grant")
        if method == "query":
            limit = int(a.get("limit", 100))
            if not 1 <= limit <= 500:
                raise SimError("INVALID_ARGS", "limit must be 1..500")
            from_ms = int(a.get("from_ms", 0))
            to_ms = int(a.get("to_ms", from_ms + 7 * 24 * 60 * 60 * 1000))
            if to_ms < from_ms or to_ms - from_ms > 31 * 24 * 60 * 60 * 1000:
                raise SimError("INVALID_ARGS", "window must be ordered and within 31 days")
            rows = [e for e in self.calendar_events if e["end_ms"] >= from_ms and e["start_ms"] <= to_ms]
            return {"events": [dict(e) for e in rows[:limit]], "count": min(len(rows), limit)}
        if method == "insert":
            title = a.get("title", "")
            if not title.strip():
                raise SimError("INVALID_ARGS", "title must not be blank")
            start = int(a.get("start_ms", 0))
            end = int(a.get("end_ms", 0))
            if end <= start:
                raise SimError("INVALID_ARGS", "end_ms must be after start_ms")
            self.calendar_inserted += 1
            return {"id": 2000 + self.calendar_inserted}
        raise SimError("INVALID_ARGS", f"unknown calendar method '{method}'")

    def _media(self, method: str, a: dict) -> Any:
        if "media" not in self.grants and "media.write" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no media grant")
        if method == "list":
            limit = int(a.get("limit", 100))
            if not 1 <= limit <= 500:
                raise SimError("INVALID_ARGS", "limit must be 1..500")
            kind = a.get("kind")
            rows = [r for r in self.media_rows if kind is None or r["kind"] == kind]
            return {"media": [dict(r) for r in rows[:limit]], "count": min(len(rows), limit)}
        if method == "read":
            mid = int(a.get("id", -1))
            row = next((r for r in self.media_rows if r["id"] == mid), None)
            if row is None:
                raise SimError("NOT_FOUND", f"media id {mid} not found (or over the 10485760 byte cap)")
            import base64
            return {"data_b64": base64.b64encode(row.get("data", b"sim")).decode(), "size_bytes": row.get("size_bytes", 3)}
        if method == "insert":
            kind = a.get("kind", "")
            if kind not in ("image", "video", "audio"):
                raise SimError("INVALID_ARGS", "kind must be image, video or audio")
            name = a.get("display_name", "")
            if not name or "/" in name:
                raise SimError("INVALID_ARGS", "display_name is required and must not contain '/'")
            import base64 as b64
            raw = b64.b64decode(a.get("data_b64", ""))
            mid = max([r["id"] for r in self.media_rows], default=3000) + 1
            self.media_rows.append({"id": mid, "kind": kind, "display_name": name,
                                    "size_bytes": len(raw), "added_ms": 0})
            return {"id": mid}
        if method == "delete":
            mid = int(a.get("id", -1))
            before = len(self.media_rows)
            self.media_rows = [r for r in self.media_rows if r["id"] != mid]
            self.media_deleted.append(mid)
            if len(self.media_rows) == before:
                raise SimError("NOT_FOUND", f"media id {mid} not found")
            return {"deleted": True}
        raise SimError("INVALID_ARGS", f"unknown media method '{method}'")

    def _location(self, method: str, a: dict) -> Any:
        if "location" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no location grant")
        if method == "last_known":
            if not self.loc_background:
                raise SimError("NEEDS_SETUP", "BACKGROUND_LOCATION: last-known fixes serve background runs only after 'Allow all the time' is granted")
            if self.loc_fix is None:
                raise SimError("CAPABILITY_UNAVAILABLE", "NO_LOCATION_FIX: no cached location fix (location services off or empty)")
            return {**self.loc_fix, "stale": True}
        if method == "current":
            timeout = int(a.get("timeout_ms", 15000))
            if not 1000 <= timeout <= 60000:
                raise SimError("INVALID_ARGS", "timeout_ms must be 1000..60000")
            if self.loc_fix is None:
                raise SimError("TIMEOUT", f"no location fix within {timeout}ms (location services off?)")
            return {**self.loc_fix, "stale": False}
        raise SimError("INVALID_ARGS", f"unknown location method '{method}'")

    def _camera(self, method: str, a: dict) -> Any:
        if "camera" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no camera grant")
        if method == "capture_photo":
            self.captures.append({"front": bool(a.get("front"))})
            return {"path": "/data/data/app.autohost/cache/camera-sim.jpg", "width": 1920, "height": 1080,
                    "size_bytes": 240_000}
        raise SimError("INVALID_ARGS", f"unknown camera method '{method}'")

    def _microphone(self, method: str, a: dict) -> Any:
        if "microphone" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no microphone grant")
        if method == "record":
            duration = int(a.get("duration_ms", 10000))
            if not 500 <= duration <= 60000:
                raise SimError("INVALID_ARGS", "duration_ms must be 500..60000")
            self.recordings.append({"duration_ms": duration})
            return {"path": "/data/data/app.autohost/cache/mic-sim.m4a", "duration_ms": duration,
                    "size_bytes": duration * 12}
        raise SimError("INVALID_ARGS", f"unknown microphone method '{method}'")

    def _sensors(self, method: str, a: dict) -> Any:
        if "sensors" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no sensors grant")
        if method == "list":
            return {"sensors": [dict(s) for s in self.sensor_list], "count": len(self.sensor_list)}
        if method == "read":
            names = a.get("names")
            samples = [{"name": name, "values": vals} for name, vals in self.sensor_samples.items()
                       if names is None or name in names]
            return {"samples": samples}
        raise SimError("INVALID_ARGS", f"unknown sensors method '{method}'")

    def _sms(self, method: str, a: dict) -> Any:
        self._two_level_gate("device.sms", "sms")
        if method == "read_inbox":
            limit = int(a.get("limit", 100))
            if not 1 <= limit <= 500:
                raise SimError("INVALID_ARGS", "limit must be 1..500")
            flt = a.get("filter")
            rows = [m for m in self.sms_inbox if flt is None or flt in m["body"]]
            return {"messages": [dict(m) for m in rows[:limit]], "count": min(len(rows), limit)}
        if method == "send":
            address = a.get("address", "")
            body = a.get("body", "")
            if not address.strip():
                raise SimError("INVALID_ARGS", '"address" is required')
            if not body:
                raise SimError("INVALID_ARGS", '"body" is required')
            if len(body) > 480:
                raise SimError("INVALID_ARGS", "body exceeds 480 characters (send parts explicitly instead)")
            digits = "".join(c for c in address if c.isdigit())
            if not address.strip().startswith("+") and len(digits) <= 6 and not a.get("allow_short"):
                raise SimError("SCOPE_VIOLATION", "short codes are refused by default (allow_short=true to override)")
            if digits.startswith("1900") or digits.startswith("0900"):
                raise SimError("SCOPE_VIOLATION", "premium-rate destinations are refused")
            if self.sms_sends >= 10:
                raise SimError("RATE_LIMITED", "at most 10 SMS per run (this is a money-costing action)")
            self.sms_sends += 1
            self.sms_sent.append({"address": address, "body": body})
            return {"sent": True, "delivered": False}
        if method == "wait_for":
            contains = a.get("contains")
            frm = a.get("from")
            timeout = int(a.get("timeout_ms", 30000))
            if not 1000 <= timeout <= 300000:
                raise SimError("INVALID_ARGS", "timeout_ms must be 1000..300000")
            hits = [m for m in self.sms_inbox
                    if (contains is None or contains in m["body"]) and (frm is None or m["sender"] == frm)]
            if hits:
                return dict(max(hits, key=lambda m: m["received_ms"]))
            raise SimError("TIMEOUT", f"no matching SMS within {timeout}ms")
        raise SimError("INVALID_ARGS", f"unknown device.sms method '{method}'")

    def _telephony(self, method: str, a: dict) -> Any:
        self._two_level_gate("device.telephony", "telephony")
        if method == "call_state":
            result = {"state": self.call_state_state}
            return result
        if method == "place_call":
            number = a.get("number", "")
            if not number.strip():
                raise SimError("INVALID_ARGS", '"number" is required')
            digits = "".join(c for c in number if c.isdigit())
            if not number.strip().startswith("+") and len(digits) <= 6 and not a.get("allow_short"):
                raise SimError("SCOPE_VIOLATION", "short codes are refused by default (allow_short=true to override)")
            if digits.startswith("1900") or digits.startswith("0900"):
                raise SimError("SCOPE_VIOLATION", "premium-rate destinations are refused")
            self.calls_placed.append(number)
            return {"placed": True, "verified": False}
        raise SimError("INVALID_ARGS", f"unknown device.telephony method '{method}'")

    def _net_socket(self, method: str, a: dict) -> Any:
        if "net.socket" not in self.grants:
            raise SimError("PERMISSION_DENIED", "the run has no net.socket grant")
        def _domains(host: str) -> str | None:
            domains = self.socket_domains
            if domains is None:
                return None
            if not domains:
                return host
            for pattern in domains:
                if pattern == "*" or host == pattern:
                    return None
                if pattern.startswith("*.") and (host == pattern[2:] or host.endswith("." + pattern[2:])):
                    return None
            return host
        if method == "tcp_connect":
            host = a.get("host", "")
            port = int(a.get("port", 0))
            if not host:
                raise SimError("INVALID_ARGS", '"host" is required')
            if not 1 <= port <= 65535:
                raise SimError("INVALID_ARGS", '"port" must be 1..65535')
            verdict = _domains(host)
            if verdict is not None:
                raise SimError("SCOPE_VIOLATION", f'host "{host}" is not in this run net.socket domain allowlist')
            self.tcp_connections.append({"host": host, "port": port})
            return {"handle": f"tcp-{len(self.tcp_connections)}", "host": host, "port": port}
        if method in ("tcp_send", "tcp_recv", "tcp_close"):
            handle = a.get("handle", "")
            if not handle:
                raise SimError("INVALID_ARGS", '"handle" is required')
            if not handle.startswith("tcp-") or int(handle[4:]) > len(self.tcp_connections) or handle in getattr(self, "_tcp_closed", set()):
                raise SimError("NOT_FOUND", f"unknown handle '{handle}'")
            if method == "tcp_send":
                return {"sent": len(base64_decode(a.get("data_b64", "")))}
            if method == "tcp_recv":
                if self.tcp_recv_data is None:
                    raise SimError("TIMEOUT", f"no data within {int(a.get('timeout_ms', 30000))}ms")
                import base64
                return {"data_b64": base64.b64encode(self.tcp_recv_data).decode(),
                        "count": len(self.tcp_recv_data), "closed": False}
            if not hasattr(self, "_tcp_closed"):
                self._tcp_closed = set()
            self._tcp_closed.add(handle)
            return {"closed": True}
        if method == "udp_send":
            host = a.get("host", "")
            port = int(a.get("port", 0))
            if not host:
                raise SimError("INVALID_ARGS", '"host" is required')
            if not 1 <= port <= 65535:
                raise SimError("INVALID_ARGS", '"port" must be 1..65535')
            verdict = _domains(host)
            if verdict is not None:
                raise SimError("SCOPE_VIOLATION", f'host "{host}" is not in this run net.socket domain allowlist')
            self.udp_sends.append({"host": host, "port": port})
            return {"sent": len(base64_decode(a.get("data_b64", "")))}
        if method == "udp_recv":
            int(a.get("port", 0))
            if self.udp_recv_canned is None:
                raise SimError("TIMEOUT", f"no datagram within {int(a.get('timeout_ms', 30000))}ms")
            import base64
            return {"data_b64": base64.b64encode(self.udp_recv_canned).decode(),
                    "count": len(self.udp_recv_canned), "from": "127.0.0.1", "port": 9999}
        raise SimError("INVALID_ARGS", f"unknown net.socket method '{method}'")

    def _net_reverse(self, method: str, a: dict) -> Any:
        self._two_level_gate("net.reverse", "net_reverse")
        if method == "open":
            url = a.get("url", "")
            token = a.get("token", "")
            if not url.startswith(("ws://", "wss://")):
                raise SimError("INVALID_ARGS", "net.reverse needs a ws:// or wss:// URL")
            if not token:
                raise SimError("INVALID_ARGS", '"token" is required (sent as the first frame, never in the URL)')
            host = url.split("://", 1)[-1].split("/", 1)[0].split(":", 1)[0]
            if url.startswith("ws://") and host not in ("localhost", "127.0.0.1", "::1"):
                raise SimError("SCOPE_VIOLATION", "plain ws:// is only allowed for localhost — use wss:// for remote hosts (token + TLS)")
            self.reverse_opened.append({"url": url})
            return {"handle": f"rev-{len(self.reverse_opened)}", "token_required": True}
        if method in ("send", "recv", "close"):
            handle = a.get("handle", "")
            if not handle:
                raise SimError("INVALID_ARGS", '"handle" is required')
            if not handle.startswith("rev-") or int(handle[4:]) > len(self.reverse_opened):
                raise SimError("NOT_FOUND", f"unknown handle '{handle}'")
            if method == "send":
                text = a.get("text", "")
                if not text:
                    raise SimError("INVALID_ARGS", '"text" is required')
                return {"sent": True}
            if method == "recv":
                return {"text": "[sim] reverse frame", "closed": False}
            return {"closed": True}
        raise SimError("INVALID_ARGS", f"unknown net.reverse method '{method}'")



    # screen model -----------------------------------------------------------
    def screen_package(self) -> str | None:
        return self.screens.get(self.current, {}).get("package") if self.current else None

    def _visible_nodes(self) -> list[dict]:
        if not self.current or self.current not in self.screens:
            return []
        spec = self.screens[self.current]
        nodes = list(spec["nodes"])
        if self.swipes >= spec["reveal_after"]:
            nodes += spec["hidden_nodes"]
        out = []
        for i, raw in enumerate(nodes):
            node = dict(raw)
            node.setdefault("cls", "android.view.View")
            node.setdefault("clickable", False)
            node.setdefault("enabled", True)
            node.setdefault("bounds", [0, 120 * i, 1080, 120 * i + 100])
            node["ref"] = f"{self.current}#{i}"
            node["pkg"] = spec["package"]
            node["index"] = i
            node["flags"] = [k for k in ("clickable", "checked", "editable", "scrollable", "focused")
                             if node.get(k)]
            out.append(node)
        return out

    def _goto(self, name: str, *, push: bool = True, reset: bool = False) -> None:
        if name not in self.screens:
            raise SimError("INTERNAL", f"sim screen '{name}' does not exist")
        if reset:
            self.history = []
        elif push and self.current:
            self.history.append(self.current)
        self.current = name
        self.swipes = 0

    def _activate(self, node: dict) -> None:
        spec = self.screens.get(self.current, {})
        target = spec.get("on_click", {}).get(node.get("id")) or spec.get("on_click", {}).get(node.get("text"))
        if target:
            self._goto(target, push=True)

    def compact(self) -> str:
        pkg = self.screen_package() or "none"
        lines = [f"App: {self.current or 'none'} ({pkg})  Activity: {self.current or 'none'}  Keyboard: hidden"]
        for n in self._visible_nodes():
            text = "" if n.get("password") else n.get("text", n.get("desc", ""))
            flags = ",".join(n["flags"])
            short = n["cls"].split(".")[-1]
            b = n["bounds"]
            lines.append(f'{n["index"]}. {short}: "{n.get("id", "")}" "{text}" [{flags}] ({b[0]},{b[1]},{b[2]},{b[3]})')
        return "\n".join(lines)

    def _resolve(self, target: dict) -> dict:
        if "ref" in target:
            screen, _, idx = str(target["ref"]).partition("#")
            if screen != self.current:
                raise SimError("STALE_NODE", "node belongs to a screen that is no longer showing")
            nodes = self._visible_nodes()
            if not idx.isdigit() or int(idx) >= len(nodes):
                raise SimError("STALE_NODE", "node no longer exists on the screen")
            return nodes[int(idx)]
        found = select(target.get("selector"), self._visible_nodes())
        if not found:
            raise SimError("NOT_FOUND", "no element matches the selector")
        return found[0]

    # broker surface -------------------------------------------------------------
    def describe(self) -> dict:
        out = {}
        for cap in _CAPABILITIES:
            available = cap != "device.screenshot" or self.screenshot_enabled
            out[cap] = {"available": available, "reason": None if available else "CAPABILITY_UNAVAILABLE",
                        "provider": "sim", "chain": ["sim"]}
        return out

    def dispatch(self, capability: str, method: str, args: dict) -> Any:
        self.calls.append((capability, method, dict(args)))
        handlers: dict[str, Callable] = {
            "device.ui": self._device_ui, "device.app": self._device_app,
            "device.screenshot": self._device_screenshot, "device.screen": self._device_screen,
            "device.ime": self._device_ime, "net.http": self._net_http, "notify.post": self._notify_post,
            "ui.prompt": self._ui_prompt, "store": self._store, "state": self._state,
            "files.private": self._files_private, "system": self._system,
            "device.notifications": self._device_notifications, "device.clipboard": self._device_clipboard,
            "device.intent": self._device_intent, "device.events": self._device_events,
            "files.shared": self._files_shared, "net.ws": self._net_ws, "net.server": self._net_server,
            "device.audio": self._device_audio, "device.power": self._device_power,
            "ui.overlay": self._ui_overlay, "device.settings": self._device_settings,
            "tasks.run": self._tasks_run, "android.api.direct": self._android_direct,
            "device.shell": self._device_shell,
            "device.app.manage": self._device_app_manage, "device.logcat": self._device_logcat,
            "device.radio": self._device_radio, "device.display": self._device_display,
            "device.location.mock": self._device_mock_location,
            "browser.webview": self._browser_webview, "browser.a11y": self._browser_a11y,
            "browser.cdp": self._browser_cdp, "vision": self._vision,
            "contacts": self._contacts, "calendar": self._calendar, "media": self._media,
            "location": self._location, "camera": self._camera, "microphone": self._microphone,
            "sensors": self._sensors, "device.sms": self._sms, "device.telephony": self._telephony,
            "net.socket": self._net_socket, "net.reverse": self._net_reverse,
        }
        handler = handlers.get(capability)
        if handler is None:
            raise SimError("CAPABILITY_UNAVAILABLE", f"{capability} is not simulated")
        return handler(method, args)

    def _device_ui(self, method: str, a: dict) -> Any:
        if method == "snapshot":
            nodes = self._visible_nodes()
            snap = {"ts": int(self.clock.now().timestamp() * 1000), "package": self.screen_package(),
                    "activity": self.current, "keyboard": "hidden", "nodes": nodes,
                    "node_count": len(nodes), "empty": not nodes}
            if a.get("compact"):
                snap["compact"] = self.compact()
            return snap
        if method == "find":
            return {"nodes": select(a.get("selector"), self._visible_nodes())[: int(a.get("limit", 20))]}
        if method == "wait_for":
            polls = max(1, int(a.get("timeout_ms", 0)) // 250)
            for _ in range(polls):
                found = select(a.get("selector"), self._visible_nodes())
                if found:
                    return {"node": found[0]}
                self.clock.sleep(0.25)
            return {"node": None}
        if method == "scroll_until":
            max_swipes = int(a.get("max_swipes", 10))
            while True:
                found = select(a.get("selector"), self._visible_nodes())
                if found:
                    return {"found": True, "node": found[0]}
                if self.swipes >= max_swipes:
                    return {"found": False, "node": None}
                self.swipes += 1
                self.clock.sleep(0.2)
        if method == "wait_for_settle":
            return {"settled": True, "waited_ms": 0}
        if method == "is_window_updating":
            return {"updating": False}
        if method == "keyboard_visible":
            return {"visible": False}
        if method == "hide_keyboard":
            return {"ok": True}
        if method == "dump":
            return {"text": self.compact()}
        if method in ("click", "long_click"):
            node = self._resolve(a["target"])
            before = self.current
            method_name = "NODE_ACTION" if node.get("clickable") else "COORDINATE"
            self._activate(node)
            changed = self.current != before
            if a.get("retry_if_no_change") and not changed:
                self._activate(node)
                changed = self.current != before
            if a.get("expect"):
                verified = bool(select(a["expect"], self._visible_nodes())) and method_name == "NODE_ACTION"
            else:
                verified = changed and method_name == "NODE_ACTION"
            return {"method": method_name, "verified": verified}
        if method == "set_text":
            node = self._resolve(a["target"])
            if not node.get("editable"):
                raise SimError("TEXT_INPUT_REJECTED", "field is not editable")
            spec = self.screens[self.current]
            for source in (spec["nodes"], spec["hidden_nodes"]):
                for raw in source:
                    if raw.get("id") == node.get("id") and node.get("id"):
                        raw["text"] = a["text"]
            return {"outcome": "VERIFIED", "strategy": "SET_TEXT"}
        if method == "press":
            key = a.get("key")
            if key == "back" and self.history:
                self._goto(self.history.pop(), push=False)
                return {"ok": True}
            return {"ok": key in ("back", "home", "recents", "enter", "notifications", "quick_settings", "lock")
                    and key != "back"}
        if method in ("swipe", "tap"):
            self.clock.sleep(0.1)
            return {"method": "COORDINATE"} if method == "tap" else {"ok": True}
        raise SimError("INVALID_ARGS", f"device.ui.{method} is not simulated")

    def _device_app(self, method: str, a: dict) -> Any:
        if method == "query":
            pkg = a["package"]
            return {"installed": pkg in self.installed, "version_name": "1.0", "version_code": 1}
        if method == "list":
            return {"packages": [{"package": p, "label": p} for p in sorted(self.installed)]}
        if method == "foreground":
            return {"package": self.screen_package(), "activity": self.current}
        if method == "wait_foreground":
            pkg = a["package"]
            for _ in range(max(1, int(a.get("timeout_ms", 0)) // 250)):
                if self.screen_package() == pkg:
                    return {"ok": True}
                self.clock.sleep(0.25)
            raise SimError("TIMEOUT", f"{pkg} did not reach the foreground", retryable=True)
        if method == "launch":
            pkg = a["package"]
            if pkg not in self.launch_map or pkg not in self.installed:
                raise SimError("NOT_FOUND", f"{pkg} is not installed")
            self._goto(self.launch_map[pkg], reset=True)
            return {"result": "LAUNCHED_VERIFIED", "strategy": "sim"}
        if method == "stop":
            if self.screen_package() == a["package"]:
                self.current = None
                self.history = []
            return {"result": "VERIFIED", "method": "sim"}
        raise SimError("INVALID_ARGS", f"device.app.{method} is not simulated")

    def _device_screenshot(self, method: str, a: dict) -> Any:
        if not self.screenshot_enabled:
            raise SimError("CAPABILITY_UNAVAILABLE", "screenshots are not available on this device")
        path = f"/sim/evidence/shot_{len(self.screenshots)}.png"
        self.screenshots.append({"path": path, "screen": self.current, "tag": a.get("tag")})
        return {"path": path, "width": 1080, "height": 2400, "provider": "sim"}

    def _device_screen(self, method: str, a: dict) -> Any:
        if method == "is_on":
            return {"on": self.screen_on}
        if method == "is_unlocked":
            return {"unlocked": not self.keyguard_secured, "secure": self.keyguard_secured}
        # P8-10 control (W2)
        if method == "wake":
            self.screen_on = True
            return {"on": True, "strategy": "wake_up"}
        if method == "dismiss_keyguard":
            if self.keyguard_secured:
                return {"dismissed": False, "secure": True}
            self.screen_on = True
            return {"dismissed": True, "secure": False}
        if method == "lock":
            self.screen_on = False
            return {"locked": True}
        if method == "brightness":
            if a.get("level") is not None:
                if not self.write_settings_granted:
                    raise SimError("NEEDS_SETUP", "setting brightness needs the write-settings access",
                                   fix_action="OPEN_WRITE_SETTINGS")
                level = int(a["level"])
                if not 0 <= level <= 100:
                    raise SimError("INVALID_ARGS", "level must be 0-100")
                self.brightness_level = level
            return {"level": self.brightness_level, "mode": "manual"}
        raise SimError("INVALID_ARGS", f"unknown device.screen method '{method}'")

    def _device_ime(self, method: str, a: dict) -> Any:
        if method == "commit_text":
            return {"outcome": "VERIFIED", "strategy": "IME"}
        if method == "clipboard_get":
            return {"text": self.clipboard}
        self.clipboard = a.get("text")
        return {"ok": True}

    def _net_http(self, method: str, a: dict) -> Any:
        route = self.http_routes.get(a["url"])
        if route is None or route == "timeout":
            raise SimError("TIMEOUT", f"no simulated response for {a['url']}", retryable=True)
        return {"status": route["status"], "headers": {}, "text": route.get("text"), "body_b64": None,
                "truncated": False}

    def _notify_post(self, method: str, a: dict) -> Any:
        self.notifications.append(dict(a))
        return {"id": len(self.notifications)}

    def _ui_prompt(self, method: str, a: dict) -> Any:
        if method == "toast":
            self.notifications.append({"kind": "toast", "text": a.get("text")})
            return {"ok": True}
        if method == "overlay":
            self.notifications.append({"kind": "overlay", "text": a.get("text"),
                                       "duration_ms": a.get("duration_ms")})
            return {"shown": True}
        if method == "dialog":
            self.prompts_asked.append(dict(a))
            answer = self.prompt_answers.get(a.get("title"))
            if isinstance(answer, str) and answer in (a.get("buttons") or ["OK"]):
                return {"answered": True, "button": answer}
            return {"answered": False, "reason": "timeout"}
        if method == "form":
            self.prompts_asked.append(dict(a))
            answer = self.prompt_answers.get(a.get("title"))
            if isinstance(answer, dict):
                return {"answered": True, "values": dict(answer)}
            return {"answered": False, "reason": "timeout"}
        self.prompts_asked.append(dict(a))
        answer = self.prompt_answers.get(a.get("title"))
        if answer == "TIMEOUT":  # scripted timeout: nobody answers the prompt
            return {"answered": False, "reason": "timeout"}
        if answer is None:      # unscripted prompt: the sim user is not home either
            return {"answered": False, "reason": "timeout"}
        return {"answered": True, "answer": answer, "confirmed": answer in (True, "yes")}

    def _store(self, method: str, a: dict) -> Any:
        scope = a["scope"]
        owner = self.profile_id if scope == "profile" else None

        def key_of(k: str) -> tuple:
            return (scope, owner, k)

        if method == "get":
            k = key_of(a["key"])
            return {"value": self.kv[k]} if k in self.kv else {}
        if method == "set":
            self.kv[key_of(a["key"])] = a["value"]
            return {"ok": True}
        if method == "delete":
            self.kv.pop(key_of(a["key"]), None)
            return {"ok": True}
        if method == "list":
            prefix = a.get("prefix", "")
            return {"keys": sorted(k[2] for k in self.kv if k[0] == scope and k[1] == owner
                                   and k[2].startswith(prefix))}
        raise SimError("INVALID_ARGS", f"store.{method} is not simulated")

    def _state(self, method: str, a: dict) -> Any:
        if method == "get":
            return {"state": dict(self.state_blob)}
        if method == "set":
            self.state_blob = dict(a["state"])
            return {"ok": True}
        raise SimError("INVALID_ARGS", f"state.{method} is not simulated")

    def _files_private(self, method: str, a: dict) -> Any:
        path = a.get("path", "")
        if method == "read":
            if path not in self.files:
                raise SimError("NOT_FOUND", f"{path} does not exist")
            return {"content": self.files[path]}
        if method == "write":
            self.files[path] = a["content"]
            return {"ok": True}
        if method == "list":
            return {"entries": sorted(self.files)}
        self.files.pop(path, None)
        return {"ok": True}

    # ---- P8 part 2 handlers -------------------------------------------------

    def add_tree(self, uri: str, files: dict | None = None) -> None:
        """Test helper: grants a SAF tree with initial {relpath: content}."""
        self.trees[uri] = dict(files or {})

    @staticmethod
    def _segments(path: str) -> list[str] | None:
        if not path:
            return []
        if path.startswith("/") or "\\" in path or ":" in path:
            return None
        segs = [s for s in path.split("/") if s]
        if any(s in (".", "..") for s in segs):
            return None
        return segs

    def _tree_pick(self, tree: str) -> dict:
        if tree not in self.trees:
            raise SimError("SCOPE_VIOLATION", f'tree "{tree}" is not in this run\'s files.shared grant')
        return self.trees[tree]

    def _files_shared(self, method: str, a: dict) -> Any:
        import base64
        tree = a.get("tree", "")
        if method in ("list", "read", "write", "delete"):
            store = self._tree_pick(tree)
            segs = self._segments(a.get("path", ""))
            if segs is None:
                raise SimError("SCOPE_VIOLATION", "path escapes the picked tree (.. / absolute)")
            key = "/".join(segs)
            if method == "list":
                names = sorted({k.split("/")[len(segs)] for k in store if key == "" or k.startswith(key + "/")})
                entries = []
                for n in names:
                    full = f"{key}/{n}" if key else n
                    is_dir = any(k.startswith(full + "/") for k in store)
                    entries.append({"name": n, "dir": is_dir, "size": 0 if is_dir else len(str(store.get(full, "")))})
                return {"entries": entries, "truncated": False}
            if method == "read":
                if key not in store:
                    raise SimError("NOT_FOUND", f"{key} does not exist in the tree")
                content = store[key]
                raw = content if isinstance(content, bytes) else str(content).encode("utf-8")
                return {"body_b64": base64.b64encode(raw).decode("ascii"), "truncated": False}
            if method == "write":
                raw = base64.b64decode(a.get("body_b64", ""))
                if len(raw) > 5 * 1024 * 1024:
                    raise SimError("INVALID_ARGS", "content exceeds the 5242880-byte cap")
                store[key] = raw
                return {"written": len(raw)}
            if key not in store:
                raise SimError("NOT_FOUND", f"{key} does not exist in the tree")
            store.pop(key)
            return {"deleted": True}
        if method in ("media_list", "media_read", "media_write", "media_delete"):
            grant_media = self.media_scope
            if not grant_media:
                raise SimError("SCOPE_VIOLATION", 'MediaStore access needs "media": true in the files.shared grant scope')
            if not self.media_permission:
                raise SimError("NEEDS_SETUP", "MediaStore reads need the shared-storage runtime permission")
            if method == "media_list":
                mtype = a.get("media_type", "files")
                rows = [{"id": r["id"], "name": r["name"], "media_type": r["media_type"], "size": r["size"]}
                        for r in self.media_rows if r["media_type"] == mtype][: min(int(a.get("limit", 100)), 500)]
                return {"rows": rows}
            if method == "media_read":
                for r in self.media_rows:
                    if r["id"] == a.get("id"):
                        raw = r["content"] if isinstance(r["content"], bytes) else str(r["content"]).encode("utf-8")
                        return {"body_b64": base64.b64encode(raw).decode("ascii"), "truncated": False}
                raise SimError("NOT_FOUND", f"media row {a.get('id')} does not exist")
            if method == "media_write":
                raw = base64.b64decode(a.get("body_b64", ""))
                rid = str(1000 + len(self.media_rows))
                self.media_rows.append({"id": rid, "name": a.get("name", "?"),
                                        "media_type": a.get("media_type", "files"), "size": len(raw), "content": raw})
                return {"id": rid}
            for i, r in enumerate(self.media_rows):
                if r["id"] == a.get("id"):
                    self.media_rows.pop(i)
                    return {"deleted": True}
            raise SimError("NOT_FOUND", f"media row {a.get('id')} does not exist")
        raise SimError("INVALID_ARGS", f"unknown files.shared method '{method}'")

    def _net_ws(self, method: str, a: dict) -> Any:
        url = a.get("url", "")
        host = url.split("//", 1)[-1].split("/", 1)[0].split(":")[0]
        if self.ws_scope_domains is not None and host not in self.ws_scope_domains:
            raise SimError("SCOPE_VIOLATION", f'host "{host}" is not in this run\'s net.ws domain allowlist')
        if method == "connect":
            if url not in self.ws_endpoints:
                raise SimError("NOT_FOUND", f"sim has no endpoint at {url}")
            handle = f"ws-{len(self.ws_sent) + 1}"
            self.ws_sent.append({"method": "connect", "url": url, "handle": handle})
            return {"handle": handle}
        handle = a.get("handle", "")
        if not any(r.get("handle") == handle for r in self.ws_sent):
            raise SimError("NOT_FOUND", f"no open ws handle {handle}")
        if method == "send":
            self.ws_sent.append({"method": "send", "handle": handle, "text": a.get("text", "")})
            return {"sent": True}
        if method == "recv":
            queue = self.ws_endpoints.get(self._ws_url_of(handle), [])
            if queue:
                return {"kind": "text", "text": queue.pop(0)}
            return {"kind": "closed"}
        self.ws_sent.append({"method": "close", "handle": handle})
        return {"closed": True}

    def _ws_url_of(self, handle: str) -> str:
        for r in self.ws_sent:
            if r.get("handle") == handle and "url" in r:
                return r["url"]
        return ""

    def _net_server(self, method: str, a: dict) -> Any:
        if method == "status":
            return {"running": True, "port": 8082, "lan": False, "hook_count": len(self.hook_names)}
        if method == "hook_names":
            return {"names": list(self.hook_names)}
        raise SimError("INVALID_ARGS", f"unknown net.server method '{method}'")

    def _device_audio(self, method: str, a: dict) -> Any:
        if method == "volume":
            stream = a.get("stream", "music")
            if stream not in self.audio_state:
                raise SimError("INVALID_ARGS", "stream must be one of music|ring|notification|alarm")
            level, mx, muted = self.audio_state[stream]
            if a.get("level") is not None:
                want = int(a["level"])
                if not 0 <= want <= 100:
                    raise SimError("INVALID_ARGS", "level must be 0-100")
                level = round(want / 100 * mx)
                muted = want == 0
                self.audio_state[stream] = (level, mx, muted)
            pct = round(level / mx * 100) if mx else 0
            return {"stream": stream, "level": level, "max": mx, "muted": muted, "percent": pct}
        if method == "ringer":
            if a.get("mode") is not None:
                mode = a["mode"]
                if mode not in ("normal", "silent", "vibrate"):
                    raise SimError("INVALID_ARGS", "mode must be normal|silent|vibrate")
                self.ringer_mode = mode
            return {"mode": self.ringer_mode}
        if method == "dnd":
            return {"on": self.dnd_on, "interrupted": 0}
        if method == "speak":
            text = a.get("text", "")
            if not text:
                raise SimError("INVALID_ARGS", "text is required")
            self.tts_log.append({"text": text, "language": a.get("language"), "queued": bool(a.get("queue", False))})
            return {"spoken": True}
        if method == "vibrate":
            ms = int(a.get("ms", 200))
            if ms < 0 or ms > 10_000:
                raise SimError("INVALID_ARGS", "ms must be 0-10000")
            self.vibrate_log.append(ms)
            return {"vibrated": True}
        if method == "flashlight":
            want = bool(a.get("on"))
            if want and not self.torch_available:
                raise SimError("CAPABILITY_UNAVAILABLE", "the torch is unavailable (camera in use or no flash unit)")
            self.torch_on = want
            return {"on": self.torch_on}
        if method == "play_sound":
            tone = a.get("tone", "beep")
            if tone not in ("beep", "confirm", "alert"):
                raise SimError("INVALID_ARGS", "tone must be beep|confirm|alert")
            self.played_tones.append(tone)
            return {"played": True}
        raise SimError("INVALID_ARGS", f"unknown device.audio method '{method}'")

    def _device_power(self, method: str, a: dict) -> Any:
        if method == "battery":
            s = dict(self.battery_state)
            return s
        if method == "wake_lock":
            hold = a.get("hold", True)
            self.wake_lock_held = bool(hold)
            return {"held": self.wake_lock_held}
        if method == "keep_awake":
            self.keep_awake = bool(a.get("on"))
            return {"on": self.keep_awake}
        raise SimError("INVALID_ARGS", f"unknown device.power method '{method}'")

    def _ui_overlay(self, method: str, a: dict) -> Any:
        if not self.a11y_overlay_connected:
            raise SimError("CAPABILITY_UNAVAILABLE",
                           "overlays ride the accessibility service; enable AutoHost in Settings → Accessibility")
        if method == "highlight":
            for k in ("x", "y", "w", "h"):
                if a.get(k) is None:
                    raise SimError("INVALID_ARGS", f'"{k}" is required')
            entry = {"kind": "highlight", "x": int(a["x"]), "y": int(a["y"]), "w": int(a["w"]), "h": int(a["h"]),
                     "label": a.get("label"), "run_id": "__sim__"}
            self.overlays = [o for o in self.overlays if o["kind"] != "highlight"]
            self.overlays.append(entry)
            return {"shown": True}
        if method == "panel":
            text = a.get("text", "")
            entry = {"kind": "panel", "text": text, "corner": a.get("corner", "top"), "run_id": "__sim__"}
            self.overlays = [o for o in self.overlays if o["kind"] != "panel"]
            self.overlays.append(entry)
            return {"shown": True}
        if method == "clear":
            self.overlays = []
            return {"cleared": True}
        raise SimError("INVALID_ARGS", f"unknown ui.overlay method '{method}'")

    def _device_settings(self, method: str, a: dict) -> Any:
        table = a.get("table", "")
        if table not in ("system", "secure", "global"):
            raise SimError("INVALID_ARGS", "table must be system|secure|global")
        if method == "read":
            key = a.get("key", "")
            if not key:
                raise SimError("INVALID_ARGS", "key is required")
            value = self.settings_table.get((table, key))
            return {"value": value, "exists": value is not None}
        if method == "read_many":
            keys = a.get("keys") or []
            values = {k: self.settings_table.get((table, k)) for k in keys}
            return {"values": values}
        if method == "write":
            key = a.get("key", "")
            value = a.get("value", "")
            confirm = bool(a.get("confirm"))
            if not key:
                raise SimError("INVALID_ARGS", "\"key\" is required")
            if key in ("enabled_accessibility_services", "accessibility_enabled", "default_input_method",
                       "device_provisioned", "user_setup_complete", "secure_user_id"):
                self.settings_protected_write_blocked += 1
                raise SimError("SCOPE_VIOLATION", f"the key '{key}' is protected (owned by AutoHost's self-heal)")
            if len(value) > 1000:
                raise SimError("INVALID_ARGS", "value exceeds 1000 characters")
            if table in ("secure", "global") and not confirm:
                raise SimError("SCOPE_VIOLATION", "writing secure settings is gated: pass confirm=true")
            self.settings_table[(table, key)] = value
            return {"written": True, "table": table, "key": key,
                    "path": self.settings_write_path, "verified": True}
        raise SimError("INVALID_ARGS", f"unknown device.settings method '{method}'")

    def _tasks_run(self, method: str, a: dict) -> Any:
        if method != "run":
            raise SimError("INVALID_ARGS", f"unknown tasks.run method '{method}'")
        task = a.get("task", "")
        if task not in self.chain_results:
            raise SimError("NOT_FOUND", f"no task {task}")
        outcome = self.chain_results[task]
        if isinstance(outcome, SimError):
            raise outcome
        return {"run_id": f"chain-{len(self.chain_results)}", **outcome}

    def _android_direct(self, method: str, a: dict) -> Any:
        if method != "authorize":
            raise SimError("INVALID_ARGS", f"unknown android.api.direct method '{method}'")
        if not self.raw_bridge_authorized:
            if not self.raw_api_switch:
                raise SimError("INSECURE_FEATURE_DISABLED",
                               "the raw_api switch is off (Settings → Developer & security)",
                               fix_action="OPEN_INSECURE_SWITCH")
            raise SimError("PERMISSION_DENIED", "the run has no android.api.direct grant",
                           fix_action="OPEN_PERMISSION_REVIEW")
        return {"authorized": True, "worker_pid": 4242}

    def _system(self, method: str, a: dict) -> Any:
        return {"api": 29, "manufacturer": "sim", "model": "FakeDevice", "locale": "en-US",
                "screen": {"w": 1080, "h": 2400, "dpi": 420}, "tiers": {"a11y": "sim"}}


    def _device_notifications(self, method: str, a: dict) -> Any:
        if method == "list":
            pkg = a.get("package")
            include_ongoing = a.get("include_ongoing", True)
            rows = [n for n in self.system_notifications
                    if (pkg is None or n["package"] == pkg) and (include_ongoing or not n["ongoing"])]
            return {"count": len(rows), "notifications": rows}
        if method == "read":
            for n in self.system_notifications:
                if n["id"] == a.get("id"):
                    return n
            raise SimError("NOT_FOUND", f"no notification {a.get('id')}")
        if method == "press":
            for n in self.system_notifications:
                if n["id"] == a.get("id"):
                    action = a.get("action", 0)
                    if isinstance(action, str):
                        match = next((x for x in n["actions"] if x["label"] == action), None)
                        if match is None:
                            raise SimError("INVALID_ARGS", f"no action labelled {action}")
                        return {"sent": True, "action_index": match["index"]}
                    if not n["actions"]:
                        raise SimError("INVALID_ARGS", "the notification has no action buttons")
                    return {"sent": True, "action_index": int(action)}
            raise SimError("NOT_FOUND", f"no notification {a.get('id')}")
        if method == "dismiss":
            before = len(self.system_notifications)
            self.system_notifications = [n for n in self.system_notifications if n["id"] != a.get("id")]
            if len(self.system_notifications) == before:
                raise SimError("NOT_FOUND", f"no notification {a.get('id')}")
            return {"dismissed": True}
        if method == "dismiss_all":
            count = len([n for n in self.system_notifications if not n["ongoing"]])
            self.system_notifications = [n for n in self.system_notifications if n["ongoing"]]
            return {"dismissed": count}
        if method == "wait_for":
            import re as _re
            pkg = a.get("package")
            title = a.get("title_regex")
            text = a.get("text_regex")
            for n in self.system_notifications:
                if pkg is not None and n["package"] != pkg:
                    continue
                if title is not None and not _re.search(title, n.get("title", "")):
                    continue
                if text is not None and not _re.search(text, n.get("text", "")):
                    continue
                return {"found": True, "notification": n}
            raise SimError("TIMEOUT", "no matching notification within the timeout")
        raise SimError("INVALID_ARGS", f"unknown device.notifications method {method}")

    def _device_clipboard(self, method: str, a: dict) -> Any:
        if method == "get":
            if not self.clipboard_focused:
                raise SimError(
                    "CAPABILITY_UNAVAILABLE",
                    "Reading the clipboard requires this app to be in focus on Android 10+. "
                    "Use device.ime.clipboard_get while the AutoHost keyboard is selected for background reads.",
                    fix_action="SELECT_IME",
                )
            return {"text": self.clipboard or "", "focused": True, "empty": not self.clipboard}
        if method == "set":
            self.clipboard = a.get("text")
            return {"set": True}
        raise SimError("INVALID_ARGS", f"unknown device.clipboard method {method}")

    def _device_intent(self, method: str, a: dict) -> Any:
        # The sim mirrors the policy: http(s) URLs and known settings pages pass, the rest need a scope.
        if method == "open_url":
            url = a.get("url", "")
            if url.split("://", 1)[0] not in ("http", "https"):
                raise SimError("SCOPE_VIOLATION", "only http/https URLs are allowed")
            self.intents_dispatched.append({"method": method, **a})
            return {"dispatched": True, "verified": False}
        if method == "open_settings":
            page = a.get("page", "")
            if page not in ("wifi", "bluetooth", "display", "sound", "applications", "battery",
                            "date", "locale", "input", "accessibility", "notification_listener",
                            "exact_alarms", "app_details", "settings"):
                raise SimError("SCOPE_VIOLATION", f"unknown settings page '{page}'")
            self.intents_dispatched.append({"method": method, **a})
            return {"dispatched": True, "verified": False}
        if method == "start_activity":
            self.intents_dispatched.append({"method": method, **a})
            return {"dispatched": True, "verified": False}
        if method == "broadcast":
            self.intents_dispatched.append({"method": method, **a})
            return {"dispatched": True, "verified": False}
        raise SimError("INVALID_ARGS", f"unknown device.intent method {method}")

    def _device_events(self, method: str, a: dict) -> Any:
        if method == "recent":
            kinds = a.get("kinds")
            limit = int(a.get("limit", 50))
            rows = [e for e in self.hub_events if kinds is None or e["kind"] in kinds]
            return {"events": rows[-limit:]}
        raise SimError("INVALID_ARGS", f"unknown device.events method {method}")
