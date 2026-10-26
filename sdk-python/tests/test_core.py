import unittest

import support
from autohost import ctx, log, errors, sel
from autohost._core import uninstall
from autohost_sim import FakeDevice, VirtualClock


class RuntimeBehaviour(unittest.TestCase):
    def setUp(self):
        self.device = support.press_device()

    def tearDown(self):
        uninstall()

    def test_kv_round_trip_and_missing_key(self):
        support.runtime_for(self.device)
        ctx.task_store["n"] = 3
        self.assertEqual(ctx.task_store["n"], 3)
        self.assertEqual(ctx.task_store.get("missing", "dflt"), "dflt")
        with self.assertRaises(KeyError):
            ctx.task_store["missing"]

    def test_profile_and_task_scopes_do_not_mix(self):
        support.runtime_for(self.device)
        ctx.profile_store["k"] = "profile"
        self.assertNotIn("k", ctx.task_store)

    def test_once_runs_the_function_only_once_per_run(self):
        support.runtime_for(self.device)
        calls = []
        self.assertEqual(ctx.once("x", lambda: calls.append(1) or 7), 7)
        self.assertEqual(ctx.once("x", lambda: calls.append(1) or 9), 7)
        self.assertEqual(len(calls), 1)

    def test_secrets_are_redacted_from_logs(self):
        rt = support.runtime_for(self.device, secrets={"password": "hunter2"})
        self.assertEqual(ctx.secret("password"), "hunter2")
        log.info("login with hunter2", pw="hunter2")
        event = rt.emitted[-1]
        self.assertEqual(event["message"], "login with [REDACTED]")
        self.assertEqual(event["data"]["pw"], "[REDACTED]")

    def test_undeclared_secret_is_not_found(self):
        support.runtime_for(self.device)
        with self.assertRaises(errors.NotFound):
            ctx.secret("nope")

    def test_cancel_stops_the_next_sleep(self):
        rt = support.runtime_for(self.device)
        rt.cancelled = True
        with self.assertRaises(errors.Cancelled):
            ctx.sleep(1)

    def test_sleep_advances_only_virtual_time(self):
        support.runtime_for(self.device)
        before = self.device.clock.now()
        ctx.sleep(90)
        self.assertEqual((self.device.clock.now() - before).total_seconds(), 90)

    def test_minutes_since_uses_the_run_clock(self):
        support.runtime_for(self.device)
        then = ctx.now()
        ctx.sleep(600)
        self.assertAlmostEqual(ctx.minutes_since(then), 10.0)

    def test_next_run_needs_exactly_one_argument(self):
        support.runtime_for(self.device)
        with self.assertRaises(errors.InvalidArgs):
            ctx.next_run()
        with self.assertRaises(errors.InvalidArgs):
            ctx.next_run(after_minutes=5, at="2026-10-23T13:00:00Z")
        ctx.next_run(after_minutes=5)

    def test_result_is_recorded(self):
        rt = support.runtime_for(self.device)
        res = ctx.result("ok", {"n": 1})
        self.assertEqual((res.code, res.data, rt.result), ("ok", {"n": 1}, res))

    def test_capabilities_reports_the_transport_view(self):
        support.runtime_for(self.device)
        from autohost import capabilities
        self.assertTrue(capabilities()["device.ui"]["available"])


if __name__ == "__main__":
    unittest.main()
