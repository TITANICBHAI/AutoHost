"""P6 tests: the flow golden vectors (testing/golden/flow_vectors.json).

The committed file is the shared contract between the Python reference (autohost_ref/flows.py)
and the Kotlin :runtime:flow interpreter: every case pins the outcome, the exact broker call
log (templated args, normalized wire selectors) and the event sequence. This replay re-runs
every case through the reference over the data-only device model; the Kotlin golden test
rebuilds the same harness from the same file (see tools/gen_flow_vectors.py for the spec).
"""
from __future__ import annotations

import json
import pathlib
import sys
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
for path in (str(REPO / "sdk-python"), str(REPO)):
    if path not in sys.path:
        sys.path.insert(0, path)

from autohost_ref import flows  # noqa: E402
from autohost_ref.flows import BrokerError, Exec  # noqa: E402
from autohost_sim import FakeDevice, SimError, VirtualClock  # noqa: E402


class SimBroker:
    """Broker adapter over the FakeDevice: records interpreter-visible calls, can fail the
    first N calls of one (capability, method) kind. Mirrors tools/gen_flow_vectors.py."""

    def __init__(self, device: FakeDevice, fail_first: dict | None = None) -> None:
        self.device = device
        self.calls: list[tuple] = []
        self.fail_first = {tuple(k.rsplit(".", 1)): list(v) for k, v in (fail_first or {}).items()}

    def call(self, capability: str, method: str, args: dict) -> dict:
        self.calls.append((capability, method, dict(args)))
        queued = self.fail_first.get((capability, method))
        if queued:
            spec = queued.pop(0)
            raise BrokerError(spec["code"], spec.get("message", ""),
                              retryable=bool(spec.get("retryable")))
        try:
            out = self.device.dispatch(capability, method, dict(args))
        except SimError as exc:
            raise BrokerError(exc.code, exc.message, retryable=bool(exc.retryable)) from None
        return out if isinstance(out, dict) else {"value": out}


class FlowVectorContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.doc = json.loads((REPO / "testing" / "golden" / "flow_vectors.json").read_text(encoding="utf-8"))
        cls.cases = cls.doc["cases"]

    def test_file_holds_the_documented_contract(self):
        self.assertGreaterEqual(len(self.cases), 44, "the vector file lost cases")
        kinds = {case["kind"] for case in self.cases}
        self.assertEqual(kinds, {"run", "parse_error"})
        names = [case["name"] for case in self.cases]
        self.assertEqual(len(names), len(set(names)), "duplicate case names")

    def test_every_run_case_replays_identically(self):
        for case in self.cases:
            if case["kind"] != "run":
                continue
            with self.subTest(case=case["name"]):
                self._replay_run(case)

    def test_every_parse_error_case_still_fails_with_the_same_message(self):
        for case in self.cases:
            if case["kind"] != "parse_error":
                continue
            with self.subTest(case=case["name"]):
                with self.assertRaises(flows.FlowError) as ctx:
                    flows.parse_flow(case["flow"])
                self.assertEqual(str(ctx.exception), case["expected"]["error"])

    def _replay_run(self, case: dict) -> None:
        device = case.get("device", {})
        dev = FakeDevice(
            device.get("screens", {}),
            launch_map=device.get("launch_map", {}),
            start=device.get("start"),
            clock=VirtualClock(),
            prompt_answers=device.get("prompt_answers", {}),
            screenshot=device.get("screenshot", True),
            installed=device.get("installed"),
        )
        broker = SimBroker(dev, device.get("fail_first"))
        events: list[dict] = []
        ex = Exec(broker, emit=events.append, check=lambda: None,
                  sleep=lambda seconds: dev.clock.sleep(seconds),
                  api_level=case.get("api_level", 29), subflows=case.get("subflows"))
        outcome = flows.run_flow(
            case["flow"], ex,
            config=case.get("config", {}), profile=case.get("profile", {}),
            env=case.get("env", {}), secrets=case.get("secrets", {}),
            run=case.get("run", {"attempt": 1}),
        )
        expected = case["expected"]
        self.assertEqual(outcome, expected["outcome"], "outcome drifted")
        self.assertEqual([[cap, method, args] for cap, method, args in broker.calls],
                         expected["calls"], "broker call log drifted")
        self.assertEqual(events, expected["events"], "event sequence drifted")


if __name__ == "__main__":
    unittest.main()
