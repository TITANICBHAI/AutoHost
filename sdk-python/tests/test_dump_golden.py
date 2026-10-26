"""The compact dump reference against its own golden file, plus the rules the Kotlin side must reproduce."""
import json
import unittest

import support  # noqa: F401
from autohost_ref.dump import compact

GOLDEN = json.loads((support.ROOT / "testing" / "golden" / "compact_dump.json").read_text(encoding="utf-8"))


class DumpGolden(unittest.TestCase):
    def test_reference_reproduces_every_golden_case(self):
        for case in GOLDEN["cases"]:
            inp = dict(case["input"])
            nodes = inp.pop("nodes")
            flt = "default" if case["filter"] == "default" else None
            with self.subTest(case=case["name"]):
                self.assertEqual(compact(nodes, flt=flt, **inp), case["expected"])

    def test_password_text_never_appears(self):
        case = next(c for c in GOLDEN["cases"] if c["name"] == "password_and_focus")
        self.assertNotIn("hunter2", case["expected"])
        self.assertIn("alice", case["expected"])

    def test_filter_drops_tiny_and_offscreen_but_keeps_partly_visible(self):
        text = next(c for c in GOLDEN["cases"] if c["name"] == "filter_drops_tiny_and_offscreen")["expected"]
        self.assertNotIn('"tiny"', text)
        self.assertNotIn('"offscreen"', text)
        self.assertIn('"partly"', text)
        self.assertIn('"visible"', text)


if __name__ == "__main__":
    unittest.main()
