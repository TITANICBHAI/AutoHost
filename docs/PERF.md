# PERF — published latency numbers

Source of truth for the Phase 3 latency gate (**G3-d**): p50/p95 for `find` on 100/500/2,000-node trees,
target **p95 < 200 ms at 2,000 nodes** (plan §18 P3). Numbers marked *(UNVERIFIED-on-hardware)* are the
harness placeholders until the phone run records real values (docs/DEVICES.md tracks the device matrix).

## 1. Harnesses

| Harness | Where | Runs on | How |
|---|---|---|---|
| JVM microbench | `core/model/src/test/.../SelectorBenchTest.kt` | any machine | `gradle :core:model:test --tests "*SelectorBench*"` — prints `PERF ...` lines; asserts correctness only |
| Device bench | `testing/fixture-app/src/androidTest/.../LatencyBenchInstrumentedTest.kt` | primary phone | `gradle :testing:fixture-app:connectedDebugAndroidTest` (G3-d run) |
| Python sim parity | `sdk-python/tests/test_match.py` | any machine | `python3 -m unittest` — contract parity, not timing |

The JVM microbench measures `SelectorEngine.findAll` with a realistic 2-term selector (class + text) over a
synthetic tree with stable ids, clickable flags and 48 px rows — the shape the compact dump filter produces.
Every iteration runs the full match (both terms, deepest-match rule, document order).

## 2. Results — *(UNVERIFIED-on-hardware until G3-d)*

Recorded by the owner on the primary Android 10 phone (docs/DEVICES.md §primary) and pasted here with the
device + build fingerprint. The rows below are the harness *placeholders* produced on the dev container the
code was written in — they prove the harness runs, not the target:

| Tree size | p50 (µs) | p95 (µs) | Target | Status |
|---|---|---|---|---|
| 100 | ~120 | ~250 | — | harness-only |
| 500 | ~500 | ~1,100 | — | harness-only |
| 2,000 | ~2,000 | ~4,500 | < 200,000 µs (200 ms) | harness-only; expects ≥40× headroom on device |

## 3. Budgets used by the host (plan §5.7)

| Path | Budget | Enforced by |
|---|---|---|
| Accessibility read | 2.5 s hard, single-flight per scope | `ReadGate` / `ReadGatePolicy` (`:capabilities:a11y`) |
| `wait_for` | default 10 s, event-driven with 50–200 ms backoff | `A11yUiProvider.find` |
| `wait_for_settle` | default 10 s, quiet-window 500 ms | `SettlePolicy` |
| Screenshot | ≥1 s between captures, burst 3/min | `ScreenshotThrottlePolicy` |
| IME commit | 5 s bridge timeout | `ImeBridge` |
| Stop chain | settle 400 ms between strategies | `AppProvider.stop` |

## 4. Procedure for the G3-d run

1. Build + install both APKs (`gradle :app:assembleCoreDebug :testing:fixture-app:assembleDebug`, `adb install -r`).
2. Run the device bench: `gradle :testing:fixture-app:connectedDebugAndroidTest`.
3. Paste the printed p50/p95 rows into §2, update the Status column to *(verified, <device> <API>)*, tick G3-d
   in `docs/PROGRESS.md` with the test report path.
