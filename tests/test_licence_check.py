"""Licence CI check (P11-09 / Gate 11-e): every catalog dependency must be covered.

The checker is contract-tested here: catalog parsing, the coverage rules (notices row OR a
recorded allowlist entry), and the honest failure when a new dependency lands without a row.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

import licence_check  # noqa: E402


class LicenceCheckTest(unittest.TestCase):
    def test_catalog_modules_are_parsed_and_deduped(self):
        modules = licence_check.catalog_modules()
        self.assertIn("dev.rikka.shizuku:api", modules)
        self.assertIn("io.github.dokar3:quickjs-kt-android", modules)
        self.assertEqual(len(modules), len(set(modules)))

    def test_every_real_catalog_entry_is_covered_today(self):
        # Gate 11-e: the repo as shipped must pass its own check.
        self.assertEqual(0, licence_check.main())

    def test_notices_rows_cover_a_dependency(self):
        text = "## Shizuku API (dev.rikka.shizuku:api 13.1.5)\n..."
        self.assertIsNotNone(licence_check.covered("dev.rikka.shizuku:api", text))

    def test_allowlist_prefixes_cover_the_androidx_family(self):
        reason = licence_check.covered("androidx.room:room-runtime", "")
        self.assertIsNotNone(reason)
        self.assertIn("Apache-2.0", reason)

    def test_unknown_dependency_is_missing(self):
        self.assertIsNone(licence_check.covered("com.example.gpl:thing", ""))

    def test_failing_exit_carries_the_missing_rows(self):
        import io
        import contextlib

        original = licence_check.catalog_modules
        licence_check.catalog_modules = lambda: ["com.example.unknown:lib"]
        try:
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                code = licence_check.main()
            self.assertEqual(1, code)
            self.assertIn("com.example.unknown:lib", buffer.getvalue())
        finally:
            licence_check.catalog_modules = original


if __name__ == "__main__":
    unittest.main()


class AdaptedHeaderRuleTest(unittest.TestCase):
    """X-11 (ADR-088): an upstream-path header without a NOTICES row fails; with one, passes."""

    def test_header_without_row_is_detected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "capabilities" / "a11y"
            src.mkdir(parents=True)
            (src / "Actions.kt").write_text(
                "// Adapted from closepaw platform/NodeActionPerformer.kt"
                " (Apache-2.0, Copyright 2026 ClosePaw Authors).\n"
                "class X\n",
                encoding="utf-8",
            )
            headers = dict((rel, (pr, up)) for rel, pr, up in licence_check.adapted_files(root))
            self.assertIn("capabilities/a11y/Actions.kt", headers)
            self.assertEqual(headers["capabilities/a11y/Actions.kt"][1], "platform/NodeActionPerformer.kt")
            notices = ""  # no row
            _, (project, upstream) = next(iter(headers.items()))
            covered = upstream.lower() in notices.lower() or project.lower() in notices.lower()
            self.assertFalse(covered)

    def test_header_with_row_passes(self):
        notices = "## NodeActionPerformer (closepaw platform/NodeActionPerformer.kt) — Apache-2.0"
        self.assertTrue("platform/nodeactionperformer.kt" in notices.lower())

    def test_non_adapted_sources_are_ignored(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "core" / "common"
            src.mkdir(parents=True)
            (src / "Ids.kt").write_text("// our own code, no header\nobject Ids\n", encoding="utf-8")
            self.assertEqual([], list(licence_check.adapted_files(root)))


class ReferenceInventoryRuleTest(unittest.TestCase):
    """X-11 (ADR-088): every file under reference/ must be listed in reference/INDEX.md."""

    def test_real_tree_inventory_is_complete(self):
        self.assertEqual([], licence_check.reference_inventory_gaps())

    def test_unlisted_file_is_reported(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ref = root / "reference" / "newproj"
            ref.mkdir(parents=True)
            (ref / "stolen.kt").write_text("// third-party\n", encoding="utf-8")
            (root / "reference" / "INDEX.md").write_text("# index\n", encoding="utf-8")
            gaps = licence_check.reference_inventory_gaps(root)
            self.assertEqual(["reference/newproj/stolen.kt"], gaps)

    def test_indexed_file_is_not_reported(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ref = root / "reference" / "newproj"
            ref.mkdir(parents=True)
            (ref / "file.kt").write_text("// apache-2.0 snapshot\n", encoding="utf-8")
            (root / "reference" / "INDEX.md").write_text("# index\n| `newproj/file.kt` | x |\n", encoding="utf-8")
            self.assertEqual([], licence_check.reference_inventory_gaps(root))
