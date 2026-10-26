"""P6 tests: the flow reference interpreter (autohost_ref.flows) — semantics.

Hand-computed assertions pin the documented semantics (see the module docstring of
autohost_ref/flows.py); the committed golden file (testing/golden/flow_vectors.json) pins the
shared contract with the Kotlin :runtime:flow interpreter (see test_flow_vectors.py).
"""
from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))

from autohost_ref import flows  # noqa: E402
from autohost_ref.flows import (  # noqa: E402
    BrokerError,
    Cancelled,
    Exec,
    ExprError,
    FlowError,
    Scope,
    StepFailure,
    eval_expr,
    parse_flow,
    run_flow,
    selector_from_flow,
)

PRESS_FLOW = {
    "schema": 1,
    "name": "Press button",
    "steps": [
        {"launchApp": {"package": "com.example.app"}},
        {"waitUntil": {"visible": {"text": ["Continue"]}, "timeout": 5}},
        {"tapOn": {"text": ["Continue"]}},
        {"setResult": {"code": "pressed"}},
    ],
}

WIRE_CONTINUE = {"op": "and", "terms": [
    {"text": {"labels": ["Continue"], "mode": "eq", "case_sensitive": False}},
]}


class StubBroker:
    """Minimal broker stub: routes (capability, method) to handlers and records every call."""

    def __init__(self, handlers: dict | None = None, fail_first: dict | None = None) -> None:
        self.calls: list[tuple] = []
        self.handlers = dict(handlers or {})
        self.fail_first = {k: list(v) for k, v in (fail_first or {}).items()}
        self.sleeps: list[float] = []

    def call(self, capability: str, method: str, args: dict) -> dict:
        self.calls.append((capability, method, args))
        queued = self.fail_first.get((capability, method))
        if queued:
            raise queued.pop(0)
        handler = self.handlers.get((capability, method))
        return handler(args) if handler else {}

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)


class Runner:
    """Exec + broker + event recorder wired together for one test run."""

    def __init__(self, broker: StubBroker | None = None, *, api_level: int = 29,
                 subflows: dict | None = None, cancel_after: int | None = None) -> None:
        self.broker = broker or StubBroker()
        self.events: list[dict] = []
        self.checks = 0
        self.cancel_after = cancel_after
        self.ex = Exec(
            self.broker, emit=self.events.append, check=self._check,
            sleep=self.broker.sleep, api_level=api_level, subflows=subflows,
        )

    def _check(self) -> None:
        self.checks += 1
        if self.cancel_after is not None and self.checks > self.cancel_after:
            raise Cancelled("run was cancelled")

    def run(self, flow: dict, **kw) -> dict:
        return run_flow(flow, self.ex, **kw)

    def calls(self, capability: str, method: str) -> list[dict]:
        return [args for cap, method_, args in self.broker.calls
                if cap == capability and method_ == method]

    def by_type(self, event_type: str) -> list[dict]:
        return [e for e in self.events if e["type"] == event_type]


# ---------------------------------------------------------------------------
# parsing
# ---------------------------------------------------------------------------
class ParseTests(unittest.TestCase):
    def test_minimal_flow_parses_and_normalises(self):
        flow = parse_flow({"schema": 1, "name": "F", "tags": ["a"],
                           "env": {"x": 1},
                           "steps": [{"log": {"message": "hi"}}]})
        self.assertEqual(flow["schema"], 1)
        self.assertEqual(flow["name"], "F")
        self.assertEqual(flow["tags"], ["a"])
        self.assertEqual(flow["env"], {"x": 1})
        self.assertEqual(flow["steps"][0]["command"], "log")

    def test_missing_steps_fails(self):
        with self.assertRaisesRegex(FlowError, "steps"):
            parse_flow({"schema": 1})

    def test_empty_steps_fails(self):
        with self.assertRaisesRegex(FlowError, "non-empty"):
            parse_flow({"schema": 1, "steps": []})

    def test_two_commands_in_one_step_fails(self):
        with self.assertRaisesRegex(FlowError, "exactly one command key"):
            parse_flow({"schema": 1, "steps": [
                {"log": {"message": "a"}, "setResult": {"code": "b"}}]})

    def test_unknown_command_fails(self):
        with self.assertRaisesRegex(FlowError, "unknown command"):
            parse_flow({"schema": 1, "steps": [{"frobnicate": {}}]})

    def test_unknown_body_key_fails(self):
        with self.assertRaisesRegex(FlowError, "unknown key"):
            parse_flow({"schema": 1, "steps": [
                {"launchApp": {"package": "p", "nope": 1}}]})

    def test_selector_command_without_selector_fails(self):
        with self.assertRaisesRegex(FlowError, "at least one selector key"):
            parse_flow({"schema": 1, "steps": [{"tapOn": {"optional": True}}]})

    def test_wait_until_needs_exactly_one_side(self):
        base = {"schema": 1, "steps": [{"waitUntil": None}]}
        bad_both = dict(base, steps=[{"waitUntil": {
            "visible": {"text": "a"}, "notVisible": {"text": "b"}}}])
        bad_neither = dict(base, steps=[{"waitUntil": {"timeout": 1}}])
        for doc in (bad_both, bad_neither):
            with self.assertRaisesRegex(FlowError, "exactly one of visible / notVisible"):
                parse_flow(doc)

    def test_unknown_press_key_fails(self):
        with self.assertRaisesRegex(FlowError, "key must be one of"):
            parse_flow({"schema": 1, "steps": [{"pressKey": {"key": "channel_up"}}]})

    def test_bad_timeout_fails(self):
        with self.assertRaisesRegex(FlowError, "timeout"):
            parse_flow({"schema": 1, "steps": [{"waitFor": {"text": "a", "timeout": -1}}]})


# ---------------------------------------------------------------------------
# selector normalization (flow form -> broker wire form)
# ---------------------------------------------------------------------------
class SelectorTests(unittest.TestCase):
    def test_string_label_becomes_eq_match(self):
        wire = selector_from_flow({"text": "Continue"})
        self.assertEqual(wire, WIRE_CONTINUE)

    def test_list_labels_become_one_match(self):
        wire = selector_from_flow({"id": ["a", "b"]})
        self.assertEqual(wire["terms"], [
            {"id": {"labels": ["a", "b"], "mode": "eq", "case_sensitive": False}}])

    def test_object_form_overrides_mode(self):
        wire = selector_from_flow({"text": {"value": "^Done", "mode": "regex"}})
        self.assertEqual(wire["terms"], [
            {"text": {"labels": ["^Done"], "mode": "regex", "case_sensitive": False}}])

    def test_package_key_maps_to_pkg_term(self):
        wire = selector_from_flow({"package": "com.example"})
        self.assertEqual(list(wire["terms"][0].keys()), ["pkg"])

    def test_state_keys_fold_into_single_state_term(self):
        wire = selector_from_flow({"clickable": True, "enabled": False})
        self.assertEqual(wire["terms"], [{"state": {"clickable": True, "enabled": False}}])

    def test_relations_map_to_wire_kinds(self):
        wire = selector_from_flow({"text": "Save", "below": {"id": "title"},
                                   "containsDescendants": [{"text": "x"}]})
        kinds = [next(iter(t)) for t in wire["terms"]]
        self.assertEqual(kinds, ["text", "relation", "relation"])
        self.assertEqual(wire["terms"][1]["relation"]["kind"], "below")
        self.assertEqual(wire["terms"][2]["relation"]["kind"], "contains_descendants")

    def test_index_folds_remaining_criteria(self):
        wire = selector_from_flow({"class": "android.widget.Button", "index": 1})
        self.assertEqual(len(wire["terms"]), 1)
        relation = wire["terms"][0]["relation"]
        self.assertEqual(relation["kind"], "index")
        self.assertEqual(relation["n"], 1)
        self.assertEqual(relation["selector"], {"op": "and", "terms": [
            {"class": {"labels": ["android.widget.Button"], "mode": "eq", "case_sensitive": False}}]})

    def test_unknown_selector_key_fails(self):
        with self.assertRaisesRegex(FlowError, "unknown selector key"):
            selector_from_flow({"xpath": "//foo"})


# ---------------------------------------------------------------------------
# templating
# ---------------------------------------------------------------------------
class TemplateTests(unittest.TestCase):
    def scope(self, **kw) -> Scope:
        return Scope(kw.get("config", {}), kw.get("profile", {}),
                     kw.get("env", {}), kw.get("run", {"attempt": 1}), kw.get("secrets", {}))

    def test_whole_string_placeholder_keeps_type(self):
        sc = self.scope(config={"n": 5, "flag": True})
        self.assertEqual(flows._resolve_string("${config.n}", sc, False), 5)
        self.assertEqual(flows._resolve_string("${config.flag}", sc, False), True)

    def test_mixed_string_interpolates_scalars(self):
        sc = self.scope(config={"n": 5}, env={"name": "x"})
        self.assertEqual(flows._resolve_string("n=${config.n} name=${env.name} none=${profile.m}", sc, False),
                         "n=5 name=x none=${profile.m}")

    def test_nested_config_path_resolves(self):
        sc = self.scope(config={"a": {"b": {"c": "deep"}}})
        self.assertEqual(flows._resolve_string("${config.a.b.c}", sc, False), "deep")

    def test_bare_name_prefers_env(self):
        sc = self.scope(config={"x": "config"}, env={"x": "env"})
        self.assertEqual(flows._resolve_string("${x}", sc, False), "env")

    def test_unresolved_placeholder_stays_literal(self):
        sc = self.scope(config={})
        self.assertEqual(flows._resolve_string("v=${config.missing}", sc, False),
                         "v=${config.missing}")

    def test_secret_stays_literal_outside_actions(self):
        sc = self.scope(secrets={"pw": "s3cr3t"})
        self.assertEqual(flows._resolve_string("pw=${secret.pw}", sc, False), "pw=${secret.pw}")
        self.assertEqual(flows._resolve_string("pw=${secret.pw}", sc, True), "pw=s3cr3t")

    def test_missing_secret_fails_typed(self):
        runner = Runner()
        outcome = runner.run({"schema": 1, "steps": [
            {"inputText": {"into": {"id": "field"}, "text": "${secret.nope}", "secret": True}}]},
            secrets={})
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "NOT_FOUND")


# ---------------------------------------------------------------------------
# expression language
# ---------------------------------------------------------------------------
class ExprTests(unittest.TestCase):
    UP = Scope({}, {}, {"n": "3", "flag": True}, {"attempt": 2}, {"s": "x"})

    def test_comparisons_and_logic(self):
        self.assertTrue(eval_expr("1 < 2 && 'a' == 'a'", self.UP))
        self.assertTrue(eval_expr("!(1 > 2) || false", self.UP))
        self.assertTrue(eval_expr("2 >= 2", self.UP))
        self.assertTrue(eval_expr("1 != 2", self.UP))

    def test_precedence_and_parentheses(self):
        self.assertTrue(eval_expr("1 + 2 == 3", self.UP))
        self.assertTrue(eval_expr("(1 + 2) == 3", self.UP))
        self.assertFalse(eval_expr("1 + 1 == 3", self.UP))

    def test_string_concat_and_number_math(self):
        self.assertEqual(eval_expr("'a' + 'b' + 'c'", self.UP), "abc")
        self.assertEqual(eval_expr("4 - 1 + 0", self.UP), 3)
        self.assertEqual(eval_expr("env.n + 'x'", self.UP), "3x")

    def test_mixed_plus_is_evaluation_error(self):
        with self.assertRaises(ExprError):
            eval_expr("'a' + 1", self.UP)

    def test_unknown_identifier_is_evaluation_error(self):
        with self.assertRaisesRegex(ExprError, "unknown variable"):
            eval_expr("nope", self.UP)
        with self.assertRaisesRegex(ExprError, "unknown variable"):
            eval_expr("config.nope", self.UP)

    def test_cross_type_equality_is_false(self):
        self.assertFalse(eval_expr("1 == '1'", self.UP))
        self.assertFalse(eval_expr("true == 1", self.UP))
        self.assertTrue(eval_expr("1 != '1'", self.UP))

    def test_secrets_are_refused_in_expressions(self):
        with self.assertRaisesRegex(ExprError, "secrets"):
            eval_expr("secret.s == 'x'", self.UP)


# ---------------------------------------------------------------------------
# run semantics
# ---------------------------------------------------------------------------
class RunTests(unittest.TestCase):
    def broker_for_press_flow(self) -> StubBroker:
        return StubBroker(handlers={
            ("device.app", "launch"): lambda a: {"result": "LAUNCHED_VERIFIED", "strategy": "sim"},
            ("device.ui", "wait_for"): lambda a: {"node": {"text": "Continue"}},
            ("device.ui", "click"): lambda a: {"method": "NODE_ACTION", "verified": True},
        })

    def test_happy_path_press_button_flow(self):
        runner = Runner(self.broker_for_press_flow())
        outcome = runner.run(PRESS_FLOW, config={"target_package": "com.example.app"})
        self.assertEqual(outcome, {"status": "SUCCEEDED",
                                   "result": {"code": "pressed", "data": {}}})
        self.assertEqual(runner.broker.calls, [
            ("device.app", "launch", {"package": "com.example.app"}),
            ("device.ui", "wait_for", {"selector": WIRE_CONTINUE, "timeout_ms": 5000}),
            ("device.ui", "click", {"target": {"selector": WIRE_CONTINUE}, "timeout_ms": 10000}),
        ])

    def test_failed_tap_is_typed_failure_with_evidence(self):
        broker = self.broker_for_press_flow()
        broker.handlers[("device.ui", "click")] = lambda a: {"method": "COORDINATE",
                                                             "verified": False}
        runner = Runner(broker)
        outcome = runner.run(PRESS_FLOW)
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "TIMEOUT")
        evidence = runner.by_type("evidence")
        self.assertEqual(len(evidence), 1)
        data = evidence[0]["data"]
        self.assertEqual(data["code"], "TIMEOUT")
        self.assertTrue(data["retryable"])
        self.assertEqual(data["selector"], WIRE_CONTINUE)

    def test_optional_step_swallows_failure(self):
        broker = self.broker_for_press_flow()
        broker.handlers[("device.ui", "click")] = lambda a: {"method": "COORDINATE",
                                                             "verified": False}
        flow = {"schema": 1, "name": "Press button", "steps": [
            {"launchApp": {"package": "com.example.app"}},
            {"waitUntil": {"visible": {"text": ["Continue"]}, "timeout": 5}},
            {"tapOn": {"text": ["Continue"], "optional": True}},
            {"setResult": {"code": "pressed"}},
        ]}
        runner = Runner(broker)
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(len(runner.by_type("evidence")), 1)
        warns = [e for e in runner.events if e["type"] == "log" and e["level"] == "warn"]
        self.assertEqual(len(warns), 1)

    def test_optional_does_not_swallow_cancellation(self):
        runner = Runner(cancel_after=0)
        flow = {"schema": 1, "steps": [{"launchApp": {"package": "p", "optional": True}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome, {"status": "CANCELLED", "reason": "USER_CANCELLED"})

    def test_when_visible_gates_step(self):
        broker = StubBroker(handlers={("device.ui", "find"): lambda a: {"nodes": []}})
        runner = Runner(broker)
        flow = {"schema": 1, "steps": [
            {"tapOn": {"text": "Continue", "when": {"visible": {"id": "banner"}}}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(runner.calls("device.ui", "click"), [])

    def test_when_true_gates_step(self):
        broker = StubBroker()
        runner = Runner(broker)
        flow = {"schema": 1, "steps": [
            {"log": {"message": "ran", "when": {"true": "env.go == 'yes'"}}}]}
        runner.run(flow, env={"go": "no"})
        self.assertEqual([e for e in runner.events if e["type"] == "log"], [])
        runner.run(flow, env={"go": "yes"})
        self.assertEqual(runner.events[-1]["message"], "ran")

    def test_when_min_api_gates_step(self):
        runner = Runner(api_level=29)
        flow = {"schema": 1, "steps": [
            {"log": {"message": "new", "when": {"minApi": 30}}},
            {"log": {"message": "old", "when": {"minApi": 29}}}]}
        runner.run(flow)
        messages = [e["message"] for e in runner.events]
        self.assertEqual(messages, ["old"])

    def test_retry_retries_retryable_failure(self):
        broker = StubBroker(
            handlers={("device.app", "launch"): lambda a: {}},
            fail_first={("device.app", "launch"): [BrokerError("TIMEOUT", "slow", retryable=True)]},
        )
        runner = Runner(broker)
        flow = {"schema": 1, "steps": [
            {"retry": {"times": 2, "steps": [{"launchApp": {"package": "p"}}]}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(len(broker.calls), 2)
        self.assertEqual(runner.broker.sleeps, [0.5])

    def test_retry_never_retries_terminal_failure(self):
        broker = StubBroker(
            fail_first={("device.app", "launch"): [BrokerError("NOT_FOUND", "gone")]},
        )
        runner = Runner(broker)
        flow = {"schema": 1, "steps": [
            {"retry": {"times": 3, "steps": [{"launchApp": {"package": "p"}}]}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "NOT_FOUND")
        self.assertEqual(len(broker.calls), 1)
        self.assertEqual(runner.broker.sleeps, [])

    def test_retry_exhaustion_fails_with_last_code(self):
        broker = StubBroker(fail_first={("device.app", "launch"): [
            BrokerError("TIMEOUT", "slow", retryable=True),
            BrokerError("TIMEOUT", "slow again", retryable=True),
        ]})
        runner = Runner(broker)
        flow = {"schema": 1, "steps": [
            {"retry": {"times": 1, "steps": [{"launchApp": {"package": "p"}}]}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "TIMEOUT")
        self.assertEqual(len(broker.calls), 2)

    def test_repeat_times_runs_exactly_n_times(self):
        runner = Runner()
        flow = {"schema": 1, "steps": [
            {"repeat": {"times": 3, "steps": [{"log": {"message": "tick"}}]}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        logs = [e for e in runner.events if e["type"] == "log"]
        self.assertEqual(len(logs), 3)

    def test_repeat_while_reevaluates_each_iteration(self):
        counter = {"finds": 0}

        def find(args: dict) -> dict:
            counter["finds"] += 1
            return {"nodes": [{"text": str(counter["finds"])}]}

        runner = Runner(StubBroker(handlers={("device.ui", "find"): find}))
        flow = {"schema": 1, "env": {"n": "0"}, "steps": [
            {"repeat": {"while": "env.n != '3'", "steps": [
                {"copyTextFrom": {"text": {"mode": "regex", "value": "."}, "into": "n"}}]}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(counter["finds"], 3)

    def test_run_flow_child_env_is_isolated(self):
        child = {"schema": 1, "steps": [
            {"defineVariables": {"z": "9"}},
            {"log": {"message": "${env.x}-${env.y}"}},
        ]}
        runner = Runner(subflows={"child": child})
        flow = {"schema": 1, "steps": [
            {"defineVariables": {"x": "1"}},
            {"runFlow": {"file": "child", "env": {"y": "2"}}},
            {"log": {"message": "${env.z}"}},
        ]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        logs = [e["message"] for e in runner.events if e["type"] == "log"]
        self.assertEqual(logs, ["1-2", "${env.z}"])

    def test_on_flow_start_failure_prevents_body(self):
        broker = self.broker_for_press_flow()
        broker.handlers[("device.ui", "find")] = lambda a: {"nodes": []}
        runner = Runner(broker)
        flow = dict(PRESS_FLOW, onFlowStart=[{"assertVisible": {"id": "gate"}}])
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "FAILED")
        self.assertEqual(outcome["reason"], "NOT_FOUND")
        self.assertEqual(runner.calls("device.app", "launch"), [])

    def test_on_flow_complete_runs_only_on_success(self):
        hook = {"log": {"message": "bye"}}
        ok = Runner(self.broker_for_press_flow())
        self.assertEqual(ok.run(dict(PRESS_FLOW, onFlowComplete=[hook]))["status"], "SUCCEEDED")
        self.assertEqual([e["message"] for e in ok.events if e["type"] == "log"], ["bye"])

        broker = self.broker_for_press_flow()
        broker.handlers[("device.ui", "click")] = lambda a: {"verified": False}
        bad = Runner(broker)
        self.assertEqual(bad.run(dict(PRESS_FLOW, onFlowComplete=[hook]))["status"], "FAILED")
        self.assertEqual([e for e in bad.events if e["type"] == "log"], [])

    def test_ask_unanswered_ends_waiting_user(self):
        runner = Runner(StubBroker(handlers={
            ("ui.prompt", "ask"): lambda a: {"answered": False, "reason": "timeout"}}))
        flow = {"schema": 1, "steps": [{"ask": {"title": "Go on?", "message": "Ready?"}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "WAITING_USER")
        self.assertEqual(outcome["reason"], "NEEDS_INPUT")

    def test_log_message_is_redacted(self):
        runner = Runner()
        runner.run({"schema": 1, "steps": [{"log": {"message": "token s3cr3t leaked"}}]},
                   secrets={"pw": "s3cr3t"})
        self.assertEqual(runner.events[-1]["message"], "token [REDACTED] leaked")

    def test_set_result_and_next_run_outcome(self):
        runner = Runner()
        flow = {"schema": 1, "steps": [
            {"nextRun": {"afterMinutes": 30}},
            {"setResult": {"code": "done", "data": {"n": 1}}}]}
        outcome = runner.run(flow)
        self.assertEqual(outcome["status"], "SUCCEEDED")
        self.assertEqual(outcome["result"], {"code": "done", "data": {"n": 1}})
        self.assertEqual(outcome["next_run"], {"afterMinutes": 30})
        self.assertEqual(runner.by_type("next_run"), [{"type": "next_run",
                                                       "data": {"afterMinutes": 30}}])

    def test_cancelled_run_outcome(self):
        runner = Runner(cancel_after=0)
        outcome = runner.run({"schema": 1, "steps": [{"log": {"message": "x"}}]})
        self.assertEqual(outcome, {"status": "CANCELLED", "reason": "USER_CANCELLED"})


if __name__ == "__main__":
    unittest.main()
