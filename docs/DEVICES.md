# DEVICES
Fill during **P0-01**. One row per real device and per emulator image used.

| ID | Role | Model | Android / API | OEM skin | ABI list | RAM | WebView | Rooted | Patch level | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| D1 | primary real device (ADR-095) | Realme C3 (RMX2027) — owner-stated | 11 / 30 | | arm64-v8a (owner-stated; confirm with `getprop ro.product.cpu.abilist`) | UNVERIFIED (variants of 2/3/4 GB exist) | | | | display believed 720×1600 — UNVERIFIED, settle with `adb shell wm size` |
| D2 | second real device (ADR-095) | Samsung SM-M315F (Galaxy M31) — owner-stated | 12 / 31 | One UI | arm64-v8a (owner-stated) | UNVERIFIED (variants of 6/8 GB exist) | | | | display not recorded |
| E1 | emulator | | API 30 | | | | | | | |
| E2 | emulator | | API 33 | | | | | | | |
| E3 | emulator | | API 34 | | | | | | | |
| E4 | emulator | | latest | | | | | | | |

## Path verification matrix
Mark `V` (verified on this device, with evidence id), `E` (emulator only), `–` (not applicable), blank (not yet run).

| Provider / path | D1 | D2 | E1 | E2 | E3 | E4 | Evidence |
|---|---|---|---|---|---|---|---|
| A11y tree read + ReadGate | | | | | | | |
| A11y click/gesture | | | | | | | |
| A11y screenshot (API 30+) | – | | | | | | |
| MediaProjection screenshot + auto-accept | | | | | | | |
| IME text commit / clipboard | | | | | | | |
| Launch from a11y context / overlay / FGS | | | | | | | |
| Exact alarm → FGS | | | | | | | |
| ScreenGuard wake/recover | | | | | | | |
| Shell tier: ADB-over-TCP | | – | – | – | – | – | |
| Shell tier: Wireless-debugging pairing | – | | | | | | |
| `WRITE_SECURE_SETTINGS` self-heal | | | | | | | |
| Restricted-settings flow | – | | | | | | |
| Advanced Protection behaviour | – | | | | | | |
| Dev API / MCP | | | | | | | |
| Flow runtime (no worker) | | | | | | | |

## v4 baseline note (ADR-095, 2026-11-01)
- Older docs and `AGENTS.md` said real-device gates run on "the primary Android 10 phone". The owner's device note (`.agents/memory/android-target-devices.md`) lists the two phones above instead, so **D1 and D2 are the default verification targets** until the owner supersedes ADR-095 (for example by confirming an Android 10 phone, which would become D3). P0-01 stays BLOCKED on the owner's device facts: nothing in D1/D2 above was read from a device by this pass (model, Android/API, ABI are owner-stated; RAM, display, WebView, patch level, rooted are unknown).
- `minSdk` stays 29, so **API 29 code paths remain `UNVERIFIED-on-hardware`**; API 30 (`takeScreenshot`, `ACTION_IME_ENTER`) and API 31 (`maximumWindowMetrics`) behaviours are on-target.
- Real-device evidence lines carry `device: <model>, Android <n> / API <n>` (for example `device: SM-M315F (arm64-v8a), Android 12 / API 31`). An emulator or simulator run never counts as real-device-verified (ADR-087).
- Every RAM-dependent decision (worker count, local-model gate) uses RAM measured on the device, never an assumed class.

## v4 additions to the path verification matrix (all blank = not yet run)
| Provider / path | D1 | D2 | E1 | E2 | E3 | E4 | Evidence |
|---|---|---|---|---|---|---|---|
| `set_text` read-back (refresh-then-verify; A1) | | | | | | | |
| Click effect `changed/unchanged/unverifiable` (A2) | | | | | | | |
| Awaited gestures complete (A3) | | | | | | | |
| `scroll_until` inside real display bounds (A4; `adb shell wm size`) | | | | | | | |
| Coordinate fallback on large clickable containers (A5) | | | | | | | |
| Scope closure: ref scoping, `allow_coordinates`, `allow_global`, `device.app` (A7–A9) | – | – | | | | | |
| Agent run, stub model over `adb reverse` (loopback) | | | | | | | |
| Agent run, cloud model, shipped grant model (needs X-15) | | | – | – | – | – | |
| Capsule/overlay frame time and touch-through (S7) | | | | | | | |
| Footprint harness: APK size, cold start, RSS, worker spawn (Phase 15) | | | – | – | – | – | |
| Local inference S6 (Phase 16) | | | – | – | – | – | |
