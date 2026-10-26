# DEVAPI — Dev API (JSON-RPC) and MCP tools
Spec for `core/devapi` (server on the phone) and `sdk-python/autohost_cli` (PC client + MCP bridge). Plan §5.6.

## 1. Enabling and security
- **Off by default.** Settings → Developer → *Dev API*. Shows state, port, token (copy button), allowed transport.
- Binds to `127.0.0.1` only (reach it with `adb forward tcp:8080 tcp:8080`, `tcp:8081`). LAN binding requires the insecure-feature switch `dev_api_remote`.
- **Token required** (`Authorization: Bearer <token>`), rotated on demand; also read via ContentProvider with `adb` (`content query --uri content://<applicationId>.devapi/auth_token`).
- Every call goes through the **broker interceptor chain** under a synthetic run token `developer` whose grants are set in Settings → Developer → *Dev API grants* (default: observe + act on packages the developer lists; shell/raw disabled). High-risk capabilities stay behind the insecure-feature switches.
- Calls are audited (`caller=devapi`), rate-limited (default 20 req/s), and never return secret values. Release builds ship the server **disabled and not startable** unless the `full` flavor and the developer toggle are both on.

## 2. Transports
| Transport | Use | Notes |
|---|---|---|
| HTTP `POST /rpc` | one-shot calls | JSON-RPC 2.0 body; `GET /ping` unauthenticated returns `{"status":"ok"}` only |
| WebSocket `/ws` | streaming (`events.subscribe`, log tail) | JSON-RPC requests + server notifications `{"method":"event","params":{…}}` |
| ContentProvider | `adb shell content …` one-liners, bootstrap token | `query` for `ping`, `auth_token`, `state`; `call --method rpc --arg '<json>'` for any RPC |
| Reverse WebSocket (P11-07, ADR-081) | phone dials out to the PC — no USB, no adb forward | `ReverseDevApiLink` (phone = WS client) dials `ws://<pc>:8090/reverse`; the token rides the FIRST frame (`{"token": …}`, never a URL — ADR-075 rule); the same `dispatch()` serves it (caller `reverse`, same token check + rate limiter). PC side: `autohost bridge --listen 8090 --http 8081 --token <t>` (WS server role + first-frame token validation + a localhost HTTP proxy on 8081, so `autohost --port 8081 <cmd>` works unchanged); opt-in per session (Settings → Developer → Reverse bridge) and gated on the Dev API being enabled |

Envelope for results (mirrors the broker): success `{"jsonrpc":"2.0","id":1,"result":{…}}`; error `{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"…","data":{"code":"SCOPE_VIOLATION","details":{…}}}}` where `data.code` is a code from `schemas/errors.json`.

## 3. Methods
**system**: `system.ping`, `system.info` (appId, flavor, version, api, tiers, devapi version, active providers), `system.capabilities` (live availability per capability + chain).
**health**: `health.run {only?: [id]}` → results grouped *needs_fix / autofixable / optional*; `health.checks` → the registry rows (id, title, required, hasAutofix) without running them; `health.fix {id}`.
**device** (same method names and arguments as the SDK/capability specs): `device.ui.snapshot {compact?, filter?, scope?}`, `device.ui.find {selector, limit?}`, `device.ui.try {selector, action?: "click"|"set_text", text?}` (dry run unless `action` given; returns matches, the node that *would* be hit, popup-guard verdict), `device.ui.click`, `device.ui.set_text`, `device.ui.press`, `device.ui.wait_for`, `device.ui.wait_for_settle`, `device.screenshot {tag?}` (routed through the broker under the developer token), `device.app.launch|foreground|stop|query|list`, `device.screen.is_on|is_unlocked`, `device.clipboard.get|set`.
**tasks**: `tasks.list`, `tasks.get {id}`, `tasks.install {bundle_b64 | device_path}`, `tasks.install_dev {dir}` (hot-reload from the dev folder), `tasks.remove {id, keep_history?}`, `tasks.set_config {id, values}`, `tasks.profiles.list|upsert|archive`, `tasks.compat {id}` (CompatReport).
**runs**: `runs.start {task, profile?, args?}`, `runs.list {filter?}`, `runs.status {id}`, `runs.tail {id, since_seq?}`, `runs.pause|resume|cancel {id}`, `runs.resume {id, stage?}`, `runs.export {id}` → `{runId, format: "aarun", bytes, bundle_b64}` (the `.aarun` zip: manifest, redacted events.jsonl, audit.jsonl, incidents.json, checkpoint.json, environment.json, evidence/ — see ADR-050 / plan §13.1), `runs.dump_ui {id, at}`.
**triggers** *(P5 storage; live since P8-03)*: `triggers.list`, `triggers.status` — **live status per rule**: enabled, armed, last fire, launch count, cooldown remaining, debounce hold, busy-queue depth and the §11.8 grant gate (`grant_ok` with an honest note). `triggers.catalog` **(P8-03)** — every source kind with its access (`receiver|network_callback|a11y_foreground|a11y_toast|notification_listener|host`), the grant the target task must hold and the params it accepts. `triggers.upsert {rule}` (validates against `trigger-rule.schema.json` → `TRIGGER_RULE_INVALID(field)`; the runtime reloads and re-arms immediately), `triggers.delete {id}`, `triggers.test {rule, count?, fire?}` → validity +, for timed sources, the next fires via the ScheduleEngine; **`fire: true` dispatches a synthetic event through the live guard chain** (cooldown/limit/busy apply — the result tells you what happened).
**events** (WS): the server broadcasts `"method":"event"` notifications on every WS connection (`DevApiServer.broadcast`); topics ride in `params.topic`. `events.subscribe/unsubscribe` remain client-side filtering for now.
**record** *(real as of P6-09)*: `recorder.start` → arms the accessibility event tap (`{recording: true}` or `RECORDING_BLOCKED` with the reason, e.g. the a11y service is off); `recorder.stop` → converts the session and returns `{recording: false, steps, warnings?, flow, sidecar, note}` — the flow document plus the `<flow>.recording.json` sidecar JSON (ADR-053); `NOT_RECORDING` when start was never called. Saving into the dev folder happens via the review screen (`Save as flow`, gated on coordinate confirmation) — the Dev API surface is the headless authoring loop for agents.

*P2/P4 note:* the implemented set is exactly what `RpcRouter` registers — `DevApiTest` asserts the full method list; anything else in this section lands with its phase.

## 6. PC tools added in Phase 11
- **`autohost ui web [--web-port 8099]` (P11-06, S27)** — the browser inspector: a localhost-only page (tree, selector try-box, screenshot) whose JS talks exclusively to `/api/<method>`; the proxy injects `Authorization` so the token never reaches the browser (asserted by tests). Upstream errors surface as honest 502 envelopes.
- **`autohost bridge` (P11-07)** — see the transports table; `BRIDGE_DOWN` is the honest 502 when no phone is connected, and `auth_failures` counts refused tokens.
- **`autohost repo --index <url> [--install ID…]` (P11-03)** — parses an `index.json` v1 (the mirrored `RepoIndex` rules), prints per-entry verdicts against the device's installed versions (`tasks.list`), and installs through `tasks.install` with the sha256 verified when the entry carries one. https or file only.

## 4. MCP bridge (`autohost mcp`)
PC-side stdio MCP server (P5-13, `autohost_cli/mcp_server.py`): **newline-delimited JSON-RPC 2.0** on stdio — `initialize` (protocol `2024-11-05`, serverInfo `autohost-mcp`), `tools/list`, `tools/call`, `ping`; notifications get no response; parse errors answer `-32700`. Tools wrap the Dev API client (ADR-050); Dev API errors become `isError` tool results.
| Tool | Maps to | Purpose |
|---|---|---|
| `autohost_get_device_state` | `device.app.foreground` | Where is the phone right now? |
| `autohost_dump_ui` | `device.ui.snapshot` (`full?`) | See the screen as compact text |
| `autohost_try_selector` | `device.ui.find` | Test a selector without side effects |
| `autohost_screenshot` | `device.screenshot {tag?}` | Capture for visual checks |
| `autohost_push_task` | `tasks.install` (zip of a local dir) | Install/refresh a task from local files |
| `autohost_run_task` | `runs.start {task, profile?}` | Start a run |
| `autohost_get_run` | `runs.status` | Status + reason code |
| `autohost_tail_logs` | `runs.tail {run_id, since_seq?}` | Recent events |
| `autohost_export_run` | `runs.export` → writes `<runId>.aarun` to `--export-dir` | Pull a failure bundle |
| `autohost_diagnose` | `health.run` | Doctor |
Tool results are text (JSON where structured data helps); `--export-dir` (default `~/.autohost/runs`) is the only path the server writes. Destructive tools are not exposed over MCP in v1.

**MCP evals (P5-14):** `testing/mcp-evals/*.yaml` driven by `tools/mcp_eval.py` — `inspect-device`, `author-run`, `failure-export`; each step asserts `expect_contains` / `expect_not_contains` / `expect_json_path` against the tool text; exit code CI-gateable.

## 5. CLI (`autohost`)
`push <dir>` · `run <task> [--profile P]` · `logs [-f] [--run ID]` · `ui dump [--compact]` · `ui try '<selector>'` · `screenshot [-o file]` · `pull-run <id> [-o dir]` · `doctor` · `enable-shell` (PC-assisted) · `grant-secure-settings` · `docs --pack [-o dir]` · `mcp` · `sim <task>` · `ui` (web inspector, Phase 11). Exit codes: `0` ok, `1` error, `127` doctor found major issues.
