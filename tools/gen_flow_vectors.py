#!/usr/bin/env python3
"""Generate testing/golden/flow_vectors.json from the Python reference (autohost_ref.flows).

The committed file is the shared contract: the sdk-python replay test (test_flow_vectors.py) and
the Kotlin :runtime:flow FlowInterpreterGoldenTest both assert against it case by case — same
pattern as schedule_vectors.json (ADR-039). Regenerate only when the contract changes, and
record why in docs/DECISIONS.md.

Case model (data-only; the Kotlin test rebuilds the same harness from this file):

    {
      "kind": "run",                       # run | parse_error
      "name": "press_button_happy_path",
      "flow": { ...flow document... },
      "config": {}, "profile": {}, "env": {}, "secrets": {}, "run": {"attempt": 1},
      "api_level": 29,
      "subflows": {"child": { ...flow document... }},
      "device": {
        "screens": {"<name>": {"package": "...", "nodes": [...], "hidden_nodes": [...],
                               "reveal_after": 3, "on_click": {"<id>": "<screen>"}}},
        "start": "home" | null,            # foreground screen at t0 (null = launcher)
        "launch_map": {"<package>": "<screen>"},
        "installed": ["<package>"],        # defaults to the launch_map keys
        "screenshot": true,
        "prompt_answers": {"<title>": "<answer>"},   # missing title = nobody answers
        "fail_first": {"device.ui.click": [{"code": "TIMEOUT", "message": "busy",
                                            "retryable": true}]}
      },
      "expected": {
        "outcome": { ...run_flow() result... },
        "calls": [["device.app", "launch", { ...exact args... }], ...],
        "events": [{ ...interpreter events, no ts... }, ...]
      }
    }

Node fields mirror the simulator model: id, text, desc, cls, clickable, editable, checked,
selected, focused, password, bounds [l,t,r,b] (bounds/cls/flags are auto-filled when omitted,
node i gets bounds [0, 120*i, 1080, 120*i+100]). The broker adapter records every call the
interpreter makes (including ones that fail via fail_first), raises the queued BrokerError
before dispatching, and forwards everything else to the same dispatch table the simulator uses.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sdk-python"))

from autohost_ref import flows  # noqa: E402
from autohost_ref.flows import BrokerError, Exec  # noqa: E402
from autohost_sim import FakeDevice, SimError, VirtualClock  # noqa: E402

CASES = []


class SimBroker:
    """Broker adapter over the FakeDevice: records calls, can fail the first N of a kind."""

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


def run_case(name: str, flow: dict, device: dict, *, config=None, profile=None, env=None,
             secrets=None, run=None, api_level=29, subflows=None) -> None:
    screens = device.get("screens", {})
    dev = FakeDevice(
        screens,
        launch_map=device.get("launch_map", {}),
        start=device.get("start"),
        clock=VirtualClock(),
        prompt_answers=device.get("prompt_answers", {}),
        screenshot=device.get("screenshot", True),
        installed=device.get("installed"),
    )
    broker = SimBroker(dev, device.get("fail_first"))
    events: list[dict] = []
    ex = Exec(broker, emit=events.append, check=lambda: None, sleep=broker_sleep(broker),
              api_level=api_level, subflows=subflows)
    outcome = flows.run_flow(flow, ex, config=config or {}, profile=profile or {},
                             env=env or {}, secrets=secrets or {}, run=run or {"attempt": 1})
    case = {
        "kind": "run", "name": name, "flow": flow,
        "expected": {
            "outcome": outcome,
            "calls": [[cap, method, args] for cap, method, args in broker.calls],
            "events": events,
        },
    }
    for key, value in (("config", config), ("profile", profile), ("env", env),
                       ("secrets", secrets), ("run", run), ("subflows", subflows)):
        if value:
            case[key] = value
    if api_level != 29:
        case["api_level"] = api_level
    if device:
        case["device"] = device
    CASES.append(case)


def broker_sleep(broker: SimBroker):
    def sleep(seconds: float) -> None:
        broker.device.clock.sleep(seconds)
    return sleep


def parse_error_case(name: str, flow: dict) -> None:
    try:
        flows.parse_flow(flow)
    except flows.FlowError as exc:
        CASES.append({"kind": "parse_error", "name": name, "flow": flow,
                      "expected": {"error": str(exc)}})
        return
    raise AssertionError(f"parse_error case {name} did not fail")


def node(node_id: str | None = None, text: str | None = None, **kw) -> dict:
    out = dict(kw)
    if node_id:
        out["id"] = node_id
    if text is not None:
        out["text"] = text
    return out


def screens(**named: dict) -> dict:
    return {"screens": named}


# Common screen sets ---------------------------------------------------------
PRESS = screens(
    home={"package": "com.example.app",
          "nodes": [node("fx_title", "Example app", cls="android.widget.TextView"),
                    node("fx_btn_continue", "Continue", cls="android.widget.Button", clickable=True)],
          "on_click": {"fx_btn_continue": "done"}},
    done={"package": "com.example.app",
          "nodes": [node("fx_done", "Done", cls="android.widget.TextView")]},
)
NAV = screens(
    home={"package": "com.example.nav",
          "nodes": [node("fx_home", "Home"), node("btn_open", "Open", clickable=True)],
          "on_click": {"btn_open": "detail"}},
    detail={"package": "com.example.nav", "nodes": [node("fx_detail", "Detail")]},
)
FORM = screens(
    form={"package": "com.example.form",
          "nodes": [node("fx_field", "", editable=True, cls="android.widget.EditText"),
                    node("fx_submit", "Submit", clickable=True)]},
)
LIST = screens(
    list={"package": "com.example.list",
          "nodes": [node("fx_header", "Items"), node(None, "Item"), node(None, "Item")]},
)
SCROLLER = screens(
    scroller={"package": "com.example.scroll",
              "nodes": [node("fx_top", "Top")],
              "hidden_nodes": [node("fx_target", "Target")],
              "reveal_after": 3},
)
STATE = screens(
    a={"package": "com.example.state",
       "nodes": [node("node_state", "A"), node("btn_next", "Next", clickable=True)],
       "on_click": {"btn_next": "b"}},
    b={"package": "com.example.state", "nodes": [node("node_state", "B")]},
)
PRESS_DEVICE = {"screens": PRESS["screens"], "launch_map": {"com.example.app": "home"}}

# ---------------------------------------------------------------------------
# A. happy paths and command mapping (10)
# ---------------------------------------------------------------------------
run_case("press_button_happy_path", {
    "schema": 1, "name": "Press button",
    "steps": [
        {"launchApp": {"package": "com.example.app"}},
        {"waitUntil": {"visible": {"text": ["Continue"]}, "timeout": 5}},
        {"tapOn": {"text": ["Continue"], "retryTapIfNoChange": True}},
        {"setResult": {"code": "pressed"}},
    ],
}, PRESS_DEVICE)

run_case("launch_clear_state_stops_first", {
    "schema": 1, "steps": [{"launchApp": {"package": "com.example.app", "clearState": True}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("stop_app", {
    "schema": 1, "steps": [{"stopApp": {"package": "com.example.app"}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("open_link_with_package", {
    "schema": 1, "steps": [{"openLink": {"url": "https://example.com/p", "package": "com.example.app"}}],
}, PRESS_DEVICE)

run_case("press_key_back_pops_screen", {
    "schema": 1, "steps": [
        {"launchApp": {"package": "com.example.nav"}},
        {"tapOn": {"id": "btn_open"}},
        {"pressKey": {"key": "back"}},
        {"assertVisible": {"text": "Home"}},
    ],
}, {"screens": NAV["screens"], "launch_map": {"com.example.nav": "home"}})

run_case("scroll_down_synthesises_swipe", {
    "schema": 1, "steps": [{"scroll": {"direction": "down"}}],
}, {"screens": SCROLLER["screens"], "start": "scroller"})

run_case("scroll_left_synthesises_swipe", {
    "schema": 1, "steps": [{"scroll": {"direction": "left"}}],
}, {"screens": SCROLLER["screens"], "start": "scroller"})

run_case("swipe_passthrough_explicit", {
    "schema": 1, "steps": [{"swipe": {"x1": 100, "y1": 200, "x2": 300, "y2": 400, "duration": 250}}],
}, {"screens": SCROLLER["screens"], "start": "scroller"})

run_case("hide_keyboard_and_wait_animation", {
    "schema": 1, "steps": [{"hideKeyboard": {}}, {"waitForAnimationToEnd": {"timeout": 3}}],
}, {"screens": FORM["screens"], "start": "form"})

run_case("screenshot_into_env", {
    "schema": 1, "steps": [
        {"takeScreenshot": {"into": "p"}},
        {"log": {"message": "saved ${env.p}"}},
    ],
}, PRESS_DEVICE)

# ---------------------------------------------------------------------------
# B. selector normalization through the broker (6)
# ---------------------------------------------------------------------------
run_case("selector_id_eq_and_state", {
    "schema": 1, "steps": [{"assertVisible": {"id": "fx_btn_continue", "clickable": True}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("selector_relational_below", {
    "schema": 1, "steps": [{"assertVisible": {"text": "Item", "below": {"id": "fx_header"}}}],
}, {"screens": LIST["screens"], "start": "list"})

run_case("selector_index_second_match", {
    "schema": 1, "steps": [{"assertVisible": {"text": "Item", "index": 1}}],
}, {"screens": LIST["screens"], "start": "list"})

run_case("selector_index_out_of_range_fails", {
    "schema": 1, "steps": [{"assertVisible": {"text": "Item", "index": 5}}],
}, {"screens": LIST["screens"], "start": "list"})

run_case("selector_package_term", {
    "schema": 1, "steps": [{"assertVisible": {"package": "com.example.app", "text": "Done"}}],
}, {**PRESS_DEVICE, "start": "done"})

run_case("selector_contains_mode", {
    "schema": 1, "steps": [{"assertVisible": {"text": {"value": "xampl", "mode": "contains"}}}],
}, {**PRESS_DEVICE, "start": "home"})

# ---------------------------------------------------------------------------
# C. waits and assertions (4)
# ---------------------------------------------------------------------------
run_case("wait_for_times_out", {
    "schema": 1, "steps": [{"waitFor": {"text": "Missing", "timeout": 1}}],
}, PRESS_DEVICE)

run_case("wait_until_not_visible_passes", {
    "schema": 1, "steps": [{"waitUntil": {"notVisible": {"text": "Done"}, "timeout": 2}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("wait_until_still_visible_fails", {
    "schema": 1, "steps": [{"waitUntil": {"notVisible": {"text": "Continue"}, "timeout": 1}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("assert_not_visible_fails_when_present", {
    "schema": 1, "steps": [{"assertNotVisible": {"text": "Continue"}}],
}, {**PRESS_DEVICE, "start": "home"})

# ---------------------------------------------------------------------------
# D. text, clipboard, notifications, prompts (7)
# ---------------------------------------------------------------------------
run_case("input_text_secret_resolved", {
    "schema": 1, "steps": [
        {"inputText": {"into": {"id": "fx_field"}, "text": "${secret.pw}", "secret": True}},
        {"setResult": {"code": "typed"}},
    ],
}, {"screens": FORM["screens"], "start": "form"}, secrets={"pw": "s3cr3t"})

run_case("input_text_without_secret_stays_literal", {
    "schema": 1, "steps": [
        {"inputText": {"into": {"id": "fx_field"}, "text": "${secret.pw}"}},
    ],
}, {"screens": FORM["screens"], "start": "form"}, secrets={"pw": "s3cr3t"})

run_case("erase_text_clears_field", {
    "schema": 1, "steps": [
        {"inputText": {"into": {"id": "fx_field"}, "text": "hello"}},
        {"eraseText": {"into": {"id": "fx_field"}}},
        {"copyTextFrom": {"id": "fx_field", "into": "v"}},
        {"setResult": {"code": "cleared", "data": {"text": "${env.v}"}}},
    ],
}, {"screens": FORM["screens"], "start": "form"})

run_case("set_clipboard", {
    "schema": 1, "steps": [{"setClipboard": {"text": "hi"}}],
}, {"screens": FORM["screens"], "start": "form"})

run_case("notify_post", {
    "schema": 1, "steps": [{"notify": {"title": "Backup", "text": "done"}}],
}, PRESS_DEVICE)

run_case("ask_confirm_answered_stores_bool", {
    "schema": 1, "steps": [
        {"ask": {"title": "Go on?", "message": "Ready?", "into": "ok"}},
        {"log": {"message": "answer=${env.ok}"}},
    ],
}, {**PRESS_DEVICE, "prompt_answers": {"Go on?": "yes"}})

run_case("ask_unanswered_ends_waiting_user", {
    "schema": 1, "steps": [{"ask": {"title": "Go on?", "message": "Ready?"}}],
}, PRESS_DEVICE)

# ---------------------------------------------------------------------------
# E. variables and expressions (4)
# ---------------------------------------------------------------------------
run_case("config_profile_templating", {
    "schema": 1, "steps": [
        {"launchApp": {"package": "${config.target}"}},
        {"log": {"message": "user=${profile.user}"}},
    ],
}, PRESS_DEVICE, config={"target": "com.example.app"}, profile={"user": "amy"})

run_case("run_attempt_placeholder", {
    "schema": 1, "steps": [{"log": {"message": "attempt ${run.attempt}"}}],
}, PRESS_DEVICE, run={"attempt": 2})

run_case("define_variables_then_use", {
    "schema": 1, "env": {"name": "world"},
    "steps": [
        {"defineVariables": {"greeting": "hi ${env.name}"}},
        {"log": {"message": "${env.greeting}"}},
    ],
}, PRESS_DEVICE)

run_case("when_true_expression_or", {
    "schema": 1, "steps": [
        {"log": {"message": "go", "when": {"true": "env.n == '2' || config.force"}}},
        {"log": {"message": "no", "when": {"true": "env.n == '3' && config.force"}}},
    ],
}, PRESS_DEVICE, env={"n": "2"}, config={"force": True})

# ---------------------------------------------------------------------------
# F. control flow (9)
# ---------------------------------------------------------------------------
run_case("optional_swallowed_tap", {
    "schema": 1, "steps": [{"tapOn": {"text": "Missing", "optional": True}}],
}, {**PRESS_DEVICE, "start": "home"})

run_case("retry_recovers_from_timeout", {
    "schema": 1, "steps": [
        {"retry": {"times": 2, "steps": [{"tapOn": {"text": "Continue"}}]}},
    ],
}, {**PRESS_DEVICE, "start": "home",
    "fail_first": {"device.ui.click": [{"code": "TIMEOUT", "message": "busy", "retryable": True}]}})

run_case("retry_exhaustion_fails", {
    "schema": 1, "steps": [
        {"retry": {"times": 1, "steps": [{"tapOn": {"text": "Continue"}}]}},
    ],
}, {**PRESS_DEVICE, "start": "home",
    "fail_first": {"device.ui.click": [
        {"code": "TIMEOUT", "message": "busy", "retryable": True},
        {"code": "TIMEOUT", "message": "busy again", "retryable": True}]}})

run_case("repeat_times_three_logs", {
    "schema": 1, "steps": [
        {"repeat": {"times": 3, "steps": [{"log": {"message": "tick"}}]}},
    ],
}, PRESS_DEVICE)

run_case("repeat_while_exits_when_state_changes", {
    "schema": 1, "env": {"n": "A"},
    "steps": [
        {"launchApp": {"package": "com.example.state"}},
        {"repeat": {"while": "env.n == 'A'", "steps": [
            {"tapOn": {"id": "btn_next", "optional": True}},
            {"copyTextFrom": {"id": "node_state", "into": "n"}},
        ]}},
    ],
}, {"screens": STATE["screens"], "launch_map": {"com.example.state": "a"}})

run_case("run_flow_env_overlay_isolation", {
    "schema": 1, "steps": [
        {"defineVariables": {"x": "1"}},
        {"runFlow": {"file": "child", "env": {"y": "2"}}},
        {"log": {"message": "${env.z}"}},
    ],
}, PRESS_DEVICE, subflows={"child": {"schema": 1, "steps": [
    {"defineVariables": {"z": "9"}},
    {"log": {"message": "${env.x}-${env.y}"}},
]}})

run_case("run_flow_missing_subflow_fails", {
    "schema": 1, "steps": [{"runFlow": {"file": "nope"}}],
}, PRESS_DEVICE)

run_case("hooks_start_failure_blocks_body", {
    "schema": 1,
    "onFlowStart": [{"assertVisible": {"id": "gate"}}],
    "steps": [{"launchApp": {"package": "com.example.app"}}],
}, PRESS_DEVICE)

run_case("hooks_complete_runs_on_success", {
    "schema": 1,
    "onFlowComplete": [{"log": {"message": "bye"}}],
    "steps": [{"log": {"message": "body"}}],
}, PRESS_DEVICE)

# ---------------------------------------------------------------------------
# G. failure typing, redaction, parse errors (4)
# ---------------------------------------------------------------------------
run_case("launch_not_installed_fails", {
    "schema": 1, "steps": [{"launchApp": {"package": "com.missing"}}],
}, PRESS_DEVICE)

run_case("secret_redaction_in_events", {
    "schema": 1, "steps": [{"log": {"message": "pw = s3cr3t"}}],
}, PRESS_DEVICE, secrets={"pw": "s3cr3t"})

parse_error_case("parse_error_two_commands", {
    "schema": 1, "steps": [{"log": {"message": "a"}, "setResult": {"code": "b"}}],
})

parse_error_case("parse_error_unknown_selector_key", {
    "schema": 1, "steps": [{"tapOn": {"xpath": "//foo"}}],
})


def main():
    doc = {
        "description": "Shared flow-interpreter vectors: the Python reference (autohost_ref/flows.py) "
                       "and the Kotlin :runtime:flow FlowInterpreter must agree on every case — outcome, "
                       "exact broker call log and event sequence. Generated by tools/gen_flow_vectors.py; "
                       "regenerate only with a recorded decision (see ADR-039 for the schedule precedent). "
                       "The device model is data-only: see the generator docstring for the harness spec.",
        "epoch": "2026-10-25",
        "cases": CASES,
    }
    out = ROOT / "testing" / "golden" / "flow_vectors.json"
    out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(CASES)} cases")


if __name__ == "__main__":
    main()
