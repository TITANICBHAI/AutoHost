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
| Reverse WebSocket (Phase 11) | phone dials out to the PC | Device initiates; same JSON-RPC |

Envelope for results (mirrors the broker): success `{"jsonrpc":"2.0","id":1,"result":{…}}`; error `{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"…","data":{"code":"SCOPE_VIOLATION","details":{…}}}}` where `data.code` is a code from `schemas/errors.json`.

## 3. Methods
**system**: `system.ping`, `system.info` (appId, flavor, version, api, tiers, devapi version, active providers), `system.capabilities` (live availability per capability + chain).
**health**: `health.run {only?: [id]}` → results grouped *needs_fix / autofixable / optional*; `health.fix {id}`.
**device** (same method names and arguments as the SDK/capability specs): `device.ui.snapshot {compact?, filter?, scope?}`, `device.ui.find {selector, limit?}`, `device.ui.try {selector, action?: "click"|"set_text", text?}` (dry run unless `action` given; returns matches, the node that *would* be hit, popup-guard verdict), `device.ui.click`, `device.ui.set_text`, `device.ui.press`, `device.ui.wait_for`, `device.ui.wait_for_settle`, `device.screenshot {region?, as: "path"|"base64"}`, `device.app.launch|foreground|stop|query|list`, `device.screen.is_on|is_unlocked`, `device.clipboard.get|set`.
**tasks**: `tasks.list`, `tasks.get {id}`, `tasks.install {bundle_b64 | device_path}`, `tasks.install_dev {dir}` (hot-reload from the dev folder), `tasks.remove {id, keep_history?}`, `tasks.set_config {id, values}`, `tasks.profiles.list|upsert|archive`, `tasks.compat {id}` (CompatReport).
**runs**: `runs.start {task, profile?, args?}`, `runs.list {filter?}`, `runs.status {id}`, `runs.tail {id, since_seq?}`, `runs.pause|resume|cancel {id}`, `runs.resume {id, stage?}`, `runs.export {id, include_evidence?}` → `{path}`, `runs.dump_ui {id, at}`.
**triggers**: `triggers.catalog`, `triggers.status`, `triggers.list`, `triggers.upsert {rule}`, `triggers.delete {id}`, `triggers.test {id|rule, payload?}`.
**events** (WS): `events.subscribe {topics: ["run","log","ui","trigger","health"]}`, `events.unsubscribe`.
**record**: `recorder.start {package?}`, `recorder.stop → {flow_yaml}`, `recorder.status`.

## 4. MCP bridge (`autohost mcp`)
PC-side stdio MCP server using the CLI's Dev API client (auto-discovers the device via `adb`, sets up `adb forward`, fetches the token). Tools:
| Tool | Maps to | Purpose |
|---|---|---|
| `autohost_get_device_state` | `system.info` + `device.ui.snapshot(compact)` header | Where is the phone right now? |
| `autohost_dump_ui` | `device.ui.snapshot` | See the screen as compact text |
| `autohost_try_selector` | `device.ui.try` | Test a selector without side effects |
| `autohost_act` | `device.ui.click/set_text/press` | Do one step to explore |
| `autohost_screenshot` | `device.screenshot` | Image for visual checks |
| `autohost_push_task` | `tasks.install_dev` / `install` | Install/refresh a task from local files |
| `autohost_run_task` | `runs.start` | Start a run |
| `autohost_get_run` | `runs.status` + `runs.tail` | Status + recent logs |
| `autohost_export_run` | `runs.export` | Pull a failure bundle |
| `autohost_record_start` / `_stop` | `recorder.*` | Record a flow from user actions |
| `autohost_diagnose` | `health.run` | Doctor |
| `autohost_list_capabilities` | `system.capabilities` | What can scripts do here? |
Tool results are compact text first (JSON only where structured data is needed); screenshots are returned as images. Destructive tools (`remove`, shell) are not exposed over MCP in v1.

**MCP evals:** `tests/mcp-evals/*.yaml` — scripted scenarios (inspect → try selector → push → run → read failure) executed against `FakeDevice` and the fixture app.

## 5. CLI (`autohost`)
`push <dir>` · `run <task> [--profile P]` · `logs [-f] [--run ID]` · `ui dump [--compact]` · `ui try '<selector>'` · `screenshot [-o file]` · `pull-run <id> [-o dir]` · `doctor` · `enable-shell` (PC-assisted) · `grant-secure-settings` · `docs --pack [-o dir]` · `mcp` · `sim <task>` · `ui` (web inspector, Phase 11). Exit codes: `0` ok, `1` error, `127` doctor found major issues.
