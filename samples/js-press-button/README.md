# js-press-button — the press-button archetype in JavaScript

The archetype-A reference task (`samples/press-button`, Python) rewritten against the **JS v1
surface** (ADR-076) — the third runtime, shipped as pure task files with **zero Kotlin changes**
(Gate 11-a) and running through the same broker as Python and flow tasks (Gate 11-b/F5).

- `task.json` — manifest with `runtime: {type: "javascript", entry: "main.js"}`.
- `main.js` — ESM entry: `autohost.call` as the universal broker verb, `autohost.sel` builders,
  `ctx.profileStore` cooldown, honest failure path (evidence + `ctx.nextRun` proposal + rethrow).

Run it in the simulator (no phone needed):

```python
from autohost_sim import FakeDevice, run_js_task
out = run_js_task("samples/js-press-button", device=FakeDevice(screens, launch_map=...),
                  config={"target_package": "com.example.app", "button_label": "Continue"})
assert out.status == "SUCCEEDED" and out.result.code == "pressed"
```

On device it runs like any other task — the orchestrator resolves `runtime.type: "javascript"`
through the `:runtime:js` provider (QuickJS in-process). Surface reference: `docs/SDK_JS.md`.
Sim contract note: the sim executes the entry under node with the SAME prelude the device loads
(ADR-077); `verified:true` here is the simulator's NODE_ACTION verdict — device runs report the
accessibility action's own verification like the Python archetype does.
