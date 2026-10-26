import unittest

from tools import progress


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


if __name__ == "__main__":
    unittest.main()
