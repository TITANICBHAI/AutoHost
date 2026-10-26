"""Worker entry point (`autohost._runtime`): RunSpec parsing, BridgeTransport, outcome classification.

These tests use a fake Kotlin bridge (same JSON-string interface as PyBridge) so the worker path is
exercised end to end without a device.
"""
import json
import unittest
from pathlib import Path

import support  # noqa: F401  (puts the SDK and repo root on sys.path)

from autohost._core import uninstall
from autohost._runtime import BridgeTransport, execute_with_runtime, request_cancel, request_pause, request_resume, run
from autohost._core import Runtime


def task_dir(name: str) -> Path:
    """Build a minimal task bundle in a temp dir."""
    import tempfile

    root = Path(tempfile.mkdtemp(prefix=f"ah-{name}-"))
    (root / "task.json").write_text(json.dumps({
        "schema": 1, "id": f"test.{name}", "name": name, "runtime": {"type": "python", "entry": "main.py"},
    }), encoding="utf-8")
    return root


class FakeBridge:
    """Same JSON-string surface the Kotlin PyBridge exposes to Python."""

    def __init__(self, fail_on: str | None = None):
        self.calls: list[tuple[str, str, dict]] = []
        self.events: list[dict] = []
        self.fail_on = fail_on
        self.described = {"capabilities": {"device.ui": {"available": True}}}

    def call(self, capability: str, method: str, args_json: str) -> str:
        args = json.loads(args_json)
        self.calls.append((capability, method, args))
        if self.fail_on == method:
            return json.dumps({"ok": False, "error": {"code": "CAPABILITY_UNAVAILABLE",
                                                       "message": "not here", "reason": "NO_PROVIDER"}})
        return json.dumps({"ok": True, "result": {"echo": args}})

    def emit(self, event_json: str) -> None:
        self.events.append(json.loads(event_json))

    def describe(self) -> str:
        return json.dumps(self.described)


class BridgeTransportTest(unittest.TestCase):
    def test_call_and_describe_round_trip_json(self):
        bridge = FakeBridge()
        transport = BridgeTransport(bridge)
        # BridgeTransport returns the raw envelope; Runtime.call unwraps it (see test in test_core).
        result = transport.call("device.app", "query", {"package": "com.a"})
        self.assertEqual({"ok": True, "result": {"echo": {"package": "com.a"}}}, result)
        self.assertEqual({"capabilities": {"device.ui": {"available": True}}}, transport.describe())
        self.assertEqual([("device.app", "query", {"package": "com.a"})], bridge.calls)


class RunLifecycleTest(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def test_success_outcome_shape_and_finished_event(self):
        root = task_dir("ok")
        (root / "main.py").write_text(
            "from autohost._ctx import Result\n"
            "def main():\n"
            "    return Result('DONE', {'n': 1})\n",
            encoding="utf-8",
        )
        bridge = FakeBridge()
        spec = {"runId": "r-1", "taskId": "test.ok", "taskPath": str(root), "entry": "main.py",
                "trigger": {"type": "manual", "payload": {}}}
        outcome = json.loads(run(json.dumps(spec), bridge))
        self.assertEqual("SUCCEEDED", outcome["status"])
        self.assertEqual("DONE", outcome["result"]["code"])
        self.assertEqual({"n": 1}, outcome["result"]["data"])
        finished = [e for e in bridge.events if e["type"] == "finished"]
        self.assertEqual(1, len(finished))
        self.assertEqual(outcome, finished[0]["data"])

    def test_script_exception_becomes_failed_with_traceback(self):
        root = task_dir("boom")
        (root / "main.py").write_text(
            "def main():\n    raise ValueError('boom')\n",
            encoding="utf-8",
        )
        bridge = FakeBridge()
        outcome = json.loads(run(json.dumps({"taskPath": str(root), "entry": "main.py"}), bridge))
        self.assertEqual("FAILED", outcome["status"])
        self.assertEqual("EXCEPTION", outcome["reason"])
        self.assertIn("ValueError: boom", outcome["message"])
        self.assertIn("Traceback", outcome["traceback"])
        finished = [e for e in bridge.events if e["type"] == "finished"]
        self.assertEqual(1, len(finished))

    def test_sdk_failure_is_classified_by_reason(self):
        root = task_dir("sdkfail")
        (root / "main.py").write_text(
            "from autohost import device\n"
            "def main():\n"
            "    device.app.launch('com.missing')\n",
            encoding="utf-8",
        )
        bridge = FakeBridge(fail_on="launch")
        outcome = json.loads(run(json.dumps({"taskPath": str(root), "entry": "main.py"}), bridge))
        self.assertEqual("FAILED", outcome["status"])
        self.assertEqual("NO_PROVIDER", outcome["reason"])
        self.assertNotIn("traceback", outcome)

    def test_missing_entry_is_failed_not_crash(self):
        root = task_dir("noentry")
        outcome = json.loads(run(json.dumps({"taskPath": str(root), "entry": "nope.py"}), FakeBridge()))
        self.assertEqual("FAILED", outcome["status"])
        self.assertEqual("ENTRY_NOT_FOUND", outcome["reason"])

    def test_cancel_via_request_cancel_yields_cancelled(self):
        root = task_dir("cancel")
        (root / "main.py").write_text(
            "from autohost._runtime import request_cancel\n"
            "def main():\n"
            "    request_cancel()\n"
            "    return\n",
            encoding="utf-8",
        )
        outcome = json.loads(run(json.dumps({"taskPath": str(root), "entry": "main.py"}), FakeBridge()))
        self.assertEqual("CANCELLED", outcome["status"])
        self.assertEqual("USER_CANCELLED", outcome["reason"])

    def test_pause_blocks_until_resume(self):
        import threading
        import time as _time

        root = task_dir("pause")
        (root / "main.py").write_text(
            "from autohost._core import current\n"
            "def main():\n"
            "    rt = current()\n"
            "    rt.pause()\n"
            "    rt.check_cancel()\n"  # blocks while paused; cancellation would win
            "    rt.resume()\n"
            "    return 'resumed'\n",
            encoding="utf-8",
        )
        outcomes: dict = {}
        bridge = FakeBridge()

        def target():
            outcomes["result"] = json.loads(run(json.dumps({"taskPath": str(root), "entry": "main.py"}), bridge))

        thread = threading.Thread(target=target)
        thread.start()
        _time.sleep(0.4)  # the task is now parked inside check_cancel while paused
        self.assertTrue(thread.is_alive(), "paused run must block until resumed")
        request_resume()  # module-global current runtime is shared across threads
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive(), "resumed run must finish")
        self.assertEqual("SUCCEEDED", outcomes["result"]["status"])

    def test_request_pause_wins_over_sleep_until_resumed(self):
        import threading
        import time as _time

        root = task_dir("pause-sleep")
        (root / "main.py").write_text(
            "from autohost import ctx\n"
            "def main():\n"
            "    ctx.sleep(5)\n"  # runtime.sleep re-enters check_cancel -> blocks while paused
            "    return 'awake'\n",
            encoding="utf-8",
        )
        outcomes: dict = {}

        def target():
            bridge = FakeBridge()
            spec = {"taskPath": str(root), "entry": "main.py"}
            outcomes["result"] = json.loads(run(json.dumps(spec), bridge))

        thread = threading.Thread(target=target)
        thread.start()
        _time.sleep(0.3)
        request_pause()  # pause mid-sleep
        _time.sleep(0.3)
        self.assertTrue(thread.is_alive(), "sleep must not finish while paused")
        request_resume()
        thread.join(timeout=10)
        self.assertEqual("SUCCEEDED", outcomes["result"]["status"])

    def test_execute_with_runtime_handles_cancelled_exception(self):
        root = task_dir("raise-cancel")
        (root / "main.py").write_text(
            "from autohost.errors import Cancelled\n"
            "def main():\n"
            "    raise Cancelled('stop it')\n",
            encoding="utf-8",
        )
        outcome = execute_with_runtime(Runtime(FakeBridge()), str(root), "main.py")
        self.assertEqual("CANCELLED", outcome["status"])
        self.assertEqual("USER_CANCELLED", outcome["reason"])


class ControlSurfaceTest(unittest.TestCase):
    def test_request_pause_resume_cancel_without_run_are_noops(self):
        uninstall()
        request_pause()
        request_resume()
        request_cancel()  # must not raise

    def test_finished_event_delivered_even_after_emit_failure(self):
        root = task_dir("emitfail")

        class BrokenEmitBridge(FakeBridge):
            def emit(self, event_json: str) -> None:
                raise RuntimeError("binder died")

        (root / "main.py").write_text("def main():\n    return\n", encoding="utf-8")
        outcome = json.loads(run(json.dumps({"taskPath": str(root), "entry": "main.py"}), BrokenEmitBridge()))
        self.assertEqual("FAILED", outcome["status"])
        self.assertEqual("FINISHED_EVENT_FAILED", outcome["reason"])


if __name__ == "__main__":
    unittest.main()
