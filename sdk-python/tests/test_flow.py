import unittest

import support
from autohost import ctx, errors, flow, sel
from autohost._core import uninstall
from autohost.flow import Screen, UNKNOWN, classify_snapshot, retry, transition


HOME_SNAP = {"package": "com.example.app", "nodes": [{"text": "Example app"}, {"text": "Continue", "id": "fx_btn_continue"}]}


class Classification(unittest.TestCase):
    def test_unique_match_is_returned(self):
        screens = [Screen("home", any_text=["Continue"], package="com.example.app"),
                   Screen("done", all_text=["Done"])]
        self.assertEqual(classify_snapshot(screens, HOME_SNAP), "home")

    def test_no_match_is_unknown(self):
        self.assertEqual(classify_snapshot([Screen("done", all_text=["Done"])], HOME_SNAP), UNKNOWN)

    def test_ambiguous_top_priority_is_unknown(self):
        screens = [Screen("a", any_text=["Continue"]), Screen("b", any_text=["Example"])]
        self.assertEqual(classify_snapshot(screens, HOME_SNAP), UNKNOWN)

    def test_priority_breaks_ties(self):
        screens = [Screen("a", any_text=["Continue"], priority=0), Screen("b", any_text=["Example"], priority=5)]
        self.assertEqual(classify_snapshot(screens, HOME_SNAP), "b")

    def test_none_text_excludes(self):
        self.assertEqual(classify_snapshot([Screen("h", any_text=["Continue"], none_text=["Example"])], HOME_SNAP), UNKNOWN)


class Transitions(unittest.TestCase):
    def setUp(self):
        self.device = support.press_device()

    def tearDown(self):
        uninstall()

    def test_expected_screen_is_reached(self):
        support.runtime_for(self.device)
        from autohost import device
        device.app.launch("com.example.app")
        node = device.ui.wait_for(sel.text("Continue"), timeout_ms=1000)
        transition(lambda: device.ui.click(node), Screen("done", all_text=["Done"]), timeout_ms=2000)
        self.assertEqual(self.device.current, "done")

    def test_unexpected_state_fails_closed_with_evidence(self):
        rt = support.runtime_for(self.device)
        from autohost import device
        device.app.launch("com.example.app")
        with self.assertRaises(errors.Timeout) as cm:
            transition(lambda: None, Screen("never", all_text=["Nope"]), timeout_ms=1000)
        self.assertEqual(cm.exception.reason, "UNKNOWN_SCREEN")
        self.assertTrue(any(e.get("data", {}).get("kind") == "ui_dump" for e in rt.emitted))


class Retries(unittest.TestCase):
    def setUp(self):
        self.device = support.press_device()
        support.runtime_for(self.device)

    def tearDown(self):
        uninstall()

    def test_retryable_errors_are_retried_until_success(self):
        attempts = []

        @retry(times=2, backoff_ms=10)
        def flaky():
            attempts.append(1)
            if len(attempts) < 3:
                raise errors.Timeout("slow")
            return "done"

        self.assertEqual(flaky(), "done")
        self.assertEqual(len(attempts), 3)

    def test_non_retryable_errors_fail_immediately(self):
        attempts = []

        @retry(times=5, backoff_ms=10)
        def bad():
            attempts.append(1)
            raise errors.InvalidArgs("bad input")

        with self.assertRaises(errors.InvalidArgs):
            bad()
        self.assertEqual(len(attempts), 1)


class RetryPolicyTests(unittest.TestCase):
    """P3-19: the declarative RetryPolicy form (factor backoff, give-up exceptions)."""

    def setUp(self):
        self.device = support.press_device()
        self.runtime = support.runtime_for(self.device)

    def tearDown(self):
        uninstall()

    def test_policy_factor_backoff_grows(self):
        import autohost.flow as flow

        policy = flow.RetryPolicy(times=3, backoff_ms=100, backoff_factor=2.0)
        calls = {"n": 0}

        @retry(policy=policy)
        def flaky():
            calls["n"] += 1
            if calls["n"] < 3:
                raise errors.Timeout("slow")
            return "ok"

        self.assertEqual(flaky(), "ok")
        self.assertEqual(calls["n"], 3)
        # backoff 100ms then 200ms: the two retries slept 0.3s on the sim clock (factor backoff works)
        self.assertAlmostEqual(self.device.clock.slept, 0.3, places=3)

    def test_policy_give_up_on_stops_immediately(self):
        import autohost.flow as flow

        class Fatal(errors.AutoHostError):
            retryable = True

        calls = []

        @retry(policy=flow.RetryPolicy(times=5, give_up_on=(Fatal,)))
        def doomed():
            calls.append(1)
            raise Fatal("no")

        with self.assertRaises(Fatal):
            doomed()
        self.assertEqual(len(calls), 1)

    def test_plain_form_still_works(self):
        @retry(times=1, backoff_ms=5)
        def once():
            raise errors.Timeout("no retry budget")

        with self.assertRaises(errors.Timeout):
            once()


if __name__ == "__main__":
    unittest.main()
