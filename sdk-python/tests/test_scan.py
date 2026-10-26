"""Tests for autohost._scan (P2-13 import scanner)."""

import json
import tempfile
import textwrap
import unittest
from pathlib import Path

from autohost import _scan


def make_bundle(files: dict) -> Path:
    root = Path(tempfile.mkdtemp(prefix="ah-scan-"))
    for name, content in files.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(textwrap.dedent(content), encoding="utf-8")
    return root


class ScanDirTest(unittest.TestCase):
    def test_buckets_stdlib_bundled_vendored_unavailable(self):
        root = make_bundle({
            "main.py": """
                import os
                import requests
                import mypackage
                import obscure_lib
            """,
        })
        report = _scan.scan_dir(root, bundled=["requests"], vendored=["mypackage"])
        self.assertEqual(1, report["files"])
        self.assertEqual(1, report["stdlib"])
        self.assertEqual(1, report["bundled"])
        self.assertEqual(1, report["vendored"])
        self.assertEqual(["obscure_lib"], report["unavailable"])
        self.assertEqual([], report["desktopOnly"])

    def test_desktop_only_flagged_with_suggestions(self):
        root = make_bundle({
            "main.py": """
                import os
                import playwright
                import ctypes
                from tkinter import Tk
            """,
        })
        report = _scan.scan_dir(root)
        flagged = {hit["module"] for hit in report["desktopOnly"]}
        self.assertEqual({"playwright", "ctypes", "tkinter"}, flagged)
        self.assertEqual([], report["unavailable"])  # desktop-only wins
        for hit in report["desktopOnly"]:
            self.assertTrue(hit["suggestion"])
            self.assertEqual("main.py", hit["file"])

    def test_relative_imports_are_not_dependencies(self):
        root = make_bundle({
            "main.py": """
                from .helpers import thing
                from ..other import x
                import json
            """,
            "helpers/__init__.py": "thing = 1\n",
        })
        report = _scan.scan_dir(root)
        self.assertEqual(1, report["stdlib"])  # json counted as stdlib
        self.assertEqual([], report["unavailable"])  # relative imports are not dependencies

    def test_syntax_error_recorded_as_unavailable_marker(self):
        root = make_bundle({
            "broken.py": "def broken(:\n",
        })
        report = _scan.scan_dir(root)
        self.assertEqual(1, len(report["unavailable"]))
        self.assertTrue(report["unavailable"][0].startswith("<syntax-error"))

    def test_desktop_binary_subprocess_calls_flagged(self):
        root = make_bundle({
            "main.py": """
                import subprocess
                subprocess.run("adb push x y", check=True)
                subprocess.call(["scrcpy", "--record"])
                os.system("explorer.exe")
            """,
        })
        report = _scan.scan_dir(root)
        flagged = {hit["module"] for hit in report["desktopOnly"]}
        self.assertTrue({"adb", "scrcpy", "explorer"} & flagged)

    def test_json_report_shape_matches_kotlin_buckets(self):
        root = make_bundle({"main.py": "import json\nimport playwright\n"})
        report = json.loads(_scan.scan_json(root))
        for key in ("files", "stdlib", "bundled", "vendored", "unavailable", "desktopOnly"):
            self.assertIn(key, report)

    def test_scan_json_of_the_sdk_itself_is_clean_of_desktop_modules(self):
        sdk_root = Path(__file__).resolve().parents[1] / "autohost"
        report = _scan.scan_dir(sdk_root)
        self.assertEqual([], [h for h in report["desktopOnly"] if h["module"] != "ctypes"])

    def test_raw_bridge_usage_flagged(self):
        """P8-12: Chaquopy imports and autohost.android calls feed the import guard."""
        root = make_bundle({
            "main.py": """
                from autohost import android
                android.authorize()
                android.jclass("java.lang.String")
            """,
            "helper.py": "import java\n",
            "clean.py": "import autohost.sel\n",
        })
        report = _scan.scan_dir(root)
        self.assertEqual({"main.py", "helper.py"}, set(report["rawBridge"]))

    def test_raw_bridge_not_flagged_for_lookalikes(self):
        root = make_bundle({
            "main.py": """
                def jclass(x):
                    return x
                jclass(1)
                from autohost import sel
                myandroid = object()
                myandroid.jclass(1)
            """,
        })
        report = _scan.scan_dir(root)
        self.assertEqual([], report["rawBridge"])

    def test_json_report_carries_raw_bridge(self):
        root = make_bundle({"main.py": "from com.chaquo.python import py\n"})
        report = json.loads(_scan.scan_json(root))
        self.assertIn("rawBridge", report)


if __name__ == "__main__":
    unittest.main()
