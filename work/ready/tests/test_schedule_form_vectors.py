"""testing/golden/schedule_form_vectors.json is the executable spec of the schedule form (UI_SPEC S10).

Checks: the committed file is exactly what tools/gen_schedule_form_vectors.py generates (no drift); every
produced rule is accepted by the Python reference scheduler; every invalid form reports its error keys; and a
rule for a non-UTC phone never carries the "UTC" default (the F-04 trap: "09:00" must mean the user's 09:00).
"""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "sdk-python"))

import gen_schedule_form_vectors as gen  # noqa: E402
from autohost_ref.schedule import next_fires, parse_rule  # noqa: E402

VECTORS = ROOT / "testing" / "golden" / "schedule_form_vectors.json"


class ScheduleFormVectorsTest(unittest.TestCase):
    def setUp(self):
        self.doc = json.loads(VECTORS.read_text(encoding="utf-8"))

    def test_committed_file_matches_the_generator(self):
        self.assertEqual(gen.build_doc(), self.doc, "regenerate: python3 tools/gen_schedule_form_vectors.py")

    def test_every_valid_case_is_a_rule_the_engine_accepts(self):
        valid = [c for c in self.doc["cases"] if "rule" in c]
        self.assertGreaterEqual(len(valid), 14)
        for case in valid:
            with self.subTest(case["name"]):
                self.assertEqual([], gen.validate(case["form"], case["now_ms"]))
                parsed = parse_rule(dict(case["rule"]))
                fires = next_fires(parsed, case["after_ms"], None, 5)
                self.assertTrue(fires, "a usable schedule must have a next fire")
                self.assertEqual(case["expected_next5"], fires)
                self.assertEqual(case["rule"], gen.form_to_rule(case["form"], case["rule_id"], case["now_ms"]))

    def test_every_invalid_case_reports_its_error_keys(self):
        invalid = [c for c in self.doc["cases"] if "errors" in c]
        self.assertGreaterEqual(len(invalid), 10)
        for case in invalid:
            with self.subTest(case["name"]):
                self.assertEqual(case["errors"], gen.validate(case["form"], case["now_ms"]))

    def test_the_rule_carries_the_phone_zone_not_the_utc_default(self):
        for case in self.doc["cases"]:
            if "rule" in case:
                with self.subTest(case["name"]):
                    self.assertEqual(case["form"]["zone"], case["rule"]["timezone"])
        kolkata = next(c for c in self.doc["cases"] if c["name"] == "daily_0900_kolkata")
        # 09:00 in Kolkata is 03:30 UTC, not 09:00 UTC
        self.assertEqual(3 * 3_600_000 + 30 * 60_000, kolkata["expected_next5"][0] % 86_400_000)

    def test_no_rule_needs_a_json_editor(self):
        # the form only ever emits these keys; limits / conditions / cron stay in Settings -> Developer
        allowed = {"id", "task", "type", "timezone", "every_minutes", "from", "anchor", "times", "days", "at",
                   "start", "end", "misfire", "profile_ids", "enabled"}
        for case in self.doc["cases"]:
            if "rule" in case:
                self.assertLessEqual(set(case["rule"]), allowed, case["name"])


if __name__ == "__main__":
    unittest.main()
