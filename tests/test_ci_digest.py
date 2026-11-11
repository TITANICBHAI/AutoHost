"""tools/ci_digest.py: the plain summary a blind agent reads instead of the Actions UI."""
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import ci_digest  # noqa: E402

PASS_XML = """<?xml version="1.0"?>
<testsuite name="a.B" tests="2" skipped="1" failures="0" errors="0">
  <testcase name="ok" classname="a.B"/><testcase name="later" classname="a.B"><skipped/></testcase>
</testsuite>"""
FAIL_XML = """<?xml version="1.0"?>
<testsuite name="c.D" tests="2" skipped="0" failures="1" errors="0">
  <testcase name="good" classname="c.D"/>
  <testcase name="bad thing" classname="c.D"><failure message="expected:&lt;1&gt; but was:&lt;2&gt;" type="AssertionError">trace</failure></testcase>
</testsuite>"""
LOG_COMPILE = """> Task :app:compileCorePyDebugKotlin FAILED
e: file:///home/runner/work/AutoHost/AutoHost/app/src/main/kotlin/app/autohost/Foo.kt:12:5 Unresolved reference: bar
e: file:///home/runner/work/AutoHost/AutoHost/app/src/main/kotlin/app/autohost/Foo.kt:30:1 Type mismatch
FAILURE: Build failed with an exception.
"""
LOG_HANG = """app.autohost.X > first STARTED
app.autohost.X > second STARTED
> Task :runtime:flow:test FAILED
* What went wrong:
Execution failed for task ':runtime:flow:test'.
> Timeout has been exceeded
"""


def put(root: Path, rel: str, text: str):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class CiDigestTest(unittest.TestCase):
    def digest(self, files: dict, log: str = "", tasks: str = "", outcome: str = "success") -> str:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, text in files.items():
                put(root, rel, text)
            per_module, failing = ci_digest.parse_reports(root)
            task_file = root / "tasks.txt"
            task_file.write_text(tasks, encoding="utf-8")
            expected = ci_digest.modules_expected(task_file)
            return ci_digest.render(log, per_module, failing, expected,
                                    {"run": "7", "branch": "work/s3", "sha": "abc1234", "outcome": outcome})

    def test_green_run(self):
        text = self.digest({"core/common/build/test-results/test/TEST-a.B.xml": PASS_XML},
                           tasks=":core:common:test\n")
        self.assertIn("## Verdict: GREEN - 2 tests ran, 0 failed", text)
        self.assertIn("branch work/s3 - commit abc1234", text)
        self.assertIn("- core/common: 2 / 0 / 1", text)

    def test_failing_test_is_named_with_its_message(self):
        text = self.digest({"capabilities/data/build/test-results/testDebugUnitTest/TEST-c.D.xml": FAIL_XML},
                           outcome="failure")
        self.assertIn("RED", text)
        self.assertIn("- [capabilities/data] D.bad thing - expected:<1> but was:<2>", text)

    def test_kotlin_compile_errors_are_listed_with_short_paths(self):
        text = self.digest({}, log=LOG_COMPILE, outcome="failure")
        self.assertIn("## Kotlin compile errors (first 40 of 2)", text)
        self.assertIn("- app/src/main/kotlin/app/autohost/Foo.kt:12:5 Unresolved reference: bar", text)
        self.assertIn("- :app:compileCorePyDebugKotlin", text)

    def test_a_timeout_shows_the_last_started_tests(self):
        text = self.digest({}, log=LOG_HANG, outcome="failure")
        self.assertIn("A task TIMED OUT", text)
        self.assertIn("- app.autohost.X > second STARTED", text)

    def test_modules_without_results_are_called_out(self):
        text = self.digest({"core/common/build/test-results/test/TEST-a.B.xml": PASS_XML},
                           tasks=":core:common:test\n:runtime:flow:test\n:capabilities:a11y:testDebugUnitTest\n")
        self.assertIn("2 modules with no results", text)
        self.assertIn("- runtime/flow", text)
        self.assertIn("- capabilities/a11y", text)

    def test_digest_is_capped(self):
        big_log = "\n".join(f"e: file:///home/runner/work/R/R/x/F{i}.kt:1:1 boom {i}" for i in range(500))
        failing = {f"m{i}/build/test-results/t/TEST-x{i}.xml": FAIL_XML for i in range(100)}
        text = self.digest(failing, log=big_log, outcome="failure")
        self.assertLessEqual(len(text.splitlines()), ci_digest.MAX_LINES)

    def test_an_empty_run_is_not_reported_green_when_the_step_failed(self):
        text = self.digest({}, outcome="failure")
        self.assertIn("RED", text)

    def test_details_list_every_error_and_the_gradle_reason(self):
        many = "\n".join(f"e: file:///home/runner/work/R/R/x/F{i}.kt:1:1 boom {i}" for i in range(300))
        log = many + "\n> Task :a:test FAILED\n* What went wrong:\nExecution failed for task ':a:test'.\n> Timeout has been exceeded\n* Try:\n> Run with --stacktrace\n"
        details = ci_digest.render_details(log, {}, [("m", "C.t", "msg")])
        self.assertIn("300 distinct Kotlin compile errors", details)
        self.assertIn("x/F299.kt:1:1 boom 299", details)
        self.assertIn("Execution failed for task ':a:test'.", details)
        self.assertNotIn("Run with --stacktrace", details)
        self.assertIn("[m] C.t - msg", details)


if __name__ == "__main__":
    unittest.main()
