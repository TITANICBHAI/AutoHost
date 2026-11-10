# Conventions

## Tooling
- Kotlin (latest stable), coroutines + Flow, kotlinx.serialization (no Gson/Moshi), Room, Compose Material 3. **No DI framework** — manual constructor wiring via `AppGraph` (main process) and `WorkerGraph` (worker processes).
- ktlint (official style) + detekt; `./gradlew check` must pass. Android lint errors fail the build. New code adds no warnings.
- Python SDK: type-hinted, `ruff` + `mypy --strict`; **stdlib only** in SDK core (it must run in the worker and in the simulator).

## Packages and naming
- Root package `app.autohost`. Module → package: `:core:model` → `app.autohost.core.model`; `:capabilities:a11y` → `app.autohost.capabilities.a11y`; `:feature:runs` → `app.autohost.feature.runs`.
- One public top-level type per file, file named after it. Suffixes: `Provider`, `Service`, `Receiver`, `Activity`, `ViewModel`, `UiState`, `UiEvent`, `UiEffect`, `Dao`, `Entity`, `Repository`, `Policy` (pure decision logic), `Gate` (rate/cooldown guard), `Interceptor` (broker chain), `Check` (HealthCheck).
- API-specific code: a tiny object per API level, e.g. `ScreenshotApi30`, annotated `@RequiresApi`. Never scatter `SDK_INT` checks.
- Capability ids lowercase dotted (`device.ui.act`). Wire/Python names snake_case; Kotlin camelCase. Error and reason codes `UPPER_SNAKE`.

## Architecture rules
- Dependency direction: `app → feature → core:designsystem, core:engine, core:data → core:model/common`; `capabilities → core:broker, core:model, core:common`; `runtime → core:broker, core:model`. Never capability → feature. `core:model` and `core:engine` are pure JVM (no `android.*`).
- **Pure `*Policy` classes** hold decisions (keep-awake, auto-accept cooldown, schedule rules, read-gate degrade, popup guard, trigger busy queue). They take plain inputs (including `now`) and return a sealed decision. Test them exhaustively on the JVM.
- Errors: expected failures return `AhResult<T>` (`Ok` / `Err(AhError)`); every `AhError` declares `retryable: Boolean` and an `ErrorCode`. Exceptions only for programmer errors. Nothing throws across the broker — envelope only.
- Time via `Clock`; ids via `Ids` (ULID); never `System.currentTimeMillis()` / `Random` directly.
- Coroutines: inject dispatchers (`AppDispatchers`), structured concurrency, no `GlobalScope`, `runBlocking` only at process entry points. Broker calls from Python workers block worker threads by design.
- Temp files: use `ScopedTemp` (Closeable). Run artifacts live in buckets: **inputs** (bundle, read-only), **outputs** (evidence, exports), **temp**.
- Logging: `AhLog` only. Never log secrets, tokens or password-field text; redaction is applied centrally.

## UI
- UDF: `ViewModel` exposes `StateFlow<UiState>`; UI sends `UiEvent`; one-off `UiEffect` via `Channel`. Composables are stateless; every state has a `@Preview`.
- All user strings in `strings.xml`; error/reason copy comes from `schemas/errors.json` through `ReasonCopy`. Min touch 48 dp, TalkBack labels, respect font scale.

## Data
- Room with exported schemas and tested migrations. Timestamps epoch ms UTC. Never store secrets outside `SecretStore`.

## Testing
- Names: ``fun `given X when Y then Z`()``. **Fakes over mocks.** Shared fakes in `testing/fakes` (`FakeDevice`, `FakeProvider`, `FakeClock`, `FakeBroker`). One behaviour per test.
- Layers: (1) pure unit; (2) in-process integration (real orchestrator + broker + interceptors, fake providers, fake clock); (3) instrumented against the fixture app; (4) E2E dogfood on device; (5) API matrix on emulators. Every bug fix adds a regression test.
- Instrumented tests carry `@DeviceGate("G3-b")` to map them to gates.

## Git
- Branch per session (`work/s2-ci`). Commit `<ITEM-ID>: <summary>` (plan item id first, e.g. `C-02`). PR description lists item ids, evidence (CI run link), risks.

## Docs
- Behaviour change → update the relevant doc in the same commit. Capability changes start in `CapabilitySpec` (codegen).

## Python tasks / SDK
- Tasks: one `main(…)` entry, explicit timeouts, selectors in named constants or config, no `time.sleep` for UI waits (use `wait_for`/`wait_for_settle`), no `print` (use `log`).

## JavaScript tasks (P11, ADR-076)
- Tasks are ESM: `export function main()`; `import` only from `./lib/*.js` (relative, containment-checked); no npm packages, no top-level await, no engine-side network.
- The surface is `docs/SDK_JS.md` (one table): `autohost.call` as the universal verb + `ctx`/`sel`/`log`/KV/evidence/errors helpers from the shared prelude. JS and Python behavior must match: errors classify identically (EXECUTION outcome contract), pause parks at the next call/sleep, cancel raises `autohost.errors.Cancelled`, secrets fail closed.
- The prelude (`runtime/js/src/main/resources/autohost/js/prelude.js`) is the single source; the sim keeps a byte-identical copy (drift-tested). Never edit only one copy.
- Any JS-runtime behavior change updates `SDK_JS.md` + JsBridgeTest + `test_p11_part1` in the same commit — the two hosts share one contract, tests pin both sides.


## Capability module template checklist (P8-16)

Every capability ships through the §6.3 template — tick all eight steps in the task's evidence
before marking `(WRITTEN)`. The table records which capabilities have been through it (P8 sweep);
`+` = shipped, `–` = not in this phase's scope.

| Capability | 1 Spec | 2 Provider + availability | 3 Access/fix mapping | 4 SDK + stub | 5 Docs + showcase snippet | 6 Tests | 7 Redaction | 8 Tracker |
|---|---|---|---|---|---|---|---|---|
| `device.ui` / `device.screenshot` / `device.app` / `device.screen` (read) / `device.ime` | + | + | + | + | + | + | + | P1–P3 |
| `notify.post` / `ui.prompt` / `store` / `secrets` / `state` / `files.private` | + | + | + | + | + | + | n/a | P1/P5 (files.private provider P8) |
| `net.http` | + | + (provider P8) | + | + | + | + | headers | P8 |
| `device.notifications` | + | + | + | + | + | + | sanitised fields only | P8-01 |
| `device.clipboard` | + | + | + | + | + | + | n/a | P8-05 |
| `device.intent` | + | + | + | + | + | + | n/a | P8-06 |
| `device.events` | + | + | + | + | + | + | minimal payloads | P8-02 |
| `files.shared` | + | + | + (PICK_FOLDER) | + | + (showcase) | + | n/a | P8-07 |
| `net.ws` | + | + | + (domains) | + | + (showcase) | + | headers | P8-08 |
| `net.server` | + | + | + (token; LAN opt-in) | + | + (S23 + showcase) | + | token never logged | P8-08 |
| `device.audio` | + | + | + (DND access) | + | + (showcase) | + | n/a | P8-09 |
| `device.power` | + | + | + | + | + (showcase) | + | n/a | P8-09 |
| `device.screen` (control) | + | + | + (WRITE_SETTINGS) | + | + (showcase) | + | n/a | P8-10 |
| `ui.overlay` | + | + | + (a11y) | + | + (showcase) | + | n/a | P8-10 |
| `device.settings` (read) | + | + | + | + | + (showcase) | + | n/a | P8-11 |
| `schedule.self` | + | n/a (engine: ctx.next_run) | + | + (ctx) | + (press-button) | + | n/a | P4-11 |
| `tasks.run` | + | + (chain runner) | + (scope tasks) | + | + (showcase) | + | n/a | P8-11 |
| `android.api.direct` | + | + (handshake) | + (grant + raw_api switch + import guard) | + | + (SDK docs) | + | n/a | P8-12 |
| `device.shell` | + | + (transport-chain availability) | + (grant patterns + `shell` switch + metachar deny) | + | + (showcase shell_tier) | + | n/a (secrets refused) | P9-05 |
| `device.ui` (shell link) | + (same spec) | + (after a11y) | + (device.ui.act scoping; secret text refused) | + (same SDK) | + | + (dump parser + honest acts) | command-line secrets refused | P9-06 |
| `device.ui` (ui.automation link) | + (same spec) | + (between a11y and shell dump) | + (exclusivity policy + experimental toggle; secret text ALLOWED via socket — ADR-068) | + (same SDK) | + | + (exclusivity + protocol tests) | socket text, never a command line | P9-08 |
| `device.app.manage` | + | + (installer UI / shell) | + (grant + `app_manage` switch + confirm rails) | + | + | + (policy + provider tests) | n/a | P9-09 |
| `device.logcat` | + | + (shell only) | + (grant + `logcat` switch) | + | + | + (parser + provider tests) | log text may hold secrets — snapshot is grant-scoped, no auto-evidence | P9-10 |
| `device.radio` / `device.display` | + | + (shell only) | + (grants; no pre-declared switch) | + | + | + (provider tests) | n/a | P9-10 |
| `device.location.mock` | + | + (shell appop) | + (grant + `mock_location` switch) | + | + | + (provider test) | n/a | P9-10 |
| `device.settings` (write) | + (same capability) | + (writer seam) | + (ADR-067 policy: protected keys, confirm for secure/global) | + (device.settings_write) | + | + (policy + provider tests) | n/a | P9-10/11 |
| `browser.webview` | + | + (visible surface; host seam) | + (browser.open/read domains; no stealth knobs by construction) | + (browser.py) | + (S25 UI_SPEC + web-login-scrape sample part 2) | + (BrowserModuleTest + sim) | type text never echoed; task: bundle-only | P10-02 |
| `browser.a11y` | + (same family) | + (after webview) | + (same grants; cookies refused COOKIES_UNSUPPORTED_LINK) | + (browser.a11y_*) | + | + (BrowserModuleTest + sim) | visible text only | P10-03 |
| `browser.cdp` | + (same family) | + (draft until S27) | + (same grants + shell tier) | + (browser.cdp_*) | + (status note on the wire) | + (CdpBrowserProviderTest) | evaluate stays grant-scoped | P10-04 |
| `vision` | + | + (engine seams: ML Kit/ZXing/pure NCC) | + (vision.read; run/task-scoped images) | + (vision.py) | + (hint:true contract + ocr-demo part 2) | + (VisionModuleTest golden vectors + sim) | OCR text never auto-evidenced | P10-05 |
| `contacts` / `calendar` / `media` | + | + (ContentResolver seams in :app/data) | + (lazy RUNTIME_PERMISSION + REQUEST_PERMISSIONS) | + (contacts/calendar/media.py) | + | + (DataClusterTest + sim) | rows capped; media bytes capped | P10-06 |
| `location` / `camera` / `microphone` / `sensors` | + | + (one-shot seams; no streams) | + (lazy perms; BACKGROUND_LOCATION honest gate) | + (location/camera/microphone/sensors.py) | + | + (DataClusterTest + sim) | captures run-scoped; no stealth | P10-07 |
| `device.sms` / `device.telephony` | + | + (SmsManager/ACTION_CALL) | + (full flavor + sms/telephony switches + premium rails) | + (sms/telephony.py) | + | + (DataClusterTest + sim) | delivered/verified always false | P10-08 |
| `net.socket` / `net.reverse` | + | + (initiator-only; run-scoped handles) | + (NetPolicy domains; net_reverse switch + token-first-frame) | + (sockets.py) | + | + (SocketProvidersTest + sim) | token never in URLs | P10 riders |

**Rule:** a new capability PR that cannot tick a column is not done. The showcase sample
(`samples/capabilities-showcase`) carries the snippet for step 5.


## App packs and plugin capabilities (Phase 11 conventions)
- **Pack selectors are selector documents** (the `sel.to_json()` shape — one selector language for flows, packs, SDK). Never invent a second mini-syntax inside `pack.json`.
- **`config.packs` is a reserved run-config key.** Tasks must not declare their own `packs` config property; the host payload wins with a logged warning (ADR-080).
- **Plugin capability ids are namespaced `plugin.<vendor>.<name>`** and one permission per method; a describe() that cannot be fully validated rejects the WHOLE plugin (ADR-079) — never ship a partial declaration.
- **Pack/plugin updates never require task changes** — that is the point (plan §7.10, Gate F4); a task pins nothing: the run result carries the pack version that was used.
- **Secrets never enter backups** (`secret_names` only, ADR-082); the codec refuses secret-shaped keys from BOTH sides — do not 'helpfully' encode values.

## v4 conventions (planning pass 2026-11-01)
1. **Adapted files carry a header:** `Adapted from <project> <upstream path> (Apache-2.0, <copyright line>). Changes: <what and why>.` plus a `THIRD_PARTY_NOTICES.md` row, the ClosePaw attribution line where applicable, the ported upstream test and tracker evidence (`docs/REUSE_MATRIX.md` §0). Only ClosePaw and opendroid files are eligible; everything else is clean-room.
2. **Failing test first for predicted defects** (ADR-089): a finding made by reading code is a prediction until a test or an emulator run reproduces it; write the red test, then fix; if it does not reproduce, record "does not reproduce on <env>", keep the test, skip the fix.
3. **Evidence vocabulary** (ADR-087): *JVM* (unit-tested), *emulator* (integration-tested), *device* (real-device-verified, evidence carries `device: <model>, Android <n> / API <n>`). Simulator and emulator runs never count as real-device.
4. **(Retired.)** Tracker state tags went away with the tracker; `STATUS.md` is the only state file.
5. **Never swallow `CancellationException`:** rethrow it before any broad `catch (e: Exception)`; classify everything else as retryable or terminal.
6. **Reserved run-config keys `packs` and `agent`** are host-written only; a task's own `config` property with either name is rejected by the validator and the host payload wins with a logged warning (ADR-080 precedent).
7. **Untrusted content:** screen text, notification text and web content are data. The agent wraps them in the user role with an explicit untrusted marker, never in the system prompt or a tool description, and they never change grants, budgets or the tool catalog.
8. **The agent tool catalog is generated and byte-stable** from `schemas/capability-specs.w1.json`; a snapshot test fails on any diff; never hand-edit.
9. **No model-written code runs.** The agent only calls catalog tools through the broker; there is no second action path.
10. **Provider keys** live only in `SecretStore` under the reserved host profile and appear only in the engine's `Authorization` header; logs and audit carry sizes and hashes; the canary tests stay green.
11. **Epoch V first** (ADR-097): Phase 12+ implementation starts after X-09; Python-only and tooling items (generators, checkers, mock servers) may start earlier.
12. **Reference material stays out of the build:** `reference/` and `drafts/` are never on a Gradle, Python or test source set.
13. **(Retired.)** `tools/doc_check.py` was removed; keep docs consistent by hand and by reading the CI digest.
