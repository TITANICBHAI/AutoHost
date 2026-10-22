# DECISIONS
ADR format: **Context → Decision → Consequences**. Add new ADRs at the bottom; never rewrite history (supersede instead).

## Accepted
| ID | Decision | Why | Revisit when |
|---|---|---|---|
| ADR-001 | Kotlin + Jetpack Compose, single activity, UDF, coroutines/Flow, kotlinx.serialization | Standard, testable | — |
| ADR-002 | **minSdk 29**, compile/target latest stable (≥35). Do not lower target to dodge restrictions | Android 10 floor; handle newer-OS rules via the §3.5 matrix | A needed behaviour is impossible at high target |
| ADR-003 | Python via **Chaquopy** (newest supported Python ≥3.13), build-time pip, curated bundled set per flavor, vendored pure-Python in tasks | Mature embedded CPython with Java bridge | S1 fails → alternative embedded CPython behind `RuntimeProvider` |
| ADR-004 | One OS process per Python run (`:w0…:w3`), cooperative cancel then process kill | Hard cancel, crash isolation, clean memory | S1 shows multi-process unreliable |
| ADR-005 | All device/service access via a **Capability Broker** with an **interceptor chain** | One chokepoint for auth, scope, gates, audit, recording | — |
| ADR-006 | Capability **providers interchangeable** and ordered into **fallback chains** by availability/API level | Version differences and accessibility lock-downs | — |
| ADR-007 | Task = `.aatask` zip with `task.json`; manifest versioned with migrations | Install/remove/version without rebuilding the host | — |
| ADR-008 | Run state is host-owned; checkpoints written atomically by the host | Reliable resume | — |
| ADR-009 | Three task-UI tiers: auto form → declarative JSON → sandboxed HTML | No new Kotlin per task | — |
| ADR-010 | Scheduling: pluggable backends (Alarm / Work / Job) + periodic **reconcile worker** + persisted rules + global UI lock | Doze/OEM resilience | S3 results |
| ADR-011 | Plugins as separate APKs over AIDL | OS-level isolation, own permissions | — |
| ADR-012 | Secrets in Keystore-wrapped AEAD; `EncryptedSharedPreferences` not used | Deprecated | — |
| ADR-013 | **Manual DI** (`AppGraph`/`WorkerGraph`), no Hilt/Dagger/Koin | Multi-process workers, build simplicity; Maestro/Portal do the same | — |
| ADR-014 | Built-in **Flow runtime** (declarative YAML, in-process, no worker) beside Python | Cheap on low-RAM phones; recorder output format | Flow scope creeps into scripting |
| ADR-015 | **Dev API** (JSON-RPC over HTTP/WS + ContentProvider, token, off by default) + `autohost` CLI + **MCP server** | Interactive authoring loop for coding agents | — |
| ADR-016 | Do **not** set `isAccessibilityTool`; detect Advanced Protection and name the failure | Not an accessibility tool | Policy changes |
| ADR-017 | `A11yProvider` and any UiAutomation-based provider are **mutually exclusive** | UiAutomation suppresses accessibility services | S4 contradicts |
| ADR-018 | **Insecure-feature switches** (global, default off) in addition to per-task grants | Defence in depth (Appium model) | — |
| ADR-019 | AGPL/GPL reference projects are **clean-room only** | Licence compatibility | — |
| ADR-020 | Foreground service: `specialUse` on API 34+, no type on API 29–33; `mediaProjection` only for `ProjectionService` | Manifest rules by API | S3 results |

## Open decisions (use the default unless the user overrides)
| ID | Question | Default |
|---|---|---|
| D-01 | App name / package | `AutoHost` / `app.autohost` |
| D-02 | **App licence** (decide in Phase 0) | Defer until dependency/reuse inventory is ready; Apache-2.0 only if reused code is Apache/MIT-compatible |
| D-03 | DB encryption (SQLCipher) | No; secrets always Keystore-wrapped; revisit in Phase 2 |
| D-04 | Worker slots | Adaptive: 2 if ≤3 GB RAM/low-RAM, up to 4; + on-demand UI-handler worker |
| D-05 | Retention | Logs 30 d, evidence 14 d |
| D-06 | Shell tier order | ADB-over-TCP (Android 10) → Wireless-debugging pairing (API 30+) → Shizuku → root |
| D-07 | Browser default provider | In-app WebView |
| D-08 | Third runtime | JavaScript (QuickJS) |
| D-09 | Distribution | Sideload; register package under a limited-distribution developer account |
| D-10 | Build flavors | `core` and `full` |
| D-11 | Flow file format / parser | YAML (JSON accepted); vetted parser with compatible licence |
| D-12 | Dev API ports | 8080 (HTTP) / 8081 (WS), off by default |
| D-13 | Scheduler backend | `auto`; reconcile every ~20 min |
| D-14 | Python version | Newest Chaquopy supports, ≥3.13; record in `VERSIONS.md` |

## Log (append below)
- 2026-10-22: The user chose to defer the app-license decision until the dependency/reuse inventory is ready. No license is selected.
