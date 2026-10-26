"""Simulator support for flow tasks (P6-11): autohost_sim.flows.run_flow_task.

Runs a flow task's document through the reference interpreter against a FakeDevice — the same
shape `runner.run_task` returns for Python tasks, so test suites can drive both runtimes alike.
The YAML case exercises the sample's block-list syntax through PyYAML (simulator-side only;
on-device YAML stays plug-gated per ADR-052).
"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from autohost_ref.flows import FlowError  # noqa: E402
from autohost_sim import FakeDevice  # noqa: E402
from autohost_sim.flows import load_flow_document, run_flow_task  # noqa: E402

MANIFEST = {
    "schema": 1,
    "id": "sim.flow",
    "name": "Sim flow",
    "version": "1.0.0",
    "host_api": ">=1 <2",
    "runtime": {"type": "flow", "entry": "flow.json"},
    "needs": {"capabilities": [], "optional": [], "apps": []},
    "triggers": {"manual": True},
    "execution": {"timeout_minutes": 5},
}

SCREENS = {
    "home": {
        "package": "com.example.app",
        "nodes": [{"id": "btn", "text": "Continue", "clickable": True}],
        "on_click": {"btn": "done"},
    },
    "done": {
        "package": "com.example.app",
        "nodes": [{"text": "All set"}],
    },
}


class SimFlowsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.device = FakeDevice(SCREENS, launch_map={"com.example.app": "home"}, start="home")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def task_dir(self, *, manifest=None, flow=None, extra=None) -> Path:
        d = self.tmp / "task"
        d.mkdir(exist_ok=True)
        (d / "task.json").write_text(json.dumps(manifest or MANIFEST), encoding="utf-8")
        if flow is not None:
            (d / "flow.json").write_text(json.dumps({"schema": 1, **flow}), encoding="utf-8")
        for name, content in (extra or {}).items():
            (d / name).write_text(content, encoding="utf-8")
        return d

    def test_json_flow_happy_path(self):
        d = self.task_dir(flow={"steps": [
            {"launchApp": {"package": "com.example.app"}},
            {"tapOn": {"text": ["Continue"]}},
            {"setResult": {"code": "pressed"}},
        ]})
        outcome = run_flow_task(d, device=self.device, config={"target_package": "com.example.app"})
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertEqual("pressed", outcome.result.code)
        self.assertEqual("done", self.device.current)
        # the dispatch log went through the sim device: launch, tap family, etc.
        self.assertTrue(any(cap == "device.app" and method == "launch" for cap, method, _ in self.device.calls))
        self.assertIsNone(outcome.reason)

    def test_yaml_entry_runs_through_the_same_interpreter(self):
        d = self.task_dir(
            manifest={**MANIFEST, "runtime": {"type": "flow", "entry": "flow.yaml"}},
            extra={"flow.yaml": "schema: 1\nsteps:\n  - setResult:\n      code: yaml-ok\n"},
        )
        outcome = run_flow_task(d, device=self.device)
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertEqual("yaml-ok", outcome.result.code)

    def test_default_entry_resolution_prefers_flow_json(self):
        d = self.task_dir(flow={"steps": [{"setResult": {"code": "json"}}]})
        (d / "flow.yaml").write_text("steps:\n  - setResult:\n      code: yaml\n", encoding="utf-8")
        parsed, name = load_flow_document(d, None)
        self.assertEqual("flow.json", name)

    def test_missing_document_fails_with_a_typed_message(self):
        d = self.task_dir()
        with self.assertRaises(FlowError) as ctx:
            run_flow_task(d, device=self.device)
        self.assertIn("does not exist beside task.json", str(ctx.exception))

    def test_missing_document_without_entry_names_the_defaults(self):
        d = self.task_dir(manifest={**MANIFEST, "runtime": {"type": "flow"}})
        with self.assertRaises(FlowError) as ctx:
            run_flow_task(d, device=self.device)
        self.assertIn("no flow document found", str(ctx.exception))

    def test_parse_error_surfaces_as_FlowError(self):
        d = self.task_dir(flow={"steps": [{"noSuchCommand": {}}]})
        with self.assertRaises(FlowError):
            run_flow_task(d, device=self.device)

    def test_subflow_resolves_by_stem_and_runs(self):
        d = self.task_dir(
            flow={"steps": [{"runFlow": {"file": "child"}}]},
            extra={"child.json": json.dumps({"schema": 1, "steps": [{"setResult": {"code": "from-child"}}]})},
        )
        outcome = run_flow_task(d, device=self.device)
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertEqual("from-child", outcome.result.code)

    def test_failure_class_matches_the_python_runner_shape(self):
        d = self.task_dir(flow={"steps": [
            {"assertVisible": {"text": ["Nothing like this"]}},
        ]})
        outcome = run_flow_task(d, device=self.device)
        self.assertEqual("FAILED", outcome.status)
        self.assertEqual("NOT_FOUND", outcome.reason)
        self.assertTrue(any(e.get("type") == "evidence" for e in outcome.events))

    def test_cancel_after_s_falls_out_as_CANCELLED(self):
        d = self.task_dir(flow={"steps": [
            {"repeat": {"times": 50, "steps": [{"scroll": {"direction": "down"}}]}},
        ]})
        # every simulated scroll advances the virtual clock by 0.1s -> cancellation lands mid-loop
        outcome = run_flow_task(d, device=self.device, cancel_after_s=0.5)
        self.assertEqual("CANCELLED", outcome.status)
        self.assertEqual("USER_CANCELLED", outcome.reason)

    def test_events_are_stamped_by_order_not_by_ts(self):
        d = self.task_dir(flow={"steps": [
            {"log": {"message": "one"}},
            {"log": {"message": "two"}},
        ]})
        outcome = run_flow_task(d, device=self.device)
        kinds = [e.get("type") for e in outcome.events]
        self.assertEqual(["log", "log"], kinds)


if __name__ == "__main__":
    unittest.main()
