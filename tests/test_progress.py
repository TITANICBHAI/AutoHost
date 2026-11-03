import unittest

from tools import progress


def _validate(tracker):
    items, _ = progress.parse(tracker)
    return progress.validate(items, tracker)


class ProgressValidationTest(unittest.TestCase):
    def test_later_phase_task_can_be_ticked_while_earlier_gate_is_open(self):
        tracker = """## Phase 0 — Setup
- [ ] **G0-a** Physical-device gate

## Phase 1 — First task
- [x] **P1-01** Verified JVM task [M]
  - evidence: gradle :core:model:test — exit 0; JDK 17
"""
        items, _ = progress.parse(tracker)

        errors, _ = progress.validate(items, tracker)

        self.assertEqual([], errors)

    def test_ticked_task_still_requires_evidence(self):
        tracker = """## Phase 0 — Setup
- [ ] **G0-a** Physical-device gate

## Phase 1 — First task
- [x] **P1-01** Verified JVM task [M]
"""
        items, _ = progress.parse(tracker)

        errors, _ = progress.validate(items, tracker)

        self.assertEqual(1, len(errors))
        self.assertIn("P1-01: ticked but evidence is missing", errors[0])


class SizeParsingTest(unittest.TestCase):
    def test_size_marker_is_found_when_a_tag_follows_it(self):
        items, _ = progress.parse(
            "## Phase 1 — X\n- [ ] **P1-09** (WRITTEN) Python SDK v0 [M] (FAILING: env lacks PyYAML)\n")

        self.assertEqual("M", items[0].size)
        self.assertEqual(2, items[0].weight)

    def test_last_size_marker_wins(self):
        items, _ = progress.parse("## Phase 1 — X\n- [ ] **P1-02** Handle [S] and [L] cases [L]\n")

        self.assertEqual("L", items[0].size)

    def test_ticked_medium_task_with_trailing_tag_needs_evidence(self):
        tracker = "## Phase 1 — X\n- [x] **P1-09** Thing [M] (BLOCKED: x)\n"

        errors, _ = _validate(tracker)

        self.assertTrue(any("P1-09: ticked but evidence is missing" in e for e in errors), errors)


class LadderTest(unittest.TestCase):
    TRACKER = """## Phase 1 — X
- [ ] **P1-01** Planned only [S]
- [ ] **P1-02** (WRITTEN) Written [S]
- [ ] **P1-03** (COMPILED) Compiled [S]
  - evidence: gradle :core:model:compileKotlin exit 0
- [ ] **P1-04** (UNIT-TESTED) Unit tested [M]
  - evidence: gradle :core:model:test exit 0, 47 tests
- [ ] **P1-05** (INTEGRATION-TESTED) Integration tested [M]
  - evidence: connectedCoreDebugAndroidTest on emulator API 34, exit 0
- [x] **P1-06** Verified [S]
"""

    def test_levels_follow_the_state_tags_and_the_tick(self):
        items, _ = progress.parse(self.TRACKER)

        self.assertEqual(
            ["planned", "implemented", "compiled", "unit-tested", "integration-tested", "verified"],
            [i.level for i in items])

    def test_every_state_tag_counts_as_code_written(self):
        items, _ = progress.parse(self.TRACKER)

        self.assertEqual([False, True, True, True, True], [i.written for i in items[:5]])

    def test_a_ticked_item_is_written_for_the_percentages_without_a_tag(self):
        items, _ = progress.parse(self.TRACKER)

        # 5 of the 7 points (1+1+1+2+2+1... planned item excluded) are at or above "implemented"
        total = sum(i.weight for i in items)
        planned = items[0].weight
        self.assertAlmostEqual(100 * (total - planned) / total, progress.written_pct(items))

    def test_dashboard_ladder_line_counts_each_level(self):
        items, titles = progress.parse(self.TRACKER)

        dash = progress.dashboard(items, titles)

        self.assertIn(
            "**Ladder (items):** planned 1 · implemented 1 · compiled 1 · unit-tested 1 · "
            "integration-tested 1 · verified 1", dash)

    def test_dashboard_phase_table_has_compiled_and_tested_columns(self):
        items, titles = progress.parse(self.TRACKER)

        dash = progress.dashboard(items, titles)

        self.assertIn("| Phase | Status | Tasks verified | Verified | Written | Compiled | Tested | Gate |", dash)

    def test_two_state_tags_on_one_item_are_ambiguous(self):
        errors, _ = _validate("## Phase 1 — X\n- [ ] **P1-01** (WRITTEN) (COMPILED) Thing [S]\n  - evidence: x ran\n")

        self.assertTrue(any("P1-01: more than one state tag" in e for e in errors), errors)

    def test_compiled_or_higher_requires_evidence_even_for_small_items(self):
        for tag in ("(COMPILED)", "(UNIT-TESTED)", "(INTEGRATION-TESTED)"):
            with self.subTest(tag=tag):
                errors, _ = _validate(f"## Phase 1 — X\n- [ ] **P1-01** {tag} Thing [S]\n")

                self.assertTrue(any("P1-01: tagged" in e and "evidence" in e for e in errors), errors)

    def test_written_tag_needs_no_evidence(self):
        errors, _ = _validate("## Phase 1 — X\n- [ ] **P1-01** (WRITTEN) Thing [M]\n")

        self.assertEqual([], errors)

    def test_placeholder_evidence_does_not_count(self):
        errors, _ = _validate(
            "## Phase 1 — X\n- [ ] **P1-01** (UNIT-TESTED) Thing [S]\n  - evidence: TBD\n")

        self.assertTrue(any("P1-01: tagged" in e for e in errors), errors)

    def test_ticked_item_with_a_leftover_state_tag_warns(self):
        _, warns = _validate(
            "## Phase 1 — X\n- [x] **P1-01** (UNIT-TESTED) Thing [M]\n  - evidence: gradle test exit 0\n")

        self.assertTrue(any("P1-01" in w and "state tag" in w for w in warns), warns)


class FailingTagTest(unittest.TestCase):
    def test_failing_needs_a_reason(self):
        errors, _ = _validate("## Phase 1 — X\n- [ ] **P1-01** (WRITTEN) (FAILING) Thing [S]\n")

        self.assertTrue(any("P1-01: (FAILING) needs a reason" in e for e in errors), errors)

    def test_failing_with_a_reason_is_accepted(self):
        errors, _ = _validate("## Phase 1 — X\n- [ ] **P1-01** (WRITTEN) (FAILING: PyYAML missing) Thing [S]\n")

        self.assertEqual([], errors)

    def test_a_ticked_item_cannot_be_failing(self):
        errors, _ = _validate(
            "## Phase 1 — X\n- [x] **P1-01** (FAILING: x) Thing [M]\n  - evidence: gradle test exit 1\n")

        self.assertTrue(any("P1-01: ticked but tagged (FAILING" in e for e in errors), errors)

    def test_failing_items_are_counted_in_the_dashboard(self):
        items, titles = progress.parse(
            "## Phase 1 — X\n- [ ] **P1-01** (WRITTEN) (FAILING: a) One [S]\n- [ ] **P1-02** Two [S]\n")

        self.assertIn("failing 1", progress.dashboard(items, titles))


class DeviceEvidenceTest(unittest.TestCase):
    GATE = "## Phase 1 — X\n- [x] **G1-a** hello logs live (real device) — `docs/GATES.md#g1-a`\n"

    def test_device_class_items_are_detected_from_the_marker(self):
        items, _ = progress.parse(
            "## Phase 1 — X\n- [ ] **G1-a** Click works (real device)\n- [ ] **G1-b** JVM only\n")

        self.assertEqual([True, False], [i.device_class for i in items])

    def test_ticked_device_item_with_a_proper_device_clause_passes(self):
        tracker = self.GATE + ("  - evidence: device: Samsung SM-M315F (arm64-v8a), Android 12 / API 31; "
                               "steps run per GATES.md; log: evidence/g1-a.txt\n")

        errors, _ = _validate(tracker)

        self.assertEqual([], errors)

    def test_ticked_device_item_without_a_device_clause_fails(self):
        errors, _ = _validate(self.GATE + "  - evidence: ran the fixture and it passed\n")

        self.assertTrue(any("G1-a: real-device item needs 'device:'" in e for e in errors), errors)

    def test_emulator_inside_the_device_clause_is_rejected(self):
        tracker = self.GATE + "  - evidence: device: Pixel 6 emulator API 34; run per GATES.md\n"

        errors, _ = _validate(tracker)

        self.assertTrue(any("G1-a" in e and "emulator" in e for e in errors), errors)

    def test_device_clause_needs_an_api_level(self):
        errors, _ = _validate(self.GATE + "  - evidence: device: Samsung SM-M315F; run per GATES.md\n")

        self.assertTrue(any("G1-a" in e and "API level" in e for e in errors), errors)

    def test_emulator_runs_may_be_mentioned_outside_the_device_clause(self):
        tracker = self.GATE + ("  - evidence: device: Samsung SM-M315F, Android 12 / API 31; "
                               "also green on emulator API 34\n")

        errors, _ = _validate(tracker)

        self.assertEqual([], errors)

    def test_dashboard_counts_real_device_verified_separately(self):
        tracker = ("## Phase 1 — X\n"
                   "- [x] **G1-a** One (real device)\n"
                   "  - evidence: device: SM-M315F, API 31; steps done\n"
                   "- [ ] **G1-b** Two (real device)\n"
                   "- [x] **P1-01** JVM task [M]\n"
                   "  - evidence: gradle test exit 0\n")
        items, titles = progress.parse(tracker)

        self.assertIn("real-device-verified 1/2", progress.dashboard(items, titles))


class WrittenPercentTest(unittest.TestCase):
    def test_compiled_and_tested_items_count_as_written(self):
        items, _ = progress.parse(
            "## Phase 1 — X\n- [ ] **P1-01** (COMPILED) A [S]\n  - evidence: ok ran\n- [ ] **P1-02** B [S]\n")

        self.assertEqual(50, round(progress.written_pct(items)))

    def test_level_share_is_cumulative(self):
        items, _ = progress.parse(
            "## Phase 1 — X\n"
            "- [ ] **P1-01** (UNIT-TESTED) A [S]\n  - evidence: gradle test exit 0\n"
            "- [ ] **P1-02** (COMPILED) B [S]\n  - evidence: gradle compile exit 0\n")

        self.assertEqual(100, round(progress.level_pct(items, "compiled")))
        self.assertEqual(50, round(progress.level_pct(items, "unit-tested")))


if __name__ == "__main__":
    unittest.main()
