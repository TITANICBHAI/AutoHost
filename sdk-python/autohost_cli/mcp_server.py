"""autohost mcp (P5-13) — the MCP bridge that lets a coding agent drive the phone.

Implements a stdio MCP server (newline-delimited JSON-RPC 2.0, the 2024-11-05 stdio transport shape:
one JSON message per line, responses one per line). Tools wrap the Dev API exactly like the CLI does,
so an agent can inspect the screen, try selectors, push a task, run it and read the failure bundle in
one loop (plan §5.6 / §13.3).

Run: AUTOHOST_TOKEN=… autohost mcp            (reads LSP-style? no — line-delimited JSON on stdio)
Security: the token never leaves the process; tools expose no shell and no file access beyond the
           export bundle written to the caller-supplied directory.
"""

from __future__ import annotations

import base64
import json
import os
import sys
from typing import Any, Callable

from autohost_cli.client import DevApiClient, DevApiError

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "autohost-mcp", "version": "1.0.0"}


def _text(content: str) -> dict:
    return {"content": [{"type": "text", "text": content}]}


def _json(payload: Any) -> dict:
    return {"content": [{"type": "text", "text": json.dumps(payload, indent=2, default=str)}]}


class McpTools:
    """The ten §5.6 tools. Each returns an MCP tool-result dict; DevApiError becomes `isError`."""

    def __init__(self, client: DevApiClient, export_dir: str | None = None) -> None:
        self.client = client
        self.export_dir = export_dir or os.path.join(os.path.expanduser("~"), ".autohost", "runs")

    def tool_map(self) -> dict[str, Callable[[dict], dict]]:
        return {
            "autohost_get_device_state": lambda a: _json(self.client.call("device.app.foreground", {})),
            "autohost_dump_ui": lambda a: _json(self.client.ui_dump(compact=not a.get("full", False))),
            "autohost_try_selector": lambda a: _json(self.client.ui_try(a.get("selector") or {})),
            "autohost_screenshot": lambda a: _json(self.client.screenshot(a.get("tag"))),
            "autohost_push_task": lambda a: _json(self.client.push(a["dir"])),
            "autohost_run_task": lambda a: _json(self.client.run(a["task"], a.get("profile"))),
            "autohost_get_run": lambda a: _json(self.client.status(a["run_id"])),
            "autohost_tail_logs": lambda a: _json(self.client.logs(a["run_id"], int(a.get("since_seq", 0)))),
            "autohost_export_run": lambda a: self._export(a),
            "autohost_diagnose": lambda _a: _json(self.client.doctor()),
        }

    def _export(self, args: dict) -> dict:
        result = self.client.export_run(args["run_id"])
        out_dir = args.get("dir") or self.export_dir
        path = self.client.save_bundle(result, out_dir)
        return _json({"savedTo": path, "runId": result.get("runId"),
                      "bytes": len(base64.b64decode(result.get("bundle_b64", "")))})

    def schemas(self) -> list[dict]:
        """Tool descriptors (name, description, input schema)."""
        return [
            {"name": "autohost_get_device_state", "description":
                "Foreground app/activity and basic device facts. Call first to see where the phone is.",
             "inputSchema": {"type": "object", "properties": {}}},
            {"name": "autohost_dump_ui", "description":
                "Compact accessibility dump of the current screen (set full=true for every node).",
             "inputSchema": {"type": "object", "properties": {"full": {"type": "boolean"}}}},
            {"name": "autohost_try_selector", "description":
                "Test a selector against the live screen without acting.",
             "inputSchema": {"type": "object", "properties": {"selector": {"type": "object"}},
                             "required": ["selector"]}},
            {"name": "autohost_screenshot", "description": "Capture a screenshot and return its path.",
             "inputSchema": {"type": "object", "properties": {"tag": {"type": "string"}}}},
            {"name": "autohost_push_task", "description":
                "Install a task bundle from a local directory (needs task.json).",
             "inputSchema": {"type": "object", "properties": {"dir": {"type": "string"}},
                             "required": ["dir"]}},
            {"name": "autohost_run_task", "description": "Start a run; returns the run id.",
             "inputSchema": {"type": "object",
                             "properties": {"task": {"type": "string"}, "profile": {"type": "string"}},
                             "required": ["task"]}},
            {"name": "autohost_get_run", "description": "Status of one run (status, stage, reason).",
             "inputSchema": {"type": "object", "properties": {"run_id": {"type": "string"}},
                             "required": ["run_id"]}},
            {"name": "autohost_tail_logs", "description": "Run events after a sequence number.",
             "inputSchema": {"type": "object",
                             "properties": {"run_id": {"type": "string"},
                                            "since_seq": {"type": "integer"}},
                             "required": ["run_id"]}},
            {"name": "autohost_export_run", "description":
                "Download the .aarun failure bundle (redacted logs, stages, incidents, evidence refs).",
             "inputSchema": {"type": "object",
                             "properties": {"run_id": {"type": "string"}, "dir": {"type": "string"}},
                             "required": ["run_id"]}},
            {"name": "autohost_diagnose", "description":
                "Run the health checks (same groups as autohost doctor).",
             "inputSchema": {"type": "object", "properties": {}}},
        ]


class McpServer:
    """Line-delimited JSON-RPC over stdio: initialize, tools/list, tools/call (+ ping)."""

    def __init__(self, tools: McpTools, stdin=None, stdout=None) -> None:
        self.tools = tools
        self._stdin = stdin or sys.stdin
        self._stdout = stdout or sys.stdout
        self.initialized = False

    def serve(self) -> int:
        for line in self._stdin:
            line = line.strip()
            if not line:
                continue
            try:
                request = json.loads(line)
            except json.JSONDecodeError:
                self._write({"jsonrpc": "2.0", "id": None,
                             "error": {"code": -32700, "message": "parse error"}})
                continue
            response = self.handle(request)
            if response is not None:
                self._write(response)
        return 0

    def handle(self, request: dict) -> dict | None:
        method = request.get("method", "")
        request_id = request.get("id")
        if method == "ping":
            return self._ok(request_id, {})
        if method == "initialize":
            self.initialized = True
            return self._ok(request_id, {
                "protocolVersion": PROTOCOL_VERSION,
                "serverInfo": SERVER_INFO,
                "capabilities": {"tools": {}},
            })
        if method == "notifications/initialized":
            return None  # notification: no response
        if method == "tools/list":
            return self._ok(request_id, {"tools": self.tools.schemas()})
        if method == "tools/call":
            return self._ok(request_id, self._call_tool(request.get("params") or {}))
        return self._err(request_id, -32601, f"method not found: {method}")

    def _call_tool(self, params: dict) -> dict:
        name = params.get("name", "")
        args = params.get("arguments") or {}
        known = self.tools.tool_map()
        if name not in known:
            return {"isError": True, "content": [{"type": "text", "text": f"unknown tool: {name}"}]}
        try:
            return known[name](args)
        except DevApiError as exc:
            return {"isError": True,
                    "content": [{"type": "text", "text": f"{exc.code}: {exc}"}]}
        except KeyError as exc:
            return {"isError": True,
                    "content": [{"type": "text", "text": f"missing argument: {exc}"}]}

    def _ok(self, request_id: Any, result: Any) -> dict:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _err(self, request_id: Any, code: int, message: str) -> dict:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    def _write(self, payload: dict) -> None:
        self._stdout.write(json.dumps(payload) + "\n")
        self._stdout.flush()


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="autohost mcp", description="MCP stdio server for AutoHost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--token", default=os.environ.get("AUTOHOST_TOKEN"))
    parser.add_argument("--export-dir", default=None, help="where .aarun bundles are written")
    args = parser.parse_args(argv)
    client = DevApiClient(port=args.port, token=args.token)
    server = McpServer(McpTools(client, args.export_dir))
    return server.serve()


if __name__ == "__main__":
    sys.exit(main())
