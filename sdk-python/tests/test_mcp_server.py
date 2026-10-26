"""P5-13: the MCP stdio server — protocol handling and tool wiring."""
from __future__ import annotations

import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from autohost_cli import mcp_server
from autohost_cli.client import DevApiError

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "sdk-python"))


class RecordingClient:
    """Minimal DevApiClient double: every method records and returns a canned value."""

    def __init__(self) -> None:
        self.calls: list[tuple] = []
        self.export_saved: list[str] = []

    def call(self, method: str, params: dict | None = None) -> dict:
        self.calls.append((method, dict(params or {})))
        if method == "device.app.foreground":
            return {"package": "com.fixture", "activity": "HomeActivity"}
        if method == "device.screenshot":
            return {"path": "/sim/shot.png"}
        if method == "health.run":
            return {"needs_fix": [], "optional": []}
        if method == "runs.export":
            return {"runId": params["id"], "bundle_b64": "eyJhIjoxfQ=="}
        return {}

    def ui_dump(self, compact: bool = True) -> dict:
        self.calls.append(("device.ui.snapshot", {"compact": compact}))
        return {"compact": "App: fixture (com.fixture)"}

    def ui_try(self, selector: dict) -> dict:
        self.calls.append(("device.ui.find", {"selector": selector}))
        return {"nodes": [{"id": "btn"}]}

    def push(self, directory: str) -> dict:
        self.calls.append(("tasks.install", {"dir": directory}))
        return {"taskId": "app.autohost.sample.hello"}

    def run(self, task: str, profile: str | None = None) -> dict:
        self.calls.append(("runs.start", {"task": task}))
        return {"runId": "run-9"}

    def status(self, run_id: str) -> dict:
        self.calls.append(("runs.status", {"id": run_id}))
        return {"id": run_id, "status": "SUCCEEDED"}

    def logs(self, run_id: str, since_seq: int = 0) -> dict:
        self.calls.append(("runs.tail", {"id": run_id}))
        return {"events": [{"seq": 1, "message": "hi"}]}

    def export_run(self, run_id: str) -> dict:
        self.calls.append(("runs.export", {"id": run_id}))
        return {"runId": run_id, "bundle_b64": "eyJhIjoxfQ=="}

    def save_bundle(self, export_result: dict, out_dir: str) -> str:
        path = os.path.join(out_dir, f"{export_result['runId']}.aarun")
        self.export_saved.append(path)
        return path

    def doctor(self) -> dict:
        self.calls.append(("health.run", {}))
        return {"needs_fix": []}


def make_server(client) -> tuple[mcp_server.McpServer, io.StringIO]:
    out = io.StringIO()
    server = mcp_server.McpServer(mcp_server.McpTools(client, export_dir="/tmp/x"),
                                  stdin=io.StringIO(), stdout=out)
    return server, out


def call_tool(server: mcp_server.McpServer, name: str, args: dict | None = None) -> dict:
    response = server.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                              "params": {"name": name, "arguments": args or {}}})
    return response["result"]


def text_of(result: dict) -> str:
    return "\n".join(p.get("text", "") for p in result["content"])


class McpProtocolTest(unittest.TestCase):
    def setUp(self):
        self.client = RecordingClient()
        self.server, _ = make_server(self.client)

    def test_initialize_negotiates_protocol(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                       "params": {"protocolVersion": mcp_server.PROTOCOL_VERSION}})
        self.assertEqual(response["result"]["protocolVersion"], mcp_server.PROTOCOL_VERSION)
        self.assertEqual(response["result"]["serverInfo"]["name"], "autohost-mcp")

    def test_tools_list_exposes_the_ten_tools(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in response["result"]["tools"]}
        self.assertEqual(len(names), 10)
        self.assertIn("autohost_export_run", names)
        self.assertIn("autohost_try_selector", names)

    def test_unknown_method_is_rpc_error(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 3, "method": "nope"})
        self.assertEqual(response["error"]["code"], -32601)

    def test_notification_gets_no_response(self):
        self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))


class McpToolsTest(unittest.TestCase):
    def setUp(self):
        self.client = RecordingClient()
        self.server, _ = make_server(self.client)

    def test_dump_ui_returns_compact_dump(self):
        result = call_tool(self.server, "autohost_dump_ui")
        self.assertFalse(result.get("isError", False))
        self.assertIn("com.fixture", text_of(result))

    def test_run_task_returns_run_id(self):
        result = call_tool(self.server, "autohost_run_task", {"task": "hello"})
        self.assertIn("run-9", text_of(result))
        self.assertIn(("runs.start", {"task": "hello"}), self.client.calls)

    def test_export_run_writes_bundle_path(self):
        result = call_tool(self.server, "autohost_export_run", {"run_id": "run-9", "dir": "/tmp/x"})
        self.assertIn(".aarun", text_of(result))
        self.assertEqual(self.client.export_saved, ["/tmp/x/run-9.aarun"])

    def test_dev_api_error_becomes_tool_error(self):
        def boom(_a=None, **_kw):
            raise DevApiError("CONN_FAILED", "cannot reach device")

        self.server.tools.client.ui_dump = boom
        result = call_tool(self.server, "autohost_dump_ui")
        self.assertTrue(result.get("isError"))
        self.assertIn("CONN_FAILED", text_of(result))

    def test_missing_required_argument_is_tool_error(self):
        result = call_tool(self.server, "autohost_run_task", {})
        self.assertTrue(result.get("isError"))
        self.assertIn("missing argument", text_of(result))

    def test_unknown_tool_is_tool_error(self):
        result = call_tool(self.server, "autohost_not_a_tool")
        self.assertTrue(result.get("isError"))


class McpServeLoopTest(unittest.TestCase):
    def test_serve_reads_lines_and_writes_responses(self):
        client = RecordingClient()
        out = io.StringIO()
        incoming = "\n".join([
            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}),
            json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}),
            json.dumps({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                        "params": {"name": "autohost_run_task", "arguments": {"task": "hello"}}}),
            "not json at all",
            "",
        ])
        server = mcp_server.McpServer(mcp_server.McpTools(client), stdin=io.StringIO(incoming), stdout=out)
        server.serve()
        lines = [json.loads(line) for line in out.getvalue().splitlines()]
        # parse error + 3 valid responses
        self.assertEqual(len(lines), 4)
        self.assertEqual(lines[0]["result"]["serverInfo"]["name"], "autohost-mcp")
        self.assertEqual(len(lines[1]["result"]["tools"]), 10)
        self.assertIn("run-9", json.dumps(lines[2]))


if __name__ == "__main__":
    unittest.main()
