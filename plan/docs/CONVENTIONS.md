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
- Branch per phase (`phase-3-device-depth`). Commit `P3-07: <summary>` (task id first). PR description lists task ids, evidence, risks.

## Docs
- Behaviour change → update `plan.md` or docs in the same commit. Capability changes start in `CapabilitySpec` (codegen).

## Python tasks / SDK
- Tasks: one `main(…)` entry, explicit timeouts, selectors in named constants or config, no `time.sleep` for UI waits (use `wait_for`/`wait_for_settle`), no `print` (use `log`).
