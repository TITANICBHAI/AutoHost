"""P7-03: the UI-handler path — run_ui_handler loads a task module and calls @ui.handler by name.

The Kotlin `:ui` worker calls this exactly like `run`: one JSON string in, one outcome JSON out.
These tests pin the contract the UiHandlerController (and later the Tier B/C hosts) rely on:
result passthrough, error classification, and SDK access from inside a handler.
"""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from support import press_device  # noqa: F401

from autohost._runtime import run_ui_handler
from autohost_sim import SimTransport

MAIN_PY = '''
from autohost import ui, log, notify
from autohost.errors import Fatal

CALLS = []

@ui.handler("greet")
def greet(args):
    return {"message": "hello " + args.get("who", "world")}

@ui.handler("no_result")
def no_result(args):
    return None

@ui.handler("uses_sdk")
def uses_sdk(args):
    ui.toast("from handler")
    log.info("handler ran", n=args.get("n"))
    return {"ok": True}

@ui.handler("posts_action")
def posts_action(args):
    notify.post("Update", "with an action", actions=[{"label": "Undo", "handler": "greet"}])
    return {"posted": True}

@ui.handler("broken")
def broken(args):
    raise RuntimeError("boom")

@ui.handler("fatal")
def fatal(args):
    raise Fatal("cannot continue", code="NOT_FOUND")

@ui.handler("returns_list")
def returns_list(args):
    return [1, 2, 3]

@ui.handler("state_patch")
def state_patch(args):
    return {"count": args.get("count", 0) + 1, "state": {"count": args.get("count", 0) + 1}}

@ui.handler("greet")   # duplicate name: the first registration wins (documented)
def greet_second(args):
    return {"message": "second"}

def main():  # pragma: no cover - not called by these tests
    return None
'''

BRIDGE_SENTINEL = object()


class JsonBridge:
    """Mimics the Kotlin PyBridge shape: JSON strings in and out over a SimTransport."""

    def __init__(self, device) -> None:
        self._sim = SimTransport(device)

    def call(self, capability: str, method: str, args_json: str) -> str:
        return json.dumps(self._sim.call(capability, method, json.loads(args_json)))

    def emit(self, event_json: str) -> None:
        self._sim.emit(json.loads(event_json))

    def describe(self) -> str:
        return json.dumps(self._sim.describe())


class UiHandlerPathTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.task_dir = Path(self.tmp.name)
        (self.task_dir / "main.py").write_text(MAIN_PY, encoding="utf-8")
        self.device = press_device()
        self.bridge = JsonBridge(self.device)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def call(self, handler: str, args: dict | None = None) -> dict:
        spec = {"taskId": "com.example.ui", "taskPath": str(self.task_dir), "entry": "main.py",
                "handler": handler, "args": args or {}}
        return json.loads(run_ui_handler(json.dumps(spec), self.bridge))

    def test_result_passthrough(self) -> None:
        outcome = self.call("greet", {"who": "AutoHost"})
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(outcome["result"], {"code": "OK", "data": {"message": "hello AutoHost"}})

    def test_none_result_is_empty_object(self) -> None:
        self.assertEqual(self.call("no_result")["result"]["data"], {})

    def test_handler_not_found(self) -> None:
        outcome = self.call("missing")
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "HANDLER_NOT_FOUND")
        self.assertIn("missing", outcome["message"])

    def test_entry_not_found(self) -> None:
        spec = {"taskId": "t", "taskPath": str(self.task_dir), "entry": "gone.py", "handler": "greet"}
        outcome = json.loads(run_ui_handler(json.dumps(spec), self.bridge))
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "ENTRY_NOT_FOUND")

    def test_handler_exception_is_classified(self) -> None:
        outcome = self.call("broken")
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "EXCEPTION")
        self.assertIn("RuntimeError: boom", outcome["message"])
        self.assertIn("Traceback", outcome["traceback"])

    def test_autohost_error_keeps_reason(self) -> None:
        outcome = self.call("fatal")
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "NOT_FOUND")

    def test_non_dict_result_fails(self) -> None:
        outcome = self.call("returns_list")
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "HANDLER_RESULT")

    def test_handler_can_use_the_sdk(self) -> None:
        outcome = self.call("uses_sdk", {"n": 2})
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(self.device.notifications[-1], {"kind": "toast", "text": "from handler"})
        levels = [e for e in self.device.events if e.get("type") == "log"]
        self.assertTrue(any(e["data"].get("n") == 2 for e in levels))

    def test_handler_notification_actions_validated(self) -> None:
        outcome = self.call("posts_action")
        self.assertEqual(outcome["status"], "SUCCEEDED")
        posted = self.device.notifications[-1]
        self.assertEqual(posted["actions"][0]["handler"], "greet")

    def test_state_key_survives_in_result(self) -> None:
        outcome = self.call("state_patch", {"count": 1})
        self.assertEqual(outcome["result"]["data"]["count"], 2)
        self.assertEqual(outcome["result"]["data"]["state"], {"count": 2})

    def test_duplicate_handler_first_wins(self) -> None:
        self.assertEqual(self.call("greet")["result"]["data"]["message"], "hello world")

    def test_sys_path_cleaned_after_call(self) -> None:
        import sys

        before = list(sys.path)
        self.call("greet")
        self.assertEqual(sys.path, before)


if __name__ == "__main__":
    unittest.main()
