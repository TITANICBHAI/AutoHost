"""P11 part 1 through the simulator: the JavaScript runtime (third runtime, plan §18 P11).

The sim runs real JS task code under node against the SAME prelude the device QuickJS runtime
loads (ADR-076/077): the node shim is a dumb transport, the Python host serves the SAME op table
the Kotlin JsBridge serves, and the outcome classification mirrors execute_with_runtime
branch-for-branch. These tests pin the JS surface, the error classes, the F2 coexistence story
(python + flow + js with independent state) and the prelude parity between the runtime resource
and the sim copy. Without node on PATH the JS runs are skipped (the sandbox/CI ships node).
"""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import support

from autohost import sel
from autohost_sim import FakeDevice, run_task
from autohost_sim.flows import run_flow_task
from autohost_sim.js_host import NODE, PRELUDE, run_js_task

SAMPLES = support.SAMPLES
RUNTIME_PRELUDE = support.ROOT / "runtime" / "js" / "src" / "main" / "resources" / "autohost" / "js" / "prelude.js"

PRESS_SCREENS = {
    "home": {"package": "com.example.app",
             "nodes": [{"id": "fx_title", "text": "Example app", "cls": "android.widget.TextView"},
                       {"id": "fx_btn_continue", "text": "Continue", "cls": "android.widget.Button", "clickable": True}],
             "on_click": {"fx_btn_continue": "done"}},
    "done": {"package": "com.example.app",
             "nodes": [{"id": "fx_done", "text": "Done", "cls": "android.widget.TextView"}]},
}


def press_device(**kw):
    return FakeDevice(PRESS_SCREENS, launch_map={"com.example.app": "home"}, **kw)


def js_task_dir(tmp, *, task_id: str, source: str, runtime: str = "javascript",
                entry: str = "main.js") -> Path:
    base = Path(tmp.name if hasattr(tmp, "name") else tmp)
    d = base / task_id.replace(".", "-")
    d.mkdir(parents=True, exist_ok=True)
    (d / "task.json").write_text(json.dumps({
        "schema": 1, "id": task_id, "name": task_id, "version": "1.0.0",
        "runtime": {"type": runtime, "entry": entry},
    }), encoding="utf-8")
    (d / entry).write_text(source, encoding="utf-8")
    return d


@unittest.skipUnless(NODE, "node is not on PATH")
class JsPressButtonSample(unittest.TestCase):
    """samples/js-press-button — the archetype-A task rewritten against the JS surface."""

    def setUp(self):
        self.device = press_device()

    def run_sample(self, config=None):
        return run_js_task(
            SAMPLES / "js-press-button",
            device=self.device,
            config={"target_package": "com.example.app", "button_label": "Continue",
                    "wait_seconds": 5, "min_interval_minutes": 0, **(config or {})},
        )

    def test_presses_the_button_and_returns_honest_verification(self):
        out = self.run_sample()
        self.assertEqual("SUCCEEDED", out.status)
        self.assertEqual("pressed", out.result.code)
        self.assertEqual("NODE_ACTION", out.result.data["method"])
        self.assertTrue(out.result.data["verified"])
        kinds = [e["type"] for e in out.events]
        self.assertIn("log", kinds)

    def test_cooldown_reads_the_profile_store_and_skips(self):
        first = self.run_sample(config={"min_interval_minutes": 30})
        self.assertEqual("SUCCEEDED", first.status)
        self.assertEqual("pressed", first.result.code)
        second = self.run_sample(config={"min_interval_minutes": 30})
        self.assertEqual("SUCCEEDED", second.status)
        self.assertEqual("skipped_cooldown", second.result.code)

    def test_missing_button_keeps_evidence_and_proposes_a_sooner_retry(self):
        out = self.run_sample(config={"button_label": "Absent", "wait_seconds": 1})
        self.assertEqual("SUCCEEDED", out.status)
        self.assertEqual("nothing_to_do", out.result.code)
        kinds = [e["type"] for e in out.events]
        self.assertIn("evidence", kinds)   # ui_dump evidence
        self.assertIn("next_run", kinds)   # proposeSooner


@unittest.skipUnless(NODE, "node is not on PATH")
class JsOutcomeClassification(unittest.TestCase):
    """The JS mirror of execute_with_runtime: every failure class matches the Python semantics."""

    def run_js(self, source: str, *, cancel_after_s=None):
        self.device = press_device()
        with tempfile.TemporaryDirectory() as tmp:
            d = js_task_dir(tmp, task_id="app.test.js", source=source)
            return run_js_task(d, device=self.device, cancel_after_s=cancel_after_s)

    def test_a_script_bug_is_failed_exception_with_the_stack(self):
        out = self.run_js("export function main() { throw new Error('boom'); }")
        self.assertEqual("FAILED", out.status)
        self.assertEqual("EXCEPTION", out.reason)
        self.assertIn("Error: boom", out.message)
        self.assertIsNotNone(out.traceback)
        self.assertIn("main.js", out.traceback)

    def test_needs_user_becomes_waiting_user_with_the_reason(self):
        out = self.run_js(
            "export function main() { throw new autohost.errors.NeedsUser('SECURITY_CHALLENGE', 'captcha page', 'solve'); }")
        self.assertEqual("WAITING_USER", out.status)
        self.assertEqual("SECURITY_CHALLENGE", out.reason)
        self.assertEqual("captcha page", out.message)

    def test_an_entry_without_main_is_failed_entry_not_found(self):
        out = self.run_js("export const notMain = 1;")
        self.assertEqual("FAILED", out.status)
        self.assertEqual("ENTRY_NOT_FOUND", out.reason)

    def test_broker_errors_keep_their_code_as_the_reason(self):
        out = self.run_js("export function main() { return autohost.ctx.secret('nope'); }")
        self.assertEqual("FAILED", out.status)
        self.assertEqual("NOT_FOUND", out.reason)

    def test_cancellation_unwinds_through_sleep(self):
        out = self.run_js(
            "export function main() { for (;;) { autohost.ctx.sleep(0.5); } }",
            cancel_after_s=1.0,
        )
        self.assertEqual("CANCELLED", out.status)
        self.assertEqual("USER_CANCELLED", out.reason)

    def test_state_round_trips_through_the_checkpoint(self):
        out = self.run_js(
            "export function main() { const s = autohost.ctx.state(); s.stage = 2; autohost.ctx.save(s);"
            " return autohost.ctx.result('saved', {read_back: autohost.ctx.state().stage}); }")
        self.assertEqual("SUCCEEDED", out.status)
        self.assertEqual("saved", out.result.code)
        self.assertEqual(2, out.result.data["read_back"])
        self.assertEqual({"stage": 2}, self.device.state_blob)

    def test_capabilities_describe_flows_through(self):
        out = self.run_js(
            "export function main() { const caps = autohost.capabilities();"
            " return autohost.ctx.result('caps', {count: Object.keys(caps).length}); }")
        self.assertEqual("SUCCEEDED", out.status)
        self.assertGreater(out.result.data["count"], 0)


@unittest.skipUnless(NODE, "node is not on PATH")
class ThreeRuntimesCoexist(unittest.TestCase):
    """Gate 11 F2 (sim half): python + flow + js tasks run through one broker surface with
    independent per-task state."""

    def test_three_tasks_succeed_with_distinct_store_keys(self):
        device = press_device()
        with tempfile.TemporaryDirectory() as tmp:
            py = js_task_dir(tmp, task_id="app.test.py", runtime="python", entry="main.py", source=(
                "from autohost import ctx\n"
                "def main():\n"
                "    ctx.task_store['py_marker'] = 1\n"
                "    return ctx.result('py-done')\n"
            ))
            flow = Path(tmp) / "flow-task"
            flow.mkdir()
            (flow / "task.json").write_text(json.dumps({
                "schema": 1, "id": "app.test.flow", "name": "flow", "version": "1.0.0",
                "runtime": {"type": "flow", "entry": "flow.json"},
            }), encoding="utf-8")
            (flow / "flow.json").write_text(json.dumps(
                {"schema": 1, "steps": [{"setResult": {"code": "flow-done"}}]}), encoding="utf-8")
            js = js_task_dir(tmp, task_id="app.test.js", source=(
                "export function main() {\n"
                "  autohost.ctx.taskStore.set('js_marker', 1);\n"
                "  return autohost.ctx.result('js-done');\n"
                "}\n"))

            py_out = run_task(py, device=device)
            flow_out = run_flow_task(flow, device=device)
            js_out = run_js_task(js, device=device)

        self.assertEqual(("SUCCEEDED", "py-done"), (py_out.status, py_out.result.code))
        self.assertEqual("flow-done", flow_out.result.code)
        self.assertEqual(("SUCCEEDED", "js-done"), (js_out.status, js_out.result.code))
        keys = {k[2] for k in device.kv}
        self.assertIn("py_marker", keys)
        self.assertIn("js_marker", keys)


class PreludeParity(unittest.TestCase):
    """The prelude is ONE source with two copies; the JS sel builders must match the Python SDK."""

    def test_the_sim_prelude_is_byte_identical_to_the_runtime_resource(self):
        self.assertEqual(
            RUNTIME_PRELUDE.read_bytes(),
            PRELUDE.read_bytes(),
            "sdk-python/autohost_sim/js/prelude.js has drifted from runtime/js prelude.js — re-copy",
        )

    @unittest.skipUnless(NODE, "node is not on PATH")
    def test_js_selector_builders_match_the_python_sdk(self):
        js_exprs = [
            "a.sel.text('Continue')",
            "a.sel.text('Ok','Cancel')",
            "a.sel.id('btn_continue')",
            "a.sel.cls('android.widget.Button')",
            "a.sel.pkg('com.example.app')",
            "a.sel.clickable()",
            "a.sel.boundsInside(1,2,3,4)",
            "a.sel.boundsOverlaps(1,2,3,4)",
            "a.sel.depth(3)",
            "a.sel.indexInParent(2)",
            "a.sel.drawingOrder(1)",
            "a.sel.below(a.sel.id('x'))",
            "a.sel.text('A').within(a.sel.id('p'))",
            "a.sel.text('A').nth(2)",
            "a.sel.text('A').bfs()",
            "a.sel.and(a.sel.id('a'), a.sel.clickable())",
            "a.sel.or(a.sel.id('a'), a.sel.id('b'))",
            "a.sel.containsDescendants(a.sel.id('a'), a.sel.id('b'))",
        ]
        py_selectors = [
            sel.text("Continue"),
            sel.text("Ok", "Cancel"),
            sel.id("btn_continue"),
            sel.cls("android.widget.Button"),
            sel.pkg("com.example.app"),
            sel.clickable(),
            sel.bounds_inside(1, 2, 3, 4),
            sel.bounds_overlaps(1, 2, 3, 4),
            sel.depth(3),
            sel.index_in_parent(2),
            sel.drawing_order(1),
            sel.below(sel.id("x")),
            sel.text("A").within(sel.id("p")),
            sel.text("A").nth(2),
            sel.text("A").bfs(),
            sel.id("a") & sel.clickable(),
            sel.id("a") | sel.id("b"),
            sel.contains_descendants(sel.id("a"), sel.id("b")),
        ]
        script = (
            "const fs = require('fs'), vm = require('vm');"
            "globalThis.__autohost_bridge = () => JSON.stringify({ok: true, result: null});"
            f"vm.runInThisContext(fs.readFileSync({json.dumps(str(PRELUDE))}, 'utf8'), {{filename: 'prelude.js'}});"
            "const a = globalThis.autohost;"
            "const out = [" + ",".join(f"(() => {e}.to_json())()" for e in js_exprs) + "];"
            "console.log(JSON.stringify(out));"
        )
        proc = subprocess.run([NODE, "-e", script], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, proc.returncode, proc.stderr)
        js_selectors = json.loads(proc.stdout)
        self.assertEqual(len(py_selectors), len(js_selectors))
        for py_sel, js_sel in zip(py_selectors, js_selectors):
            self.assertEqual(py_sel.to_json(), js_sel)


if __name__ == "__main__":
    unittest.main()
