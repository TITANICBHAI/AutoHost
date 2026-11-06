#!/usr/bin/env python3
"""P13-16 (G13-e later half): run the agent-eval scenarios against the PC stub model.

NOT runnable under the owner's current decisions (D-24/D-25: no emulator, no device): this
script drives `testing/stub-model/stub_model.py` over `adb reverse` and the fixture app through
the real service stack. It exists so the gate's tooling is complete when those decisions are
revisited; the JVM half of the evals (AgentEvalHarnessTest, :runtime:agent) runs the SAME
scenario files with FakeLlm and is what CI can verify today.

Usage (when device/emulator work returns):
    python testing/stub-model/stub_model.py --port 8080 --script <script.json> --log requests.jsonl &
    adb reverse tcp:8080 tcp:8080
    # install the debug APK + fixture, enable ai_cleartext_loopback, then:
    python testing/agent-evals/run_stub_eval.py --port 8080 --base http://127.0.0.1:8080/v1
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent


def load_scenarios() -> list[dict]:
    scenarios = sorted((HERE / "scenarios").glob("*.json"))
    return [json.loads(p.read_text(encoding="utf-8")) for p in scenarios]


def to_stub_script(scenario: dict) -> list[dict]:
    """A scenario's `script` is already in the stub model's entry format."""
    return scenario["script"]


def chat(base: str, payload: dict) -> dict:
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base", default="http://127.0.0.1:8080/v1")
    ap.add_argument("--model", default="stub-model")
    args = ap.parse_args()

    print("NOTE: this is the G13-e later half — it needs a device/emulator (D-24/D-25) "
          "and the real service stack. It does not run today; see the module docstring.")
    for scenario in load_scenarios():
        script = to_stub_script(scenario)
        # Smoke the stub endpoint so a wrong port/URL fails here, with a clear message.
        try:
            chat(args.base, {
                "model": args.model,
                "messages": [{"role": "user", "content": scenario["goal"]}],
                "max_tokens": 16,
            })
        except Exception as e:  # noqa: BLE001 - a dev tool; report and stop
            print(f"[{scenario['name']}] stub endpoint unreachable at {args.base}: {e}",
                  file=sys.stderr)
            return 2
        print(f"[{scenario['name']}] scenario loads; stub endpoint reachable. "
              f"The device-side loop run is the remaining half of G13-e.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
