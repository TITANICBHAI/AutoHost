import json
import unittest

import support  # noqa: F401  (sets sys.path)
from autohost import errors
from autohost._envelope import unwrap


class ErrorContract(unittest.TestCase):
    def test_every_broker_code_maps_to_the_class_named_in_the_schema(self):
        spec = json.loads((support.SCHEMAS / "errors.json").read_text(encoding="utf-8"))
        for entry in spec["brokerErrors"]:
            cls = errors.BROKER_CODE_CLASSES.get(entry["code"])
            self.assertIsNotNone(cls, f"{entry['code']} has no exception class")
            self.assertEqual(cls.__name__, entry["sdkException"], entry["code"])

    def test_no_extra_codes_in_the_sdk(self):
        spec = json.loads((support.SCHEMAS / "errors.json").read_text(encoding="utf-8"))
        known = {e["code"] for e in spec["brokerErrors"]}
        self.assertEqual(set(errors.BROKER_CODE_CLASSES), known)

    def test_envelope_error_becomes_exception_with_code_and_fix(self):
        env = {"ok": False, "error": {"code": "STALE_NODE", "message": "gone", "retryable": True,
                                      "fixAction": "NONE"}}
        with self.assertRaises(errors.StaleNode) as cm:
            unwrap(env)
        self.assertEqual(cm.exception.code, "STALE_NODE")
        self.assertTrue(cm.exception.retryable)
        self.assertEqual(cm.exception.fix_action, "NONE")

    def test_unknown_code_is_fatal_not_silent(self):
        with self.assertRaises(errors.Fatal):
            unwrap({"ok": False, "error": {"code": "SOMETHING_NEW", "message": "?"}})

    def test_malformed_envelope_is_rejected(self):
        with self.assertRaises(errors.Fatal):
            unwrap({"result": 1})

    def test_success_returns_result(self):
        self.assertEqual(unwrap({"ok": True, "result": {"a": 1}}), {"a": 1})


if __name__ == "__main__":
    unittest.main()
