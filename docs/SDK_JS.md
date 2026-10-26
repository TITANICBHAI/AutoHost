# SDK_JS — the JavaScript task surface (v1)

ADR-076/077. One table, two hosts: the device runtime (QuickJS, `:runtime:js` + the
`QuickJsEngineFactory` touchpoint) and the simulator (`autohost_sim/js_host.py` under node) both
serve these ops, and both load the SAME `prelude.js` (byte-identity is drift-tested).

## Task shape

```js
// main.js — ESM, entry beside task.json (manifest runtime.entry, default main.js)
export function main() {
  const node = autohost.call("device.ui", "wait_for", {
    selector: autohost.sel.text("Continue").to_json(),
    timeout_ms: 20000,
  });
  if (!node.node) return autohost.ctx.result("nothing_to_do");
  const click = autohost.call("device.ui", "click", { target: { ref: node.node.ref } });
  autohost.log.info("clicked", click);
  return autohost.ctx.result("pressed");
}
```

- `main` is resolved from the entry module's `export function main()` first, then a global
  `function main()` (plain-script entries work, but cannot `import`).
- `import { x } from './lib/util.js'` works on device (module loader) and in the sim (node);
  names must be relative `./`/`../`, stay inside the task folder, and live under `lib/`.
- No npm packages, no top-level await, no network access from the engine itself — task code
  reaches the world only through `autohost.*` (the broker enforces grants/scopes/switches).
- Blocking semantics match the Python SDK: every `autohost.*` call parks while the run is
  paused and raises `autohost.errors.Cancelled` when the run is cancelled.

## The surface (v1, ADR-076)

| JS | Notes |
|---|---|
| `autohost.call(capability, method, args)` | The universal verb — broker envelopes, spec-locked capability/method names; broker errors throw `AutoHostError` with `.code`/`.retryable`/`.reason`/`.fixAction`/`.details`. |
| `autohost.emit(event)` | Run event (redacted host-side like every log path). |
| `autohost.log.{debug,info,warn,error}(msg, data?)` | Log events. |
| `autohost.ctx.config` / `.profile` / `.run` / `.trigger` | Read-only snapshots (Python shapes). |
| `autohost.ctx.state()` / `.save(state?)` | JS gets a snapshot copy (Python shares the live dict): mutate, then `save(copy)`. Persists through `state.set`. |
| `autohost.ctx.secret(name)` | Fail-closed until the SecretsProvider ships values (NOT_FOUND) — same as flows/Python today. |
| `autohost.ctx.result(code, data?)` | Sets the run result (a `main()` return only wins when it is a `ctx.result()`/`Result` — Python parity). |
| `autohost.ctx.progress(fraction, message?)` | Progress event. |
| `autohost.ctx.now()` / `.minutesSince(iso)` / `.sleep(seconds)` | Clock via the host (`ctx.now()` mirrors `now_iso`). |
| `autohost.ctx.nextRun({afterMinutes} \| {at})` | Exactly one — the host clamps to the declared bounds. |
| `autohost.ctx.profileStore` / `.taskStore` | KV (`get/set/delete/keys`) over the `store` capability, non-secret values only. |
| `autohost.ctx.evidence.{note,screenshot,uiDump}` | Evidence events; screenshot failures are honest warnings (null). |
| `autohost.sel.*` | Selector builders — port of the Python SDK `sel.py`; parity is drift-tested (18-builder matrix). |
| `autohost.capabilities()` | Live availability `describe()` map. |
| `autohost.Result(code, data)` | Result marker (what `ctx.result` returns). |
| `autohost.errors.{AutoHostError, NeedsUser, Cancelled, Retryable}` | Throw these to signal: `NeedsUser(reason, message, resumeHint?)` → WAITING_USER; `Cancelled` → CANCELLED. |

Not in v1 (deliberate, ship later without runtime changes): per-module ergonomics like the
Python SDK's `device.*`/`ui.*` wrappers, `ctx.once`, `autohost.prefer` (provider preference),
UI-handler callbacks (`@ui.handler`), and task-UI tiers from JS.

## The bridge ops (the single table)

`__autohost_bridge(frame)` with `frame = JSON.stringify([op, payloadJson])`; replies
`{"ok":true,"result":…}` or `{"ok":false,"error":{code,message,retryable,reason,fixAction,details}}`
(Kotlin `JsBridge` = Python host = this table; pinned by JsBridgeTest and test_p11_part1):

| op | payload |
|---|---|
| `call` | `{capability, method, args}` |
| `emit` | event object |
| `sleep` | `{seconds}` (host caps at 3600 s per call) |
| `ctx` | `{what: config\|profile\|run\|trigger\|state}` |
| `state_save` | full state object |
| `secret` | `{name}` |
| `clock` | `{}` → `{now_iso}` |
| `result` | `{code, data}` |
| `next_run` | `{after_minutes}` or `{at}` |
| `capabilities` | `{}` |

## Engine contract (for the touchpoint owner)

`JsEngine.runEntry(prelude, entry, entryName, moduleLoader)` returns ONE JSON string:
`{"status":"ok","value":…}` or `{"status":"error","error":{kind: entry|autohost|needs_user|
cancelled|exception, …}}` — the provider classifies (JsOutcomeTest) and the sim classifies
identically (ADR-077). Caps: heap 32/16 MB (RAM class), stack 1 MB, deadline = the manifest's
`timeout_minutes` via the interrupt watcher. `close()` after the thread joins.
