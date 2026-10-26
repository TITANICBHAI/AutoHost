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
