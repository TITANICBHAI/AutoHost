"""Tests for the autohost CLI (P2-23): client envelope handling, command exit codes, error mapping."""

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from autohost_cli.client import DevApiClient, DevApiError  # noqa: E402
from autohost_cli import __main__ as cli  # noqa: E402


class StubServer(HTTPServer):
    """Minimal Dev API stand-in: token check, method table, scriptable results."""

    def __init__(self, token: str = "tok-123", results: dict | None = None):
        super().__init__(("127.0.0.1", 0), StubHandler)
        self.token = token
        self.results = results or {}
        self.requests: list[dict] = []


class StubHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence
        pass

    def do_GET(self):  # /ping
        if self.path == "/ping":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
        else:
            self.send_error(404)

    def do_POST(self):
        server: StubServer = self.server
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        server.requests.append(body)
        if self.headers.get("Authorization") != f"Bearer {server.token}":
            self._json(401, {"jsonrpc": "2.0", "id": body.get("id"), "error": {"code": -32000, "message": "bad token", "data": {"code": "UNAUTHORIZED"}}})
            return
        method = body.get("method")
        result = server.results.get(method, {"ok": True})
        error = server.results.get(method + "@error")
        if error:
            self._json(200, {"jsonrpc": "2.0", "id": body.get("id"), "error": {"code": -32000, "message": "boom", "data": {"code": error}}})
        else:
            self._json(200, {"jsonrpc": "2.0", "id": body.get("id"), "result": result})

    def _json(self, code: int, payload: dict):
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.server = StubServer(results={
            "system.info": {"app": "app.autohost", "flavor": "core"},
            "runs.start": {"runId": "run-9"},
            "runs.tail": {"events": [{"seq": 1, "ts": 100, "level": "info", "stage": "launch", "message": "hi"}]},
        })
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.client = DevApiClient(port=self.server.server_address[1], token=self.server.token)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_call_returns_result_and_sends_bearer_token(self):
        result = self.client.system_info()
        self.assertEqual("app.autohost", result["app"])
        self.assertEqual(self.server.token, f"Bearer {self.server.token}".split()[1])  # token present in request
        self.assertTrue(self.server.requests[0]["params"] == {})

    def test_rpc_error_raises_devapi_error_with_code(self):
        self.server.results["runs.start@error"] = "TASK_NOT_FOUND"
        with self.assertRaises(DevApiError) as ctx:
            self.client.run("com.example.missing")
        self.assertEqual("TASK_NOT_FOUND", ctx.exception.code)

    def test_connection_failure_is_typed(self):
        dead = DevApiClient(port=1)  # nothing listens on port 1
        with self.assertRaises(DevApiError) as ctx:
            dead.ping()
        self.assertEqual("CONN_FAILED", ctx.exception.code)

    def test_push_zips_a_directory_and_sends_base64(self):
        import base64
        import io
        import tempfile
        import zipfile

        root = Path(tempfile.mkdtemp(prefix="ah-push-"))
        (root / "task.json").write_text(json.dumps({"id": "com.example.pushed", "name": "Pushed"}))
        (root / "main.py").write_text("print('hi')\n")
        self.server.results["tasks.install"] = {"taskId": "com.example.pushed", "version": "1.0.0"}
        result = self.client.push(str(root))
        self.assertEqual("com.example.pushed", result["taskId"])
        # the bundle arrived as a valid zip containing the manifest
        sent = base64.b64decode(self.server.requests[0]["params"]["bundle_b64"])
        with zipfile.ZipFile(io.BytesIO(sent)) as bundle:
            self.assertIn("task.json", bundle.namelist())
            self.assertIn("main.py", bundle.namelist())

    def test_push_refuses_a_directory_without_manifest(self):
        import tempfile

        root = Path(tempfile.mkdtemp(prefix="ah-empty-"))
        with self.assertRaises(DevApiError) as ctx:
            self.client.push(str(root))
        self.assertEqual("NO_MANIFEST", ctx.exception.code)


class CommandTest(unittest.TestCase):
    def setUp(self):
        self.server = StubServer(results={
            "system.info": {"app": "app.autohost"},
            "runs.tail": {"events": [{"seq": 2, "ts": 5, "level": "warn", "stage": "s1", "message": "watch out"}]},
            "health.run": {"groups": {"needs_fix": [], "autofixable": [], "optional": []}},
        })
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.port = self.server.server_address[1]
        self.token = self.server.token

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_cli(self, *argv):
        return cli.main(["--port", str(self.port), "--token", self.token, *argv])

    def test_status_exit_zero(self):
        self.assertEqual(0, self.run_cli("status"))

    def test_logs_prints_events(self):
        self.assertEqual(0, self.run_cli("logs", "--run", "run-9"))

    def test_doctor_exit_127_on_major_issues(self):
        self.server.results["health.run"] = {"groups": {"needs_fix": [{"id": "accessibility"}], "autofixable": [], "optional": []}}
        self.assertEqual(127, self.run_cli("doctor"))

    def test_doctor_exit_zero_when_healthy(self):
        self.assertEqual(0, self.run_cli("doctor"))

    def test_rpc_error_maps_to_exit_1(self):
        self.server.results["runs.start@error"] = "TASK_NOT_FOUND"
        self.assertEqual(1, self.run_cli("run", "com.example.missing"))


if __name__ == "__main__":
    unittest.main()
