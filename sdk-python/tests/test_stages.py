"""P5-01/P5-02: stage records, atomic checkpoints and on_resume policies (plan §12.2/§12.3)."""
from __future__ import annotations

import unittest

from autohost import flow
from autohost._core import Runtime
from autohost._ctx import ctx
from autohost.errors import NeedsUser


class Transport:
    """Records state.set payloads so checkpoint persistence is observable."""

    def __init__(self) -> None:
        self.saved: list[dict] = []
        self.checkpoint: dict = {}

    def call(self, capability: str, method: str, args: dict) -> dict:
        if (capability, method) == ("state", "set"):
            self.saved.append(dict(args["state"]))
            self.checkpoint = dict(args["state"])
            return {"ok": True}
        return {}

    def emit(self, event: dict) -> None:
        pass

    def describe(self) -> dict:
        return {}


def make_runtime(checkpoint: dict | None = None) -> tuple[Runtime, Transport]:
    transport = Transport()
    rt = Runtime(transport, task_id="t", state=checkpoint)
    return rt, transport


class StageRecordTest(unittest.TestCase):
    def setUp(self):
        from autohost import _core
        self.rt, self.transport = make_runtime()
        _core.install(self.rt)

    def tearDown(self):
        from autohost import _core
        _core.uninstall()

    def stage_events(self, kind: str) -> list[dict]:
        return [e for e in self.rt.emitted if e.get("type") == kind]

    def test_completed_stages_never_repeat_on_resume(self):
        calls = []

        @flow.stage("first")
        def first():
            calls.append("first")

        @flow.stage("second")
        def second():
            calls.append("second")

        flow.run_stages()
        flow.run_stages()  # second attempt with the checkpoint restored
        self.assertEqual(calls, ["first", "second"])  # bodies ran once each

    def test_stage_record_shape_is_persisted(self):
        @flow.stage("alpha")
        def alpha():
            pass

        flow.run_stages()
        record = self.rt.state["stage:alpha"]
        self.assertEqual(record["status"], "done")
        self.assertEqual(record["attempts"], 1)
        self.assertIsNotNone(record["started_at"])
        self.assertIsNotNone(record["ended_at"])
        self.assertIsNone(record["error"])
        # the final save carried the record to the host
        self.assertEqual(self.transport.saved[-1]["stage:alpha"]["status"], "done")

    def test_in_progress_marker_is_durable_before_body(self):
        seen = {}

        @flow.stage("marker")
        def marker():
            seen["during"] = dict(self.transport.saved[-1])

        flow.run_stages()
        self.assertEqual(seen["during"]["stage:marker"]["status"], "running")
        self.assertEqual(self.rt.state["stage:marker"]["status"], "done")

    def test_failed_stage_records_error_and_reraises(self):
        @flow.stage("boom")
        def boom():
            raise ValueError("nope")

        with self.assertRaises(ValueError):
            flow.run_stages()
        record = self.rt.state["stage:boom"]
        self.assertEqual(record["status"], "failed")
        self.assertIn("nope", record["error"])
        end = self.stage_events("stage_end")[0]
        self.assertFalse(end["data"]["ok"])

    def test_on_resume_verify_marks_done_without_rerunning(self):
        done = []

        @flow.stage("half", on_resume="verify", verify=lambda: True)
        def half():
            done.append(1)

        flow.run_stages()  # completes normally
        # simulate an interrupted attempt: the checkpoint says the stage was running
        self.rt.state["stage:half"] = {"status": "running", "attempts": 1, "started_at": "x",
                                       "ended_at": None, "error": None}
        flow.run_stages()
        self.assertEqual(done, [1])  # the body did not run again
        record = self.rt.state["stage:half"]
        self.assertEqual(record["status"], "done")
        self.assertEqual(record["attempts"], 2)
        end = self.stage_events("stage_end")[-1]
        self.assertTrue(end["data"]["verified"])

    def test_on_resume_verify_failure_redoes_body(self):
        done = []

        @flow.stage("flaky", on_resume="verify", verify=lambda: False)
        def flaky():
            done.append(1)

        self.rt.state["stage:flaky"] = {"status": "running", "attempts": 1, "started_at": "x",
                                        "ended_at": None, "error": None}
        flow.run_stages()
        self.assertEqual(done, [1])  # honest redo
        self.assertEqual(self.rt.state["stage:flaky"]["attempts"], 2)

    def test_on_resume_manual_raises_needs_user(self):
        @flow.stage("risky", on_resume="manual")
        def risky():
            pass

        self.rt.state["stage:risky"] = {"status": "running", "attempts": 1, "started_at": "x",
                                        "ended_at": None, "error": None}
        with self.assertRaises(NeedsUser) as ctx_exc:
            flow.run_stages()
        self.assertEqual(ctx_exc.exception.reason, "STAGE_UNVERIFIED")

    def test_on_resume_redo_reruns_body(self):
        done = []

        @flow.stage("again", on_resume="redo")
        def again():
            done.append(1)

        self.rt.state["stage:again"] = {"status": "running", "attempts": 3, "started_at": "x",
                                        "ended_at": None, "error": None}
        flow.run_stages()
        self.assertEqual(done, [1])
        self.assertEqual(self.rt.state["stage:again"]["attempts"], 4)

    def test_verify_only_applies_to_interrupted_stages(self):
        done = []

        @flow.stage("fresh", on_resume="verify", verify=lambda: False)
        def fresh():
            done.append(1)

        flow.run_stages()  # first attempt: verify must not run instead of the body
        self.assertEqual(done, [1])


if __name__ == "__main__":
    unittest.main()
