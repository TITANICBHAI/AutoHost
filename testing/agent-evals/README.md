# Agent evals (P13-16)

JVM-runnable eval scenarios for the agent loop (`AGENT_RUNTIME` §14a, RM-G1 adapted clean-room).
The runner is `AgentEvalHarnessTest` in `:runtime:agent` — it executes every scenario here
through the REAL `AgentLoop` with a scripted FakeLlm and evaluates the `expect` clauses, then
compares the numbers against `baselines.json` (movement-only).

The fixture-app half of G13-e (the same scenarios against a real model through
`testing/stub-model/stub_model.py` over `adb reverse`) is the later half of the gate — behind
the git/CI hand-back and D-24/D-25.

## Scenario format (`scenarios/*.json`)

| Key | Meaning |
|---|---|
| `name` | stable id, also the `baselines.json` key |
| `goal` | the user goal (travels in the `<goal>` block) |
| `packages` | the run's package scope `P` |
| `observation` | the static `device.ui.snapshot` result the fake observer returns every turn (rendered by the real `AgentView` — the keep rule and caps apply) |
| `tool_results` | per-tool canned broker results the scripted pipeline returns (`{ok:true}` default) |
| `script` | the FakeLlm: one entry per turn — `{"text": "..."}` (a plain assistant message) or `{"tool_calls": [{"name", "arguments"}]}` |
| `expect` | `finished` (the run ended via a confirmed `finish`), `tools` (the exact executed set), `not_tools` (must never appear), `max_steps` (turns), `contains` / `not_contains` (substrings of the rendered observation + tool-result JSONs — the screen truth the model saw) |

## Baselines (`baselines.json`)

`{status, steps, model_calls, executed_tools}` per scenario. The runner:

- **fails** on any `expect` violation (a broken behaviour is a broken behaviour);
- **fails with a movement report** when `steps` / `model_calls` / `executed_tools` drift from the
  baseline — re-record deliberately, in the same change as the behaviour shift, never silently.

## Re-recording

Edit `baselines.json` in the same commit as the behaviour change and say so in the tracker row
evidence. A baseline change without a behaviour change is a red flag.
