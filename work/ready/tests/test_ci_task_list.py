"""scripts/unit-test-tasks.txt (what CI's one Gradle run executes) must list EVERY module exactly once, with the
right task for its kind. A module that is missing from the list never has its tests run (that is how :runtime:agent
and :core:llm went unnoticed); a bare `:x:test` on an Android module runs debug AND release variants.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TASKS = ROOT / "scripts" / "unit-test-tasks.txt"
# the app has flavors, so its unit-test task carries the flavor; coreLite is the Robolectric launch-smoke variant
APP_TASKS = {":app:testCorePyDebugUnitTest", ":app:testCoreLiteDebugUnitTest"}


def modules() -> list:
    text = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    return re.findall(r'^include\("(:[^"]+)"\)', text, re.MULTILINE)


def kind(module: str) -> str:
    build = (ROOT / module.strip(":").replace(":", "/") / "build.gradle.kts").read_text(encoding="utf-8")
    if "libs.plugins.kotlin.jvm" in build:
        return "jvm"
    if "libs.plugins.android.library" in build or "libs.plugins.android.application" in build:
        return "android"
    raise AssertionError(f"cannot tell what kind of module {module} is")


class CiTaskListTest(unittest.TestCase):
    def setUp(self):
        self.tasks = [ln.strip() for ln in TASKS.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.startswith("#")]

    def test_every_module_has_a_test_task_and_nothing_extra(self):
        per_module = {}
        for task in self.tasks:
            per_module.setdefault(task.rsplit(":", 1)[0], []).append(task)
        self.assertEqual(sorted(modules()), sorted(per_module), "modules in settings.gradle.kts vs the task list")

    def test_each_task_matches_the_kind_of_its_module(self):
        for task in self.tasks:
            module, name = task.rsplit(":", 1)
            with self.subTest(task):
                if module == ":app":
                    self.assertIn(task, APP_TASKS)
                elif kind(module) == "jvm":
                    self.assertEqual("test", name)
                else:
                    self.assertEqual("testDebugUnitTest", name, "an Android module must not run bare `test` (debug + release)")

    def test_no_duplicates(self):
        self.assertEqual(len(self.tasks), len(set(self.tasks)))


if __name__ == "__main__":
    unittest.main()
