"""testing/golden/script_manifest_vectors.json is the executable spec of `ManifestFactory` (Part 2 B-00).

Checks: no drift against tools/gen_script_manifest_vectors.py; every manifest passes schemas/task.schema.json
(same id pattern, same allowed keys, same bounds the host enforces); invalid inputs report their error keys;
the permission expansion always carries a scope where the capability needs one; names and slugs stay in bounds.
"""
import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import gen_script_manifest_vectors as gen  # noqa: E402
from jsonschema_mini import validate  # noqa: E402

VECTORS = ROOT / "testing" / "golden" / "script_manifest_vectors.json"
SPECS = ROOT / "schemas" / "capability-specs.w1.json"
ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z0-9][a-z0-9-]*)+$")


def known_permission_ids() -> set:
    doc = json.loads(SPECS.read_text(encoding="utf-8"))
    caps = doc.get("capabilities") or doc
    return {p["id"] if isinstance(p, dict) else p for c in caps if isinstance(c, dict) for p in c.get("permissions", [])}


class ScriptManifestVectorsTest(unittest.TestCase):
    def setUp(self):
        self.doc = json.loads(VECTORS.read_text(encoding="utf-8"))
        self.schema = json.loads((ROOT / "schemas" / "task.schema.json").read_text(encoding="utf-8"))

    def test_committed_file_matches_the_generator(self):
        self.assertEqual(gen.build_doc(), self.doc, "regenerate: python3 tools/gen_script_manifest_vectors.py")

    def test_every_manifest_passes_the_task_schema(self):
        valid = [c for c in self.doc["cases"] if "manifest" in c]
        self.assertGreaterEqual(len(valid), 12)
        for case in valid:
            with self.subTest(case["name"]):
                self.assertEqual([], validate(case["manifest"], self.schema))
                self.assertRegex(case["manifest"]["id"], ID_RE)
                self.assertLessEqual(len(case["manifest"]["name"]), 80)

    def test_every_invalid_case_reports_its_error_keys(self):
        invalid = [c for c in self.doc["cases"] if "errors" in c]
        self.assertGreaterEqual(len(invalid), 6)
        for case in invalid:
            with self.subTest(case["name"]):
                i = case["input"]
                self.assertEqual(case["errors"], gen.validate_inputs(i["kind"], i["display_name"], i["entry"], i["permissions"]))

    def test_grants_use_real_permission_ids_and_carry_their_scope(self):
        known = known_permission_ids()
        for case in self.doc["cases"]:
            for grant in case.get("manifest", {}).get("needs", {}).get("capabilities", []):
                with self.subTest(f'{case["name"]}:{grant["id"]}'):
                    self.assertIn(grant["id"], known)
                    if grant["id"].startswith("device.") :
                        self.assertTrue(grant["scope"]["packages"], "app control must name the apps")
                    if grant["id"] == "net.http":
                        self.assertTrue(grant["scope"]["domains"], "the internet must name the websites")

    def test_ui_scripts_are_exclusive_and_need_an_unlocked_screen(self):
        for case in self.doc["cases"]:
            if "manifest" in case:
                ex = case["manifest"]["execution"]
                uses_ui = any(g["id"].startswith("device.ui") for g in case["manifest"]["needs"]["capabilities"])
                self.assertEqual(uses_ui, ex["ui_exclusive"], case["name"])
                self.assertEqual(uses_ui, ex["needs_unlocked_screen"], case["name"])

    def test_slug_rules(self):
        self.assertEqual("my-script-2", gen.slug("My Script (2)"))
        self.assertEqual("script", gen.slug("___"))
        self.assertEqual("n-code-skript", gen.slug("Ünïcode Skript"))
        self.assertLessEqual(len(gen.slug("x" * 300)), 40)
        self.assertFalse(gen.slug("-a-").startswith("-"))

    def test_no_reserved_config_keys_are_used(self):
        for case in self.doc["cases"]:
            props = (case.get("manifest", {}).get("config_schema") or {}).get("properties", {})
            self.assertFalse({"packs", "agent"} & set(props), case["name"])


if __name__ == "__main__":
    unittest.main()
