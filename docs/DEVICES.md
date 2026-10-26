# DEVICES
Fill during **P0-01**. One row per real device and per emulator image used.

| ID | Role | Model | Android / API | OEM skin | ABI list | RAM | WebView | Rooted | Patch level | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| D1 | primary real device | | 10 / 29 | | `getprop ro.product.cpu.abilist` | | | | | |
| D2 | additional real device (if any) | | | | | | | | | |
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
