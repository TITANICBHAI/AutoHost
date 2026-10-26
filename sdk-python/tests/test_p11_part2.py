"""P11 part 2 through the simulator + the PC CLI: extensibility and release (plan §18 P11).

Covers, in order:
  - P11-03 consumer: the CLI repo module — index parse parity with RepoIndex.kt, select verdicts
    (incl. SemVer §11 ordering), sha verification, and the https/file fetch refusal rule.
  - P11-04: app packs end to end — the sim's run-start injection into config.packs for all three
    runtimes, the SDK `autohost.packs` helpers (selector/label fallback), the honest PackMissing
    path, and the sample `samples/pack-presser`.
  - P11-06: the web inspector — page render, the token-safe /api proxy, and 404s.
  - P11-07: the PC bridge — a real loopback round trip (bridge ← fake phone) with the
    first-frame token rule and the BRIDGE_DOWN honesty when no phone is connected.
  - P11-08/Kotlin mirror: the backup codec rules are Kotlin-side; here we pin the CLI-visible
    behaviors that matter on the PC (none — the codec is device-side; the sample covers runs).
  - P11-12: `samples/photo-organizer` — the port exercise runs in the sim (copy → folders,
    delete_originals off by default).

Run: python3 -m unittest discover -s tests -t tests -p "test_*.py"   (from sdk-python/)
"""
import base64
import io
import json
import socket
import tempfile
import threading
import time
import unittest
import urllib.request
from pathlib import Path
from zipfile import ZipFile

import support

from autohost.packs import PackMissing, SelectorMissing
from autohost_sim import FakeDevice, run_task

SAMPLES = support.SAMPLES

from autohost_cli import repo as repo_mod
from autohost_cli import bridge as bridge_mod
from autohost_cli import webui as webui_mod


# ----------------------------------------------------------------------------------------------
# P11-03 (part 2): the repo consumer
# ----------------------------------------------------------------------------------------------

INDEX_JSON = json.dumps({
    "schema": 1,
    "name": "demo repo",
    "updated_at": "2026-10-26T00:00:00Z",
    "tasks": [
        {"id": "com.example.alpha", "version": "1.0.0", "title": "Alpha",
         "source": "https://example.com/alpha.aatask"},
        {"id": "com.example.beta", "version": "2.0.0", "title": "Beta", "sha256": "a" * 64,
         "source": "https://example.com/beta.aatask", "runtime": "python"},
        {"id": "com.example.old", "version": "0.9.0", "source": "https://example.com/old.aatask"},
        {"id": "com.example.needsnew", "version": "1.1.0", "min_host_api": 99,
         "source": "https://example.com/n.aatask"},
    ],
})


class RepoParseTest(unittest.TestCase):
    """The Python mirror matches RepoIndex.kt's contract (fail-closed, first-dup wins)."""

    def test_parse_ok_and_verdicts(self):
        parsed = repo_mod.parse(INDEX_JSON)
        self.assertTrue(parsed.ok)
        self.assertEqual(4, len(parsed.index.entries))
        self.assertEqual("demo repo", parsed.index.name)

    def test_select_matches_the_semver_rules(self):
        parsed = repo_mod.parse(INDEX_JSON)
        entries = {e.id: e for e in parsed.index.entries}
        self.assertEqual("INSTALL", repo_mod.select(entries["com.example.alpha"], None, 8)[0])
        self.assertEqual("UPGRADE", repo_mod.select(entries["com.example.beta"], "1.5.0", 8)[0])
        self.assertEqual(("UPGRADE", "1.5.0 → 2.0.0"), repo_mod.select(entries["com.example.beta"], "1.5.0", 8))
        self.assertEqual("UP_TO_DATE", repo_mod.select(entries["com.example.alpha"], "1.0.0", 8)[0])
        verdict, message = repo_mod.select(entries["com.example.old"], "1.0.0", 8)
        self.assertEqual("REPO_OLDER", verdict)
        self.assertIn("downgrades need an explicit uninstall", message)
        self.assertEqual("HOST_TOO_OLD", repo_mod.select(entries["com.example.needsnew"], None, 8)[0])

    def test_prerelease_sorts_before_release_and_build_metadata_is_ignored(self):
        parsed = repo_mod.parse(INDEX_JSON)
        alpha = {e.id: e for e in parsed.index.entries}["com.example.alpha"]
        self.assertEqual("UPGRADE", repo_mod.select(alpha, "1.0.0-rc.1", 8)[0])
        self.assertEqual("UP_TO_DATE", repo_mod.select(alpha, "1.0.0+build.7", 8)[0])

    def test_malformed_and_duplicate_entries(self):
        # A wrong schema number is an ISSUE but the Kotlin mirror still parses the entries
        # (only a malformed root / missing tasks nulls the index) — parity pinned here.
        bad = repo_mod.parse(json.dumps({"schema": 2, "tasks": []}))
        self.assertTrue(bad.ok)
        self.assertTrue(any(i.code == "SCHEMA" for i in bad.issues))
        nothing = repo_mod.parse("[]")
        self.assertFalse(nothing.ok)
        dup = repo_mod.parse(json.dumps({"schema": 1, "tasks": [
            {"id": "a.b", "version": "1.0.0", "source": "https://x/a"},
            {"id": "a.b", "version": "2.0.0", "source": "https://x/b"},
        ]}))
        self.assertTrue(dup.ok)
        self.assertEqual(1, len(dup.index.entries))
        self.assertEqual("1.0.0", dup.index.entries[0].version)  # first wins
        self.assertTrue(any(i.code == "DUP_ID" for i in dup.issues))
        self.assertTrue(any(i.code == "UNKNOWN_KEY" for i in repo_mod.parse(json.dumps({
            "schema": 1, "tasks": [{"id": "a.b", "version": "1.0.0", "source": "https://x/a", "wat": 1}]})).issues))

    def test_sha_verification_case_insensitive(self):
        self.assertTrue(repo_mod.verify_sha("A" * 64, "a" * 64))
        self.assertFalse(repo_mod.verify_sha("a" * 64, "b" * 64))
        self.assertFalse(repo_mod.verify_sha("a" * 63, "a" * 63))

    def test_fetch_refuses_non_https_remote_schemes(self):
        with self.assertRaises(ValueError):
            repo_mod.fetch("http://example.com/index.json")
        with self.assertRaises(ValueError):
            repo_mod.fetch("ftp://example.com/index.json")

    def test_fetch_file_scheme_reads_local_indexes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "index.json"
            path.write_text(INDEX_JSON, encoding="utf-8")
            raw = repo_mod.fetch(path.as_uri())
            self.assertTrue(repo_mod.parse(raw.decode("utf-8")).ok)


# ----------------------------------------------------------------------------------------------
# P11-04: app packs
# ----------------------------------------------------------------------------------------------

PACK_ID = "app.autohost.sample.presser-pack"
LOGIN_BUTTON_DOC = {
    "op": "and",
    "terms": [{"text": {"labels": ["Log in"], "mode": "eq", "case_sensitive": False}}],
}
PACK = {
    "id": PACK_ID, "target": "com.example.presser", "version": "1.1.0",
    "selectors": {"login_button": dict(LOGIN_BUTTON_DOC)},
    "screens": {"login": {"markers": ["Log in", "Username"]}},
    "labels": {"en": {"login_button": "Log in"}, "de": {"login_button": "Anmelden"},
               "de-AT": {"login_button": "Anmelden"}},
    "tips": ["The login screen auto-focuses the username field."],
}

PACK_SCREENS = {
    "login": {"package": "com.example.presser",
              "nodes": [{"id": "fx_login", "text": "Log in", "cls": "android.widget.Button", "clickable": True}],
              "on_click": {"fx_login": "done"}},
    "done": {"package": "com.example.presser",
             "nodes": [{"id": "fx_done", "text": "Welcome", "cls": "android.widget.TextView"}]},
}


def pack_device(**kw):
    packs = kw.pop("installed_packs", None)
    if packs is None:
        packs = {PACK_ID: dict(PACK)}
    return FakeDevice(PACK_SCREENS, launch_map={"com.example.presser": "login"}, installed_packs=packs, **kw)


class PackSampleTest(unittest.TestCase):
    """samples/pack-presser — the selector lives in the pack, the task only names it."""

    def run_sample(self, device, config=None):
        return run_task(SAMPLES / "pack-presser", device=device,
                        config={"target_package": "com.example.presser", **(config or {})})

    def test_press_via_pack_succeeds_and_names_the_pack_version(self):
        outcome = self.run_sample(pack_device())
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertEqual("pressed", outcome.result.code)
        self.assertEqual("1.1.0", outcome.result.data["pack_version"])
        self.assertEqual("Log in", outcome.result.data["label"])

    def test_locale_label_falls_back_like_the_kotlin_side(self):
        outcome = self.run_sample(pack_device(), config={"locale": "de-CH"})  # language fallback de→Anmelden
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertEqual("Anmelden", outcome.result.data["label"])

    def test_missing_pack_is_an_honest_failure_not_a_silent_fallback(self):
        outcome = self.run_sample(pack_device(installed_packs={}))  # pack absent at run start
        self.assertEqual("FAILED", outcome.status)

    def test_no_packs_key_at_all_fails_with_the_typed_error(self):
        # A task WITHOUT uses_packs resolution (device with no packs): config.packs never lands.
        device = FakeDevice(PACK_SCREENS, launch_map={"com.example.presser": "login"})
        outcome = self.run_sample(device)
        self.assertEqual("FAILED", outcome.status)
        self.assertIn("PackMissing", outcome.message or "")


class PackSdkTest(unittest.TestCase):
    """The SDK helpers over ctx.config.packs — selector/screen/label/tips + honest errors."""

    def run_py(self, source: str, device: FakeDevice, config: dict):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / "t"
            d.mkdir()
            (d / "task.json").write_text(json.dumps({
                "schema": 1, "id": "t.packs", "name": "t", "version": "1.0.0",
                "uses_packs": [PACK_ID],
                "runtime": {"type": "python", "entry": "main.py"},
            }), encoding="utf-8")
            (d / "main.py").write_text(source, encoding="utf-8")
            return run_task(d, device=device, config=config)

    def test_selector_screen_label_tips(self):
        source = f"""
from autohost import ctx
import autohost.packs as packs

def main():
    pack = packs.pack(ctx, "{PACK_ID}")
    return ctx.result("ok", {{
        "sel": packs.selector(ctx, "{PACK_ID}", "login_button").to_json(),
        "screen": packs.screen(ctx, "{PACK_ID}", "login"),
        "label_de": packs.label(ctx, "{PACK_ID}", "login_button", "de"),
        "label_fr": packs.label(ctx, "{PACK_ID}", "login_button", "fr"),
        "tips": packs.tips(ctx, "{PACK_ID}"),
        "version": pack["version"],
    }})
"""
        outcome = self.run_py(source, pack_device(), {})
        self.assertEqual("SUCCEEDED", outcome.status, outcome.message)
        data = outcome.result.data
        self.assertEqual(LOGIN_BUTTON_DOC, data["sel"])  # rebuilt Selector round-trips its document
        self.assertEqual(["Log in", "Username"], data["screen"]["markers"])
        self.assertEqual("Anmelden", data["label_de"])
        self.assertEqual("Log in", data["label_fr"])  # fr → en fallback
        self.assertEqual(["The login screen auto-focuses the username field."], data["tips"])

    def test_missing_pack_and_selector_raise_typed_errors(self):
        # Covered end-to-end by the sample tests above (PackMissing → FAILED) and the
        # selector-missing case below; this row keeps the typed classes importable contract.
        self.assertTrue(issubclass(PackMissing, Exception))
        self.assertTrue(issubclass(SelectorMissing, Exception))

    def test_unknown_selector_is_selector_missing(self):
        source = f"""
from autohost import ctx
import autohost.packs as packs

def main():
    try:
        packs.selector(ctx, "{PACK_ID}", "nope")
        return ctx.result("wrong", {{}})
    except packs.SelectorMissing:
        return ctx.result("ok", {{}})
"""
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp) / "t"
            d.mkdir()
            (d / "task.json").write_text(json.dumps({
                "schema": 1, "id": "t.packs2", "name": "t", "version": "1.0.0",
                "uses_packs": [PACK_ID],
                "runtime": {"type": "python", "entry": "main.py"},
            }), encoding="utf-8")
            (d / "main.py").write_text(source, encoding="utf-8")
            outcome = run_task(d, device=pack_device(), config={})
            self.assertEqual("SUCCEEDED", outcome.status, outcome.message)


# ----------------------------------------------------------------------------------------------
# P11-06: the web inspector
# ----------------------------------------------------------------------------------------------

class FakePageClient:
    """A stand-in DevApiClient with the same `.call(method, params)` surface."""

    def __init__(self, responses: dict, calls: list):
        self.responses = responses
        self.calls = calls
        self.port = 8080

    def call(self, method, params=None):
        self.calls.append((method, params))
        out = self.responses.get(method)
        if isinstance(out, Exception):
            raise out
        return out or {}


class WebUiTest(unittest.TestCase):
    def setUp(self):
        self.calls: list = []
        self.client = FakePageClient({
            "device.ui.snapshot": {"root": {"cls": "root", "text": "top", "children": []}},
            "device.screenshot": {"data_b64": base64.b64encode(b"png").decode()},
            "missing.method": RuntimeError("upstream exploded"),
        }, self.calls)
        self.server = None
        for port in range(8199, 8299):
            try:
                handler = type("H", (webui_mod.InspectorHandler,),
                               {"client": self.client, "page": webui_mod.index_html(8080)})
                self.server = webui_mod.ThreadingHTTPServer(("127.0.0.1", port), handler)
                self.port = port
                break
            except OSError:
                continue
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def get(self, path: str) -> tuple[int, bytes]:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}") as response:
                return response.status, response.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read()

    def post(self, path: str, body: dict) -> tuple[int, dict]:
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
                                         data=json.dumps(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.loads(response.read().decode())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read().decode())

    def test_page_is_served_and_never_contains_the_token(self):
        status, body = self.get("/")
        self.assertEqual(200, status)
        self.assertIn(b"AutoHost inspector", body)
        self.assertNotIn(b"Bearer", body)  # the token never rides the page

    def test_api_proxy_forwards_methods_and_params(self):
        status, payload = self.post("/api/device.ui.snapshot", {"compact": False})
        self.assertEqual(200, status)
        self.assertEqual("root", payload["result"]["root"]["cls"])
        self.assertEqual(("device.ui.snapshot", {"compact": False}), self.calls[0])

    def test_unknown_paths_404_and_upstream_errors_surface_honestly(self):
        status, _ = self.get("/nope")
        self.assertEqual(404, status)
        status, payload = self.post("/api/missing.method", {})
        self.assertEqual(502, status)
        self.assertIn("error", payload)

    def test_the_page_polls_through_the_proxy_only(self):
        # The page's JS uses /api/... exclusively (no direct Dev API port, no token in JS).
        _, body = self.get("/")
        self.assertNotIn(b"127.0.0.1:8080", body)
        self.assertIn(b"device.ui.snapshot", body)
        self.assertIn(b"'/api/' + method", body)  # every call is built from the /api prefix


# ----------------------------------------------------------------------------------------------
# P11-07: the PC bridge (loopback)
# ----------------------------------------------------------------------------------------------

class FakePhone(threading.Thread):
    """Dials the bridge exactly like ReverseDevApiLink.kt: handshake, first-frame token, serve."""

    def __init__(self, port: int, token: str, reply: dict):
        super().__init__(daemon=True)
        self.port = port
        self.token = token
        self.reply = reply
        self.errors: list[str] = []
        self.release_after_reply = threading.Event()

    def run(self):
        try:
            sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
            key = "dGhlIHNhbXBsZSBub25jZQ=="
            sock.sendall((
                "GET /reverse HTTP/1.1\r\nHost: pc\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            ).encode())
            # read the 101
            response = bytearray()
            while b"\r\n\r\n" not in response:
                chunk = sock.recv(4096)
                if not chunk:
                    return
                response += chunk
            first = bridge_mod.encode_text(json.dumps({"token": self.token}).encode(), mask=True)
            sock.sendall(first)
            # serve one request then exit
            opcode, payload = bridge_mod.read_frame(sock, expect_masked=False)
            if opcode != bridge_mod.OP_TEXT:
                self.errors.append(f"expected text frame, got {opcode}")
                return
            request = json.loads(payload.decode())
            sock.sendall(bridge_mod.encode_text(json.dumps({
                "jsonrpc": "2.0", "id": request.get("id"), "result": self.reply,
            }).encode(), mask=True))
            self.release_after_reply.wait(timeout=5)
            sock.close()
        except Exception as exc:  # noqa: BLE001 — the test asserts on this list
            self.errors.append(str(exc))


class BridgeTest(unittest.TestCase):
    @staticmethod
    def wait_for(predicate, timeout: float = 5) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return True
            time.sleep(0.02)
        return predicate()

    def start_bridge(self, token: str) -> tuple[bridge_mod.Bridge, int, int, int]:
        bridge = bridge_mod.Bridge(0, 0, token)
        listen_sock = socket.socket()
        listen_sock.bind(("127.0.0.1", 0))
        listen_port = listen_sock.getsockname()[1]
        listen_sock.close()
        http_sock = socket.socket()
        http_sock.bind(("127.0.0.1", 0))
        http_port = http_sock.getsockname()[1]
        http_sock.close()
        bridge.listen_port, bridge.http_port = listen_port, http_port
        thread = threading.Thread(target=bridge.serve_forever, daemon=True)
        thread.start()
        proxy = bridge_mod.start_http_proxy(bridge, http_port)
        return bridge, listen_port, http_port, proxy

    def test_end_to_end_round_trip_with_first_frame_token(self):
        bridge, listen_port, http_port, proxy = self.start_bridge("tok-1")
        phone = FakePhone(listen_port, "tok-1", {"status": "ok"})
        try:
            phone.start()
            self.assertTrue(self.wait_for(lambda: bridge.state == "connected"),
                            f"bridge did not connect; state={bridge.state!r}")
            body = json.dumps({"jsonrpc": "2.0", "id": 3, "method": "system.ping"}).encode()
            request = urllib.request.Request(f"http://127.0.0.1:{http_port}/rpc", data=body,
                                             headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(request, timeout=5) as response:
                payload = json.loads(response.read().decode())
            self.assertEqual(3, payload["id"])
            self.assertEqual({"status": "ok"}, payload["result"])
            self.assertEqual(1, bridge.handshakes)
            self.assertEqual("connected", bridge.state)
        finally:
            phone.release_after_reply.set()
            bridge.stop()
            proxy.shutdown()
            proxy.server_close()

    def test_wrong_token_is_refused_and_the_proxy_reports_bridge_down(self):
        bridge, listen_port, http_port, proxy = self.start_bridge("expected")
        try:
            phone = FakePhone(listen_port, "WRONG", {})
            phone.start()
            self.assertTrue(self.wait_for(lambda: bridge.auth_failures == 1),
                            f"wrong token was not processed; state={bridge.state!r}")
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "system.ping"}).encode()
            request = urllib.request.Request(f"http://127.0.0.1:{http_port}/rpc", data=body, method="POST")
            try:
                urllib.request.urlopen(request, timeout=3)
                self.fail("expected 502")
            except urllib.error.HTTPError as exc:
                self.assertEqual(502, exc.code)
                payload = json.loads(exc.read().decode())
                self.assertEqual("BRIDGE_DOWN", payload["error"]["code"])
            self.assertEqual(0, bridge.handshakes)
            self.assertEqual(1, bridge.auth_failures)
        finally:
            bridge.stop()
            proxy.shutdown()
            proxy.server_close()

    def test_no_phone_means_an_honest_502(self):
        bridge, _listen_port, http_port, proxy = self.start_bridge("t")
        try:
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "system.ping"}).encode()
            request = urllib.request.Request(f"http://127.0.0.1:{http_port}/rpc", data=body, method="POST")
            try:
                urllib.request.urlopen(request, timeout=3)
                self.fail("expected 502")
            except urllib.error.HTTPError as exc:
                self.assertEqual(502, exc.code)
                self.assertEqual("BRIDGE_DOWN", json.loads(exc.read().decode())["error"]["code"])
        finally:
            bridge.stop()
            proxy.shutdown()
            proxy.server_close()


# ----------------------------------------------------------------------------------------------
# P11-12: the port exercise sample
# ----------------------------------------------------------------------------------------------

class PhotoOrganizerSample(unittest.TestCase):
    def _device(self):
        device = FakeDevice({}, launch_map={})
        device.add_grants("media", "media.write", "files.shared")
        device.add_media([
            {"id": 1, "kind": "image", "display_name": "a.jpg", "size_bytes": 3, "added_ms": 1735689600000},  # 2025-01-01
            {"id": 2, "kind": "image", "display_name": "b.jpg", "size_bytes": 3, "added_ms": 1740787200000},  # 2025-03-01
            {"id": 3, "kind": "image", "display_name": "c.jpg", "size_bytes": 3, "added_ms": 1735689600000},
        ])
        device.add_tree("primary:Pictures", {})
        return device

    def test_copy_into_month_folders_and_no_delete_by_default(self):
        device = self._device()
        outcome = run_task(SAMPLES / "photo-organizer", device=device, config={"max_items": 50})
        self.assertEqual("SUCCEEDED", outcome.status, outcome.message)
        data = outcome.result.data
        self.assertEqual(3, data["copied"])
        self.assertEqual(0, data["deleted"])
        self.assertEqual(2, len(data["folders"]))  # 2025-01 and 2025-03
        tree = device.trees["primary:Pictures"]
        self.assertIn("Photos/2025-01/a.jpg", tree)
        self.assertIn("Photos/2025-03/b.jpg", tree)
        # originals survive: the default is a copy, never a move
        self.assertEqual(3, len([row for row in device.media_rows if row.get("id") in (1, 2, 3)]))

    def test_delete_originals_is_explicit(self):
        device = self._device()
        outcome = run_task(SAMPLES / "photo-organizer", device=device,
                           config={"max_items": 50, "delete_originals": True})
        self.assertEqual("SUCCEEDED", outcome.status, outcome.message)
        self.assertEqual(3, outcome.result.data["deleted"])


if __name__ == "__main__":
    unittest.main()
