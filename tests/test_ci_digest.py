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

    def test_the_digest_shows_where_the_time_went_for_finished_tasks(self):
        # INT-T3: the journey-test task grew to the edge of its 8 minute limit; the digest now names the slow tests
        timed = """<?xml version="1.0"?>
<testsuite name="a.Slow" tests="3" skipped="0" failures="0" errors="0">
  <testcase name="quick" classname="a.Slow" time="0.2"/>
  <testcase name="walks every screen" classname="a.Slow" time="41.5"/>
  <testcase name="no time attribute" classname="a.Slow"/>
</testsuite>"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            put(root, "app/build/test-results/testCoreLiteDebugUnitTest/TEST-a.Slow.xml", timed)
            per_module, failing = ci_digest.parse_reports(root)
            text = ci_digest.render("", per_module, failing, [], {"run": "9", "branch": "b", "sha": "c", "outcome": "success"},
                                    slow=ci_digest.parse_times(root))
        self.assertIn("## Where the time went", text)
        self.assertIn("- app: 42 s of test time", text)
        self.assertIn("- slowest: 41.5 s Slow.walks every screen [app]", text)
        self.assertLess(text.index("slowest: 41.5"), text.index("slowest: 0.2"))

    def test_no_timing_section_when_no_report_was_written(self):
        # a task stopped by the timeout writes no XML: the digest must not invent a timing section
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = ci_digest.render("", {}, [], [], {"run": "9", "branch": "b", "sha": "c", "outcome": "failure"},
                                    slow=ci_digest.parse_times(root))
        self.assertNotIn("Where the time went", text)

    # ---- INT-T4: where a hung test JVM was stuck ----------------------------------------------------------------------

    @staticmethod
    def dump(deadlock: bool = False, cpu: str = "51300.9", first_frame: str = "jdk.internal.misc.Unsafe.park(java.base@17.0.13/Native Method)") -> str:
        filler = "".join(f"\tat java.base.Filler.m{i}(Filler.java:{i})\n" for i in range(20))
        text = (
            "Full thread dump OpenJDK 64-Bit Server VM (17.0.13+11 mixed mode, sharing):\n\n"
            # run 50's real dump: the 'Test worker' thread only waits for Robolectric's main thread
            '"Test worker" #1 prio=5 os_prio=0 cpu=971.33ms elapsed=423.98s tid=0x00007f nid=0x1a2b waiting on condition  [0x00007f]\n'
            "   java.lang.Thread.State: WAITING (parking)\n"
            "\tat jdk.internal.misc.Unsafe.park(java.base@17.0.13/Native Method)\n"
            "\tat org.robolectric.internal.bytecode.Sandbox.runOnMainThread(Sandbox.java:130)\n\n"
            # the thread that runs the test body
            f'"SDK 35" #31 prio=5 os_prio=0 cpu={cpu}ms elapsed=420.0s tid=0x00007e nid=0x1a3c waiting on condition  [0x00007e]\n'
            "   java.lang.Thread.State: WAITING (parking)\n"
            f"\tat {first_frame}\n"
            f"{filler}"
            "\tat app.autohost.SettingsWalkJourneyTest.waitForText(SettingsWalkJourneyTest.kt:52)\n\n"
            # another thread inside our code
            '"DefaultDispatcher-worker-2" #40 daemon prio=5 os_prio=0 cpu=12.0ms elapsed=100.0s tid=0x00007d nid=0x1a4d runnable  [0x00007d]\n'
            "   java.lang.Thread.State: RUNNABLE\n"
            "\tat java.net.SocketInputStream.socketRead0(java.base@17.0.13/Native Method)\n"
            "\tat app.autohost.shell.Fake.read(Fake.kt:9)\n\n"
            '"Reference Handler" #2 daemon prio=10 os_prio=0\n'
            "   java.lang.Thread.State: RUNNABLE\n"
            "\tat java.lang.ref.Reference.waitForReferencePendingList(java.base@17.0.13/Native Method)\n\n"
        )
        if deadlock:
            text += (
                "Found one Java-level deadlock:\n=============================\n"
                '"A":\n  waiting to lock monitor 0x1 (object 0x2, a java.lang.Object),\n  which is held by "B"\n\n'
            )
        text += (
            " garbage-first heap   total 2097152K, used 2051000K [0x0000000080000000, 0x0000000100000000)\n"
            "  region size 1024K, 4 young (4096K), 1 survivors (1024K)\n"
        )
        return text

    def timed_out_digest(self, threads_dir: Path) -> str:
        stuck = ci_digest.stuck_stacks(threads_dir)
        return ci_digest.render(LOG_HANG, {}, [], [], {"run": "9", "branch": "b", "sha": "c", "outcome": "failure"}, stuck=stuck)

    def test_a_timeout_shows_where_the_test_thread_was_stuck(self):
        with tempfile.TemporaryDirectory() as tmp:
            put(Path(tmp), "threads/4242.txt", self.dump())
            text = self.timed_out_digest(Path(tmp) / "threads")
        self.assertIn("## Where the test JVM was stuck", text)
        self.assertIn("test JVM 4242: 4 threads", text)
        self.assertIn('"Test worker" #1', text)  # the thread that only waits
        self.assertIn('"SDK 35" #31', text)  # the thread that runs the test body
        self.assertIn("Unsafe.park", text)  # the call it is blocked in
        self.assertIn("deeper down, frames of ours:", text)
        self.assertIn("SettingsWalkJourneyTest.waitForText(SettingsWalkJourneyTest.kt:52)", text)  # the test step
        self.assertNotIn("Filler.m19", text)  # frames in between are cut
        self.assertIn("total 2097152K, used 2051000K", text)  # a full heap looks like a hang
        self.assertNotIn("Reference Handler", text)  # JVM housekeeping is left out
        self.assertIn("app.autohost.shell.Fake.read(Fake.kt:9)", text)  # another thread inside our code
        self.assertLess(text.index('"SDK 35"'), text.index("DefaultDispatcher-worker-2"))  # the test body first
        self.assertIn("(no earlier dump of this thread)", text)

    def test_a_stuck_thread_is_compared_with_the_dump_before_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            put(Path(tmp), "threads/5.prev", self.dump(cpu="51000.1"))
            put(Path(tmp), "threads/5.txt", self.dump(cpu="51300.9"))
            moving = self.timed_out_digest(Path(tmp) / "threads")
            sleeping = "java.lang.Thread.sleep(java.base@17.0.13/Native Method)"
            put(Path(tmp), "threads/5.prev", self.dump(cpu="51000.1", first_frame=sleeping))
            changed = self.timed_out_digest(Path(tmp) / "threads")
        self.assertIn("(about a minute earlier: cpu 51000 -> 51301 ms, same top frames)", moving)  # a busy loop burns cpu
        self.assertIn("(about a minute earlier: cpu 51000 -> 51301 ms, different top frames)", changed)
        self.assertNotIn("test JVM 5.prev", moving)  # the older dump is only a comparison, never a JVM of its own

    def test_a_deadlock_the_jvm_found_is_quoted(self):
        with tempfile.TemporaryDirectory() as tmp:
            put(Path(tmp), "threads/7.txt", self.dump(deadlock=True))
            text = self.timed_out_digest(Path(tmp) / "threads")
        self.assertIn("JAVA-LEVEL DEADLOCK:", text)
        self.assertIn('which is held by "B"', text)

    def test_dumps_of_jvms_that_had_already_finished_are_left_out(self):
        import os
        import time
        with tempfile.TemporaryDirectory() as tmp:
            old = put_and_path(Path(tmp), "threads/1.txt", self.dump())
            put(Path(tmp), "threads/2.txt", self.dump())
            long_ago = time.time() - 1000
            os.utime(old, (long_ago, long_ago))
            text = self.timed_out_digest(Path(tmp) / "threads")
        self.assertIn("test JVM 2:", text)
        self.assertNotIn("test JVM 1:", text)

    def test_no_dump_folder_or_an_empty_one_adds_nothing_and_a_run_without_a_timeout_never_shows_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "threads").mkdir()
            self.assertEqual([], ci_digest.stuck_stacks(Path(tmp) / "threads"))
            self.assertEqual([], ci_digest.stuck_stacks(Path(tmp) / "missing"))
            self.assertEqual([], ci_digest.stuck_stacks(None))
            put(Path(tmp), "threads/5.txt", self.dump())
            stuck = ci_digest.stuck_stacks(Path(tmp) / "threads")
        green = ci_digest.render("", {}, [], [], {"run": "9", "branch": "b", "sha": "c", "outcome": "success"}, stuck=stuck)
        self.assertNotIn("Where the test JVM was stuck", green)

    def test_the_stuck_section_is_capped(self):
        frames = "".join(f"\tat app.autohost.Deep.m{j}(Deep.kt:{j})\n" for j in range(300))
        many = "".join(f'"SDK 35 copy {i}" #{i}\n   java.lang.Thread.State: RUNNABLE\n{frames}\n' for i in range(8))
        with tempfile.TemporaryDirectory() as tmp:
            put(Path(tmp), "threads/3.txt", many)
            stuck = ci_digest.stuck_stacks(Path(tmp) / "threads")
        self.assertLessEqual(len(stuck), ci_digest.MAX_STUCK_LINES)
        self.assertEqual(ci_digest.MAX_STUCK_THREADS, len([ln for ln in stuck if ln.startswith('"SDK 35 copy')]))


def put_and_path(root: Path, rel: str, text: str) -> Path:
    put(root, rel, text)
    return root / rel


if __name__ == "__main__":
    unittest.main()
