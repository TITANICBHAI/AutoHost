# press-button-flow

The `press-button` archetype written as a **declarative flow** (plan §7.9) instead of a Python
script. Same job: launch the target app, wait for a labelled button, press it (with settle +
retry-if-no-change), report `pressed`.

## Files

- `task.json` — manifest with `runtime.type = "flow"`; grants scope `device.app.launch`,
  `device.ui.observe`, `device.ui.act` to the configured target package.
- `flow.yaml` — the canonical authored document (plan default 15: flows are YAML).
- `flow.json` — the **generated JSON twin**. Keep the two byte-for-byte equivalent in meaning;
  `tests/test_samples.py::test_flow_twin_matches_yaml` fails when they drift (the twin must be
  regenerated whenever the YAML changes).

## Why a twin (ADR-052)

On-device flows are JSON in v1: the Kotlin `FlowParser` reads `flow.json` today, while YAML
becomes a parser plug (`yamlkt`, Apache-2.0) once a Gradle machine can resolve and licence-check
the dependency. Until that plug is installed, a task pointing at `flow.yaml` fails at start with
a typed message asking for `flow.json`. This sample keeps the YAML as the authoring reference
and ships the twin so the task is runnable today:

- simulator / agent testing: `autohost_sim.flows.run_flow_task` runs the JSON twin end to end
  (see `tests/test_sim_flows.py` and the `test_press_button_flow_twin_runs` case).
- on-device until the plug ships: point `runtime.entry` at `flow.json` in a copied task folder —
  or install the plug in `:app` wiring (`YamlParserPlug.install(...)`), after which the sample
  loads as written.

## Run it (once a device or the simulator is available)

```bash
# simulator (no phone needed)
cd sdk-python && python3 -m unittest tests.test_sim_flows -v

# phone (Phase 6 / Gate 6-a): the run must show NO :w* worker process
adb shell ps -A | grep autohost   # only the main process expected
```

Config: `target_package` (app picker) and `button_label` (default `Continue`). The fixture app's
home screen has a clickable `Continue` button that navigates to a done screen — the same
interaction `press-button` scripts in Python.
