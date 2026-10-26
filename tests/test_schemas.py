"""Schema contract tests: samples validate, and each kind of bad input is rejected for the right reason."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from jsonschema_mini import SchemaError, validate  # noqa: E402

TASK = json.loads((ROOT / "schemas/task.schema.json").read_text(encoding="utf-8"))
TRIGGER = json.loads((ROOT / "schemas/trigger-rule.schema.json").read_text(encoding="utf-8"))
FLOW = json.loads((ROOT / "schemas/flow.schema.json").read_text(encoding="utf-8"))
HELLO = json.loads((ROOT / "samples/hello/task.json").read_text(encoding="utf-8"))


class SamplesValidate(unittest.TestCase):
    def test_every_shipped_manifest_is_valid(self):
        for path in sorted((ROOT / "samples").glob("*/task.json")):
            with self.subTest(sample=path.parent.name):
                self.assertEqual(validate(json.loads(path.read_text(encoding="utf-8")), TASK), [])

    def test_flow_yaml_is_valid(self):
        import yaml
        flow = yaml.safe_load((ROOT / "samples/press-button-flow/flow.yaml").read_text(encoding="utf-8"))
        self.assertEqual(validate(flow, FLOW), [])


class ManifestRejects(unittest.TestCase):
    def bad(self, mutate, needle):
        m = copy.deepcopy(HELLO)
        mutate(m)
        errs = validate(m, TASK)
        self.assertTrue(any(needle in e for e in errs), f"expected '{needle}' in {errs}")

    def test_missing_id(self):
        self.bad(lambda m: m.pop("id"), "missing required property 'id'")

    def test_bad_id_format(self):
        self.bad(lambda m: m.__setitem__("id", "Bad_ID"), "does not match")

    def test_bad_version(self):
        self.bad(lambda m: m.__setitem__("version", "1.0"), "does not match")

    def test_unknown_top_level_key(self):
        self.bad(lambda m: m.__setitem__("surprise", 1), "unknown property 'surprise'")

    def test_python_entry_must_be_py(self):
        self.bad(lambda m: m["runtime"].__setitem__("entry", "main.yaml"), "does not match")

    def test_bad_capability_id(self):
        m = copy.deepcopy(HELLO)
        m["needs"]["capabilities"] = [{"id": "Device.UI"}]
        self.assertTrue(validate(m, TASK))

    def test_declarative_ui_requires_a_file(self):
        self.bad(lambda m: m.__setitem__("ui", {"mode": "declarative"}), "missing required property 'file'")

    def test_bad_enum_value(self):
        self.bad(lambda m: m["execution"].__setitem__("verification", "maybe"), "is not one of")

    def test_out_of_range_timeout(self):
        self.bad(lambda m: m["execution"].__setitem__("timeout_minutes", 0), "below minimum")

    def test_secret_flag_must_be_boolean(self):
        m = copy.deepcopy(HELLO)
        m["profile_schema"]["properties"]["pw"] = {"type": "string", "x-secret": "yes"}
        self.assertTrue(validate(m, TASK))


class TriggerRules(unittest.TestCase):
    def ok(self, rule):
        self.assertEqual(validate(rule, TRIGGER), [])

    def bad(self, rule, needle):
        errs = validate(rule, TRIGGER)
        self.assertTrue(any(needle in e for e in errs), f"expected '{needle}' in {errs}")

    def test_notification_rule_with_guards(self):
        self.ok({"source": "notification.posted", "task": "t.a", "params": {"package": "com.x"},
                 "cooldown_seconds": 60, "debounce_ms": 500, "busy_policy": "queue", "queue_max": 3})

    def test_daily_needs_a_valid_time(self):
        self.bad({"source": "time.daily", "task": "t.a", "params": {"time": "25:00"}}, "does not match")

    def test_battery_level_needs_op_and_percent(self):
        self.bad({"source": "battery.level", "task": "t.a", "params": {"percent": 20}}, "missing required property 'op'")

    def test_unknown_source_is_rejected(self):
        self.bad({"source": "moon.phase", "task": "t.a"}, "is not one of")

    def test_busy_policy_enum(self):
        self.bad({"source": "power.connected", "task": "t.a", "busy_policy": "yolo"}, "is not one of")

    def test_chain_needs_a_task(self):
        self.bad({"source": "task.chain", "task": "t.a", "params": {"on": "failed"}}, "missing required property 'task'")


class FlowSteps(unittest.TestCase):
    def ok(self, doc):
        self.assertEqual(validate(doc, FLOW), [])

    def test_one_command_per_step(self):
        self.ok({"schema": 1, "steps": [{"launchApp": {"package": "com.x"}}]})

    def test_two_commands_in_one_step_are_rejected(self):
        errs = validate({"schema": 1, "steps": [{"launchApp": {}, "tapOn": {}}]}, FLOW)
        self.assertTrue(any("more than 1 properties" in e for e in errs), errs)

    def test_unknown_command_is_rejected(self):
        errs = validate({"schema": 1, "steps": [{"teleport": {}}]}, FLOW)
        self.assertTrue(errs)

    def test_empty_steps_are_rejected(self):
        self.assertTrue(validate({"schema": 1, "steps": []}, FLOW))

    def test_selector_with_unknown_key_is_rejected(self):
        errs = validate({"schema": 1, "steps": [{"tapOn": {"banana": "x"}}]}, FLOW)
        self.assertTrue(errs)

    def test_selector_nested_under_selector_key_is_rejected(self):
        self.assertTrue(validate({"schema": 1, "steps": [{"tapOn": {"selector": {"text": ["x"]}}}]}, FLOW))

    def test_wait_until_body_is_checked(self):
        self.assertTrue(validate({"schema": 1, "steps": [{"waitUntil": {"visibel": {"text": ["x"]}}}]}, FLOW))

    def test_launch_app_needs_a_package(self):
        self.assertTrue(validate({"schema": 1, "steps": [{"launchApp": {}}]}, FLOW))

    def test_valid_modifiers_are_accepted(self):
        self.ok({"schema": 1, "steps": [{"tapOn": {"text": ["Continue"], "retryTapIfNoChange": True, "waitToSettle": True, "timeout": 5}}]})


class ValidatorItself(unittest.TestCase):
    def test_unsupported_keyword_is_refused_not_ignored(self):
        with self.assertRaises(SchemaError):
            validate("x", {"type": "string", "format": "email"})

    def test_unknown_ref_target_raises(self):
        with self.assertRaises(KeyError):
            validate("x", {"$ref": "#/$defs/nope"})


if __name__ == "__main__":
    unittest.main()
