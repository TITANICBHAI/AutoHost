"""tools/doc_check.py contract: each rule has a passing and a failing case on a temp tree."""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

import doc_check  # noqa: E402

PROGRESS = "## Phase 1\n- [ ] **P1-01** thing [S]\n- [ ] **G1-a** gate — `docs/GATES.md#g1-a`\n- [ ] **X-01** cross\n"
GATES = "### G1-a Title\nbody\n"
DECISIONS = "### ADR-001 — title\ntext\n| ADR-002 | row form | why | when |\n"
MATRIX = "| RM-A1 | a | b | c | d | e | f |\n"
INDEX = "| `GATES.md` | x |\n| `PROGRESS.md` | x |\n| `DECISIONS.md` | x |\n| `REUSE_MATRIX.md` | x |\n"


def make(tmp: Path, extra: str = "", index: str = INDEX) -> Path:
    (tmp / "docs").mkdir()
    (tmp / "docs" / "PROGRESS.md").write_text(PROGRESS)
    (tmp / "docs" / "GATES.md").write_text(GATES)
    (tmp / "docs" / "DECISIONS.md").write_text(DECISIONS)
    (tmp / "docs" / "REUSE_MATRIX.md").write_text(MATRIX)
    (tmp / "docs" / "README.md").write_text(index)
    (tmp / "plan.md").write_text("See docs/GATES.md, P1-01, ADR-001, RM-A1, X-01, docs/GATES.md#g1-a.\n" + extra)
    return tmp


class DocCheckTest(unittest.TestCase):
    def run_check(self, extra: str = "", index: str = INDEX):
        with tempfile.TemporaryDirectory() as d:
            return doc_check.check(make(Path(d), extra, index))

    def test_clean_tree_passes(self):
        self.assertEqual([], self.run_check())

    def test_missing_path_is_reported(self):
        self.assertTrue(any(f.startswith("paths") and "docs/NOPE.md" in f for f in self.run_check("see docs/NOPE.md\n")))

    def test_path_with_wildcard_is_ignored(self):
        self.assertEqual([], self.run_check("see docs/*.md and prompts/<name>.md\n"))

    def test_unknown_gate_anchor_is_reported(self):
        self.assertTrue(any(f.startswith("gates") and "G9-z" in f for f in self.run_check("docs/GATES.md#g9-z\n")))

    def test_unknown_task_id_is_reported(self):
        self.assertTrue(any(f.startswith("tasks") and "P99-01" in f for f in self.run_check("see P99-01\n")))

    def test_adr_table_rows_count_as_defined(self):
        self.assertEqual([], self.run_check("see ADR-002\n"))

    def test_unknown_adr_is_reported(self):
        self.assertTrue(any(f.startswith("adr") and "ADR-777" in f for f in self.run_check("see ADR-777\n")))

    def test_unknown_reuse_row_is_reported(self):
        self.assertTrue(any(f.startswith("rm") and "RM-Q9" in f for f in self.run_check("see RM-Q9\n")))

    def test_unlisted_doc_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            root = make(Path(d))
            (root / "docs" / "EXTRA.md").write_text("x\n")
            self.assertTrue(any(f.startswith("index") and "EXTRA.md" in f for f in doc_check.check(root)))

    def test_known_missing_is_tolerated(self):
        self.assertEqual([], self.run_check("generated docs/SDK.md\n"))

    def test_main_returns_nonzero_on_findings(self):
        with tempfile.TemporaryDirectory() as d:
            root = make(Path(d), "see docs/NOPE.md\n")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = doc_check.main(["--root", str(root)])
            self.assertEqual(1, code)

    def test_main_runs_on_the_real_tree(self):
        # The real tree is reported by `python3 tools/doc_check.py`; here we only require that it executes.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = doc_check.main(["--root", str(Path(__file__).resolve().parents[1])])
        self.assertIn(code, (0, 1))


if __name__ == "__main__":
    unittest.main()
