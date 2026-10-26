#!/usr/bin/env python3
"""mcp_eval (P5-14): run MCP eval scenarios against the autohost MCP server (plan §18 P5).

Each scenario is a YAML file in testing/mcp-evals/:

    name: inspect-author-run
    description: the Gate-5 authoring loop, tool by tool
    steps:
      - tool: autohost_get_device_state
        expect_contains: "com.fixture"
      - tool: autohost_dump_ui
        expect_contains: "button"
      - tool: autohost_push_task
        args: {dir: samples/hello}
        expect_json_path: taskId
      - tool: autohost_run_task
        args: {task: app.autohost.sample.hello}
        expect_json_path: runId
      - tool: autohost_diagnose
        expect_not_contains: NEEDS_FIX

The runner feeds each step through `McpServer.handle` exactly as an MCP client would
(initialize → tools/call), backed by a scripted `DevApiClient` double — no phone needed.
Exit code 0 when every scenario passes; 1 with a failing-step report (CI-gateable).

Usage: python3 tools/mcp_eval.py [scenario.yaml …]   (default: every YAML in testing/mcp-evals/)
"""

from __future__ import annotations

import io
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "sdk-python"))

import yaml  # noqa: E402  (dev-only dependency; the shipped SDK stays stdlib-only)

from autohost_cli import mcp_server  # noqa: E402

EVAL_DIR = ROOT / "testing" / "mcp-evals"


class FakeDevClient:
    """Scripted DevApiClient double: canned tool responses, records every call."""

    def __init__(self, script: dict[str, Any] | None = None) -> None:
        self.script = dict(script or {})
        self.calls: list[tuple[str, dict]] = []

    def _respond(self, key: str, default: Any) -> Any:
        self.calls.append((key, {}))
        return self.script.get(key, default)

    def call(self, method: str, params: dict | None = None) -> dict:
        self.calls.append((method, dict(params or {})))
        return self.script.get(method, {})

    def ui_dump(self, compact: bool = True) -> dict:
        self.calls.append(("device.ui.snapshot", {"compact": compact}))
        return self.script.get("dump", {"compact": "App: fixture (com.fixture)\n0. Button: \"btn\" \"Start\" [clickable] (0,0,100,40)"})

    def ui_try(self, selector: dict) -> dict:
        self.calls.append(("device.ui.find", {"selector": selector}))
        return self.script.get("try", {"nodes": [{"id": "btn", "text": "Start"}]})

    def screenshot(self, tag: str | None = None) -> dict:
        self.calls.append(("device.screenshot", {"tag": tag}))
        return self.script.get("screenshot", {"path": "/sim/shot.png"})

    def push(self, directory: str) -> dict:
        self.calls.append(("tasks.install", {"dir": directory}))
        return self.script.get("push", {"taskId": "app.autohost.sample.hello", "version": "1"})

    def run(self, task: str, profile: str | None = None) -> dict:
        self.calls.append(("runs.start", {"task": task}))
        return self.script.get("run", {"runId": "run-123"})

    def status(self, run_id: str) -> dict:
        self.calls.append(("runs.status", {"id": run_id}))
        return self.script.get("status", {"id": run_id, "status": "FAILED", "reasonCode": "EXCEPTION"})

    def logs(self, run_id: str, since_seq: int = 0) -> dict:
        self.calls.append(("runs.tail", {"id": run_id, "since_seq": since_seq}))
        return self.script.get("logs", {"events": [{"seq": 1, "level": "error", "message": "boom"}]})

    def export_run(self, run_id: str) -> dict:
        self.calls.append(("runs.export", {"id": run_id}))
        return self.script.get("export", {"runId": run_id, "bundle_b64": "eyJvayI6IHRydWV9"})

    def save_bundle(self, export_result: dict, out_dir: str) -> str:
        return f"{out_dir}/{export_result.get('runId', 'run')}.aarun"

    def doctor(self) -> dict:
        self.calls.append(("health.run", {}))
        return self.script.get("doctor", {"needs_fix": [], "autofixable": [], "optional": []})


def _text_of(tool_result: dict) -> str:
    parts = tool_result.get("content") or []
    return "\n".join(part.get("text", "") for part in parts if isinstance(part, dict))


def run_scenario(path: Path, *, client: FakeDevClient | None = None) -> tuple[bool, list[str]]:
    client = client or FakeDevClient()
    with path.open(encoding="utf-8") as fh:
        scenario = yaml.safe_load(fh)
    tools = mcp_server.McpTools(client, export_dir="/tmp/mcp-eval")
    server = mcp_server.McpServer(tools, stdin=io.StringIO(), stdout=io.StringIO())
    server.handle({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                   "params": {"protocolVersion": mcp_server.PROTOCOL_VERSION}})
    problems: list[str] = []
    for index, step in enumerate(scenario.get("steps", []), start=1):
        tool = step.get("tool", "")
        response = server.handle({"jsonrpc": "2.0", "id": index, "method": "tools/call",
                                  "params": {"name": tool, "arguments": step.get("args") or {}}})
        result = response.get("result") or {}
        text = _text_of(result)
        if result.get("isError"):
            problems.append(f"step {index} ({tool}): tool error → {text[:200]}")
            continue
        if "expect_contains" in step and step["expect_contains"] not in text:
            problems.append(f"step {index} ({tool}): expected {step['expect_contains']!r} in output")
        if "expect_not_contains" in step and step["expect_not_contains"] in text:
            problems.append(f"step {index} ({tool}): did not expect {step['expect_not_contains']!r}")
        if "expect_json_path" in step:
            try:
                parsed = json.loads(text)
                cursor: Any = parsed
                for part in str(step["expect_json_path"]).split("."):
                    cursor = cursor[part]
            except Exception as exc:  # path missing / output not JSON
                problems.append(f"step {index} ({tool}): json path failed ({exc})")
    return (not problems), problems


def main(argv: list[str] | None = None) -> int:
    paths = [Path(a) for a in (argv or [])] or sorted(EVAL_DIR.glob("*.yaml"))
    if not paths:
        print("no scenarios found", file=sys.stderr)
        return 1
    failures = 0
    for path in paths:
        ok, problems = run_scenario(path)
        print(f"{'PASS' if ok else 'FAIL'}  {path.name}")
        for problem in problems:
            print(f"   - {problem}")
        failures += 0 if ok else 1
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
