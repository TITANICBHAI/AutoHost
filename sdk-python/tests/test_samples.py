"""The shipped sample tasks, run unchanged against the fake device. This is the SDK's acceptance test on a PC."""
import json
import pathlib
import shutil
import tempfile
import unittest

import support
from autohost import errors
from autohost._core import uninstall
from autohost_sim import FakeDevice, VirtualClock, run_task
from autohost_sim.flows import run_flow_task

PRESS_CONFIG = {"target_package": "com.example.app", "button_label": "Continue", "label_variants": [],
                "wait_seconds": 5, "min_interval_minutes": 0}


def evidence(outcome, kind):
    return [e for e in outcome.events if e.get("type") == "evidence" and e["data"].get("kind") == kind]


class PressButtonFlowSample(unittest.TestCase):
    """P6-12: the flow twin of press-button — YAML/JSON drift gate + simulator run of the twin."""

    SAMPLE = support.SAMPLES / "press-button-flow"

    def test_flow_twin_matches_yaml(self):
        import yaml
        from_yaml = yaml.safe_load((self.SAMPLE / "flow.yaml").read_text(encoding="utf-8"))
        from_json = json.loads((self.SAMPLE / "flow.json").read_text(encoding="utf-8"))
        self.assertEqual(from_yaml, from_json)

    def test_press_button_flow_twin_runs_in_the_simulator(self):
        # ADR-052: the sample's entry stays flow.yaml (the authoring form); the simulator runs the
        # JSON twin from a temp dir with the entry flipped — exactly the workaround the sample
        # README prescribes for on-device runs until the YAML plug lands.
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            manifest = json.loads((self.SAMPLE / "task.json").read_text(encoding="utf-8"))
            manifest["runtime"]["entry"] = "flow.json"
            (p / "task.json").write_text(json.dumps(manifest), encoding="utf-8")
            shutil.copy(self.SAMPLE / "flow.json", p / "flow.json")
            out = run_flow_task(p, device=support.press_device(), config=PRESS_CONFIG)
        self.assertEqual("SUCCEEDED", out.status)
        self.assertEqual("pressed", out.result.code)

    def test_flow_document_of_the_sample_passes_the_schema(self):
        # Belt and braces next to tests/test_schemas.py: the twin (not only the YAML) validates.
        flow = json.loads((self.SAMPLE / "flow.json").read_text(encoding="utf-8"))
        import sys
        sys.path.insert(0, str(support.ROOT / "tools"))
        from jsonschema_mini import validate
        schema = json.loads((support.ROOT / "schemas" / "flow.schema.json").read_text(encoding="utf-8"))
        self.assertEqual([], validate(flow, schema))


class PressButtonSample(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def test_presses_the_button_and_records_the_time(self):
        dev = support.press_device()
        out = run_task(support.SAMPLES / "press-button", device=dev, config=PRESS_CONFIG)
        self.assertEqual(out.status, "SUCCEEDED", out.message)
        self.assertEqual(out.result.code, "pressed")
        self.assertEqual(dev.current, "done")
        self.assertIn(("profile", "p1", "last_pressed_at"), dev.kv)

    def test_cooldown_skips_a_second_press(self):
        dev = support.press_device()
        run_task(support.SAMPLES / "press-button", device=dev, config={**PRESS_CONFIG, "min_interval_minutes": 60})
        out = run_task(support.SAMPLES / "press-button", device=dev, config={**PRESS_CONFIG, "min_interval_minutes": 60})
        self.assertEqual(out.result.code, "skipped_cooldown")

    def test_cooldown_expires_with_time(self):
        dev = support.press_device()
        run_task(support.SAMPLES / "press-button", device=dev, config={**PRESS_CONFIG, "min_interval_minutes": 60})
        dev.clock.sleep(61 * 60)
        out = run_task(support.SAMPLES / "press-button", device=dev, config={**PRESS_CONFIG, "min_interval_minutes": 60})
        self.assertEqual(out.result.code, "pressed")

    def test_missing_button_reports_nothing_to_do_with_a_ui_dump(self):
        dev = support.press_device()
        out = run_task(support.SAMPLES / "press-button", device=dev, config={**PRESS_CONFIG, "button_label": "Nope"})
        self.assertEqual(out.result.code, "nothing_to_do")
        self.assertTrue(evidence(out, "ui_dump"))


class HelloSample(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def test_runs_and_counts_runs_across_invocations(self):
        dev = FakeDevice({"x": {"package": "p", "nodes": []}}, launch_map={}, clock=VirtualClock())
        first = run_task(support.SAMPLES / "hello", device=dev, config={"steps": 3})
        second = run_task(support.SAMPLES / "hello", device=dev, config={"steps": 3})
        self.assertEqual((first.result.data["runs"], second.result.data["runs"]), (1, 2))
        self.assertEqual(dev.clock.slept, 6.0)

    def test_cancellation_mid_run_is_reported_as_cancelled(self):
        dev = FakeDevice({"x": {"package": "p", "nodes": []}}, launch_map={}, clock=VirtualClock())
        out = run_task(support.SAMPLES / "hello", device=dev, config={"steps": 30}, cancel_after_s=2)
        self.assertEqual(out.status, "CANCELLED")


class SettingsReaderSample(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def settings_device(self):
        screens = {
            "settings_root": {"package": "com.android.settings",
                              "nodes": [{"id": "s_network", "text": "Network", "cls": "android.widget.TextView"}],
                              "hidden_nodes": [{"id": "s_about", "text": "About phone", "cls": "android.widget.TextView",
                                                "clickable": True}],
                              "reveal_after": 2, "on_click": {"About phone": "about"}},
            "about": {"package": "com.android.settings",
                      "nodes": [{"id": "a_model", "text": "Model Sim", "cls": "android.widget.TextView"},
                                {"id": "a_android", "text": "Android version 10", "cls": "android.widget.TextView"}]},
        }
        return FakeDevice(screens, launch_map={"com.android.settings": "settings_root"}, clock=VirtualClock())

    def test_scrolls_to_about_reads_it_and_goes_back(self):
        dev = self.settings_device()
        out = run_task(support.SAMPLES / "settings-reader", device=dev)
        self.assertEqual(out.status, "SUCCEEDED", out.message)
        self.assertEqual(out.result.code, "ok")
        self.assertGreater(out.result.data["lines"], 0)
        self.assertEqual(dev.current, "settings_root")
        self.assertTrue(evidence(out, "screenshot"))
        self.assertTrue(evidence(out, "note"))

    def test_without_screenshot_capability_the_script_still_succeeds(self):
        dev = self.settings_device()
        dev.screenshot_enabled = False
        out = run_task(support.SAMPLES / "settings-reader", device=dev)
        self.assertEqual(out.status, "SUCCEEDED", out.message)
        self.assertFalse(evidence(out, "screenshot"))


class HttpCheckSample(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def device(self, route):
        return FakeDevice({"x": {"package": "p", "nodes": []}}, launch_map={}, clock=VirtualClock(),
                          http_routes={"https://example.com/": route})

    def test_healthy_endpoint_succeeds_quietly(self):
        dev = self.device({"status": 200, "text": "ok"})
        out = run_task(support.SAMPLES / "http-check", device=dev, config={"domain": "example.com", "path": "/"})
        self.assertEqual((out.result.code, out.result.data["status"]), ("ok", 200))
        self.assertEqual(dev.notifications, [])

    def test_failing_endpoint_notifies_and_asks_for_a_retry(self):
        dev = self.device({"status": 500, "text": "err"})
        out = run_task(support.SAMPLES / "http-check", device=dev, config={"domain": "example.com", "path": "/"})
        self.assertEqual(out.result.code, "failed")
        self.assertEqual(len(dev.notifications), 1)
        nxt = [e for e in out.events if e.get("type") == "next_run"]
        self.assertEqual(nxt[0]["data"]["after_minutes"], 5)

    def test_timeout_is_treated_as_status_zero(self):
        dev = self.device("timeout")
        out = run_task(support.SAMPLES / "http-check", device=dev, config={"domain": "example.com", "path": "/"})
        self.assertEqual(out.result.data["status"], 0)
        self.assertEqual(len(dev.notifications), 1)


class ScriptErrors(unittest.TestCase):
    def tearDown(self):
        uninstall()

    def test_a_script_bug_is_a_failed_run_not_a_crash(self):
        import tempfile, json, pathlib
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            (p / "task.json").write_text(json.dumps({"schema": 1, "id": "t.bug", "name": "b", "version": "1.0.0",
                                                     "runtime": {"type": "python", "entry": "main.py"}}))
            (p / "main.py").write_text("def main():\n    return 1 / 0\n")
            out = run_task(p, device=support.press_device())
            self.assertEqual(out.status, "FAILED")
            self.assertEqual(out.reason, "EXCEPTION")
            self.assertIn("ZeroDivisionError", out.message)

    def test_missing_permission_is_a_failed_run_with_the_broker_code(self):
        dev = support.press_device()
        out = run_task(support.SAMPLES / "press-button", device=dev,
                       config={**PRESS_CONFIG, "target_package": "com.missing.app"})
        self.assertEqual(out.status, "FAILED")
        self.assertEqual(out.reason, "NOT_FOUND")


if __name__ == "__main__":
    unittest.main()


class CapabilitiesShowcaseSample(unittest.TestCase):
    """P8-15: the honest availability sweep — harmless sections run in the sim; every row is real."""

    SAMPLE = support.SAMPLES / "capabilities-showcase"

    def test_sweep_reports_honest_rows(self):
        device = FakeDevice(
            {"main": {"package": "com.fixture", "nodes": []}},
            launch_map={"com.fixture": "main"},
        )
        device.inject_notification("n1", "com.mail", title="Hi")
        device.emit_event("screen.on")
        device.chain_results["app.autohost.sample.hello"] = {"status": "SUCCEEDED", "result": "ok"}
        outcome = run_task(
            self.SAMPLE,
            device=device,
            config={"sections": ["clipboard", "intents", "notifications", "events", "audio",
                                 "power", "screen_control", "settings_read", "webhooks", "chaining"],
                    "chain_target": "app.autohost.sample.hello"},
        )
        self.assertEqual("SUCCEEDED", outcome.status)
        results = outcome.result.data
        self.assertTrue(results["clipboard"]["ok"])
        self.assertEqual("AutoHost showcase", results["clipboard"]["read_back"])
        self.assertTrue(results["audio"]["ok"])
        self.assertEqual(80, results["power"]["battery"]["level"])
        self.assertTrue(results["chaining"]["ok"])
        self.assertEqual("SUCCEEDED", results["chaining"]["status"])
        self.assertTrue(results["notifications"]["ok"])
        self.assertEqual(1, results["notifications"]["visible"])
        self.assertIn("screen.on", results["events"]["recent"])

    def test_files_section_is_honestly_skipped_without_a_tree(self):
        device = FakeDevice(
            {"main": {"package": "com.fixture", "nodes": []}},
            launch_map={"com.fixture": "main"},
        )
        outcome = run_task(self.SAMPLE, device=device, config={"sections": ["files_shared"]})
        self.assertEqual("SUCCEEDED", outcome.status)
        self.assertIn("skipped", outcome.result.data["files_shared"])


class WebLoginScrapeSample(unittest.TestCase):
    """P10-02: the Gate 10-a sample — local login page in the visible browser, then scrape."""

    SAMPLE = support.SAMPLES / "web-login-scrape"

    def tearDown(self):
        uninstall()

    def test_logs_into_the_local_page_and_scrapes(self):
        device = FakeDevice({"main": {"package": "com.fixture", "nodes": []}},
                            launch_map={"com.fixture": "main"})
        device.add_browser_grant(None)
        device.add_web_page(
            "task:web/login.html", title="AutoHost Local Login",
            values={"#status": ["signed in"]}, cookies={"session": "local-1"},
        )
        out = run_task(self.SAMPLE, device=device, config={"username": "demo"},
                       secrets={"demo_password": "correct horse"})
        self.assertEqual("SUCCEEDED", out.status, out.message)
        self.assertEqual("ok", out.result.code)
        self.assertTrue(out.result.data["session_cookie"])
        # one page was opened with the local URL and closed again
        self.assertEqual(["task:web/login.html"], [p["url"] for p in device.web_pages_opened])
        self.assertNotIn("correct horse", str(out.result.data))  # never echoed

    def test_challenge_page_pauses_for_the_user(self):
        device = FakeDevice({"main": {"package": "com.fixture", "nodes": []}},
                            launch_map={"com.fixture": "main"})
        device.add_browser_grant(None)
        device.add_web_page(
            "task:web/challenge.html", title="Attention Required",
            challenge={"kind": "anti_bot", "matched": "title:/Attention required/"},
        )
        out = run_task(self.SAMPLE, device=device, config={"username": "demo", "open_challenge": True},
                       secrets={"demo_password": "correct horse"})
        self.assertEqual("WAITING_USER", out.status, out.message)
        self.assertEqual("SECURITY_CHALLENGE", out.reason)

    def test_missing_secret_fails_honestly(self):
        device = FakeDevice({"main": {"package": "com.fixture", "nodes": []}},
                            launch_map={"com.fixture": "main"})
        device.add_browser_grant(None)
        device.add_web_page("task:web/login.html", title="AutoHost Local Login",
                            values={"#status": ["signed in"]}, cookies={"session": "local-1"})
        out = run_task(self.SAMPLE, device=device, config={"username": "demo"})
        self.assertEqual("FAILED", out.status)


class OcrDemoSample(unittest.TestCase):
    """P10-05: the Gate 10-b sample — OCR/template/QR with the hint:true contract."""

    SAMPLE = support.SAMPLES / "ocr-demo"

    def tearDown(self):
        uninstall()

    def test_reads_the_region_and_keeps_hints_honest(self):
        device = FakeDevice({"main": {"package": "com.fixture", "nodes": []}},
                            launch_map={"com.fixture": "main"})
        device.add_grants("vision.read")
        device.add_ocr(["TOTAL 12.40", "CARD **42"])
        device.set_template_result(found=True, score=0.95, box=(10, 20, 60, 28))
        device.add_qr("otpauth://demo")
        out = run_task(self.SAMPLE, device=device, config={
            "region": {"left": 0, "top": 0, "width": 400, "height": 80},
        })
        self.assertEqual("SUCCEEDED", out.status, out.message)
        data = out.result.data
        self.assertTrue(data["hint"])
        self.assertEqual(["TOTAL 12.40", "CARD **42"], data["ocr_lines"])
        self.assertTrue(data["qr_found"])
