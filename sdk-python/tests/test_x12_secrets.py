"""X-12 (ADR-099) SDK/simulator half: registered secret values never leave the worker through
a path that records or persists them.

Refused: untracked secret strings in call() args, kv/state writes, checkpoints (ctx.once),
the run result, and the simulator's store/state (the FakeDevice mirrors the broker).
Allowed: a value obtained from ctx.secret() riding call() args — that is the one legitimate
channel (e.g. typing into a field); emit keeps redacting by value.
"""
import unittest

import support
from autohost import ctx, errors
from autohost._log import log
from autohost_sim.device import SimError


class X12SecretBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.device = support.device()
        self.rt = support.runtime_for(self.device, secrets={"password": "hunter2"})

    # -- call() args ---------------------------------------------------------
    def test_call_args_refuse_an_untracked_secret(self):
        ctx.secret("password")  # value is now registered in the run
        with self.assertRaises(errors.InvalidArgs):
            self.rt.call("notify.post", "post", {"title": "login", "body": "pw is hunter2"})

    def test_call_args_allow_a_tracked_secret_value(self):
        pw = ctx.secret("password")
        res = self.rt.call("notify.post", "post", {"title": "login", "body": pw})
        self.assertTrue(isinstance(res, dict))

    def test_nested_call_args_are_scanned(self):
        ctx.secret("password")
        with self.assertRaises(errors.InvalidArgs):
            self.rt.call("notify.post", "post", {"title": "login", "data": {"note": ["x", "hunter2"]}})

    # -- kv writes -----------------------------------------------------------
    def test_kv_write_refuses_a_secret_value(self):
        with self.assertRaises(errors.InvalidArgs):
            ctx.profile_store["pw"] = ctx.secret("password")

    def test_kv_write_refuses_an_untracked_secret_inside_a_plain_string(self):
        with self.assertRaises(errors.InvalidArgs):
            ctx.task_store["note"] = "the pw is hunter2"

    # -- state / checkpoints ---------------------------------------------------
    def test_state_save_refuses_a_secret_in_state(self):
        ctx.state["token"] = ctx.secret("password")
        with self.assertRaises(errors.InvalidArgs):
            ctx.save()

    def test_once_checkpoint_refuses_a_secret_result(self):
        with self.assertRaises(errors.InvalidArgs):
            ctx.once("login", lambda: ctx.secret("password"))

    # -- run result ------------------------------------------------------------
    def test_result_refuses_a_secret_in_data(self):
        with self.assertRaises(errors.InvalidArgs):
            ctx.result("OK", {"pw": "hunter2"})

    # -- emit keeps redacting ----------------------------------------------------
    def test_emit_still_redacts_secret_values(self):
        log.info("login with hunter2", pw="hunter2")
        event = self.rt.emitted[-1]
        self.assertNotIn("hunter2", str(event))
        self.assertIn("[REDACTED]", event["message"])

    def test_secret_value_repr_is_redacted(self):
        self.assertEqual(repr(ctx.secret("password")), "[REDACTED]")

    # -- FakeDevice mirrors the broker (defense in depth) -------------------------
    def test_sim_store_refuses_a_registered_secret_even_without_the_sdk_guard(self):
        with self.assertRaises(SimError) as caught:
            self.device.dispatch("store", "set",
                                 {"scope": "profile", "key": "pw", "value": "hunter2"})
        self.assertEqual(caught.exception.code, "INVALID_ARGS")

    def test_sim_state_refuses_a_registered_secret_even_without_the_sdk_guard(self):
        with self.assertRaises(SimError) as caught:
            self.device.dispatch("state", "set", {"state": {"pw": "hunter2"}})
        self.assertEqual(caught.exception.code, "INVALID_ARGS")

    def test_sim_store_accepts_values_without_registered_secrets(self):
        self.device.dispatch("store", "set", {"scope": "profile", "key": "ok", "value": "safe"})
        self.assertEqual(self.device.dispatch("store", "get",
                                              {"scope": "profile", "key": "ok"})["value"], "safe")


if __name__ == "__main__":
    unittest.main()
