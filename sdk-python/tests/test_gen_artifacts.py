"""P5-15/P5-16: generated artifacts (drift) and the Skill-format agent pack."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent  # autohost/autohost
sys.path.insert(0, str(REPO / "sdk-python"))

from autohost_cli.pack import write_pack  # noqa: E402


class GenArtifactsTest(unittest.TestCase):
    """P5-15: the drift gate — committed outputs must match the spec (plan §5.5)."""

    def test_check_mode_passes_when_in_sync(self):
        result = subprocess.run(
            [sys.executable, str(REPO / "tools" / "gen_artifacts.py"), "--check"],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)

    def test_check_mode_detects_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            # regenerate into a temp copy with a doctored spec
            tmp_root = Path(tmp) / "repo"
            shutil.copytree(REPO, tmp_root, ignore=shutil.ignore_patterns(".git", "__pycache__"))
            spec_path = tmp_root / "schemas" / "capability-specs.w1.json"
            spec = json_load(spec_path)
            spec["capabilities"].append({
                "id": "device.ghost", "summary": "test-only", "permissions": [],
                "methods": [{"name": "boo"}], "minSdk": 29, "tiers": ["T1"],
            })
            spec_path.write_text(json.dumps(spec), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(tmp_root / "tools" / "gen_artifacts.py"), "--check"],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("drifted", result.stderr)

    def test_generated_catalog_lists_every_capability(self):
        sys.path.insert(0, str(REPO / "sdk-python"))
        import importlib

        gen = importlib.import_module("autohost._capabilities_gen")
        spec = json_load(REPO / "schemas" / "capability-specs.w1.json")
        self.assertEqual({c["id"] for c in gen.CATALOG}, {c["id"] for c in spec["capabilities"]})


class AgentPackTest(unittest.TestCase):
    def test_pack_contains_skill_references_and_examples(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = write_pack(tmp, device_info={"api": 29, "flavor": "core"})
            self.assertEqual(out, tmp)
            skill = (Path(tmp) / "SKILL.md").read_text(encoding="utf-8")
            self.assertIn("autohost_export_run", skill)          # the loop names the real tools
            refs = sorted(os.listdir(Path(tmp) / "references"))
            self.assertIn("sdk.md", refs)
            self.assertIn("capabilities.md", refs)
            self.assertIn("pitfalls.md", refs)
            self.assertIn("task.schema.json", refs)
            self.assertIn("examples", os.listdir(tmp))
            self.assertTrue(any(name == "hello" for name in os.listdir(Path(tmp) / "examples")))

    def test_capabilities_reference_injects_live_availability(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_pack(tmp, device_info={"providers": {"device.ui": {"available": True}}})
            text = (Path(tmp) / "references" / "capabilities.md").read_text(encoding="utf-8")
            self.assertIn("Live availability", text)
            self.assertIn("device.ui", text)


def json_load(path: Path):
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


if __name__ == "__main__":
    unittest.main()
