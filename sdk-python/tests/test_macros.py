"""INT-D: autohost.macros on top of tasks.run, against the simulator (what a script sees when it runs a macro)."""
import json
import unittest

import support
from autohost import errors, macros
from autohost_sim import FakeDevice

SCREENS = {"main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []}}


def done(code="OK", **data):
    """The answer the host gives for a child that finished with setResult(code, data)."""
    return {"status": "SUCCEEDED", "result": json.dumps({"code": code, "data": data})}


class MacrosRun(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        support.runtime_for(self.device)

    def test_returns_the_code_and_data_the_macro_set(self):
        self.device.chain_results["gemini-copy-idea"] = done("COPIED", idea="a cat on a skateboard")
        result = macros.run("gemini-copy-idea")
        self.assertTrue(result.ok)
        self.assertEqual("COPIED", result.code)
        self.assertEqual("a cat on a skateboard", result.data["idea"])

    def test_inputs_reach_the_host_and_none_is_left_out(self):
        self.device.chain_results["gemini-send"] = done()
        macros.run("gemini-send", {"prompt": "hello", "count": 2, "loud": True, "skip": None})
        call = self.device.chain_calls[-1]
        self.assertEqual("gemini-send", call["task"])
        self.assertEqual({"prompt": "hello", "count": 2, "loud": True}, call["args"])

    def test_no_inputs_sends_no_args(self):
        self.device.chain_results["m"] = done()
        macros.run("m")
        self.assertNotIn("args", self.device.chain_calls[-1])

    def test_a_plain_text_answer_comes_back_as_text(self):
        self.device.chain_results["m"] = {"status": "SUCCEEDED", "result": "just text"}
        result = macros.run("m")
        self.assertEqual("just text", result.text)
        self.assertIsNone(result.code)
        self.assertEqual({}, result.data)

    def test_no_answer_at_all_is_still_a_success(self):
        self.device.chain_results["m"] = {"status": "SUCCEEDED"}
        result = macros.run("m")
        self.assertTrue(result.ok)
        self.assertIsNone(result.text)
        self.assertEqual({}, result.data)

    def test_a_failed_macro_raises_unless_check_is_off(self):
        self.device.chain_results["m"] = {"status": "FAILED"}
        with self.assertRaises(errors.Fatal):
            macros.run("m")
        result = macros.run("m", check=False)
        self.assertFalse(result.ok)
        self.assertEqual("FAILED", result.status)

    def test_a_macro_waiting_for_the_user_raises_needs_user(self):
        self.device.chain_results["m"] = {"status": "WAITING_USER"}
        with self.assertRaises(errors.NeedsUser):
            macros.run("m")

    def test_a_macro_still_running_raises_timeout(self):
        self.device.chain_results["m"] = {"status": "RUNNING", "await": "timeout"}
        with self.assertRaises(errors.Timeout):
            macros.run("m")

    def test_an_unknown_macro_is_not_found(self):
        with self.assertRaises(errors.NotFound):
            macros.run("nope")

    def test_bad_input_values_are_refused_before_the_host_is_called(self):
        self.device.chain_results["m"] = done()
        for bad in ({"a": [1]}, {"a": {"b": 1}}, {"a": float("nan")}, {"": "x"}):
            with self.assertRaises(errors.InvalidArgs):
                macros.run("m", bad)
        self.assertEqual([], self.device.chain_calls)

    def test_a_macro_id_is_required(self):
        with self.assertRaises(errors.InvalidArgs):
            macros.run("")

    def test_the_module_is_part_of_the_package(self):
        import autohost
        self.assertIs(macros, autohost.macros)
        self.assertIn("macros", autohost.__all__)


if __name__ == "__main__":
    unittest.main()
