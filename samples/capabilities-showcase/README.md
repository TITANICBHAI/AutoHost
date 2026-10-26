# Capabilities Showcase (P8-15)

An **honest availability sweep** over every Phase-8 capability — the §18 P8 Gate-8 showcase
snippet source. Run it and read the result dict: each section reports what worked on THIS device
or the exact typed error the host returned (`NEEDS_SETUP`, `SCOPE_VIOLATION`, …). It never fakes
a success, which is the point: the sweep doubles as a per-device capability report.

Sections (each is a copy-pasteable snippet for new tasks):

| Section | Capability | What it proves |
|---|---|---|
| `clipboard` | `device.clipboard` | set + focus-gated get (background reads point at the IME path) |
| `intents` | `device.intent` | allowlisted Settings pages dispatch |
| `notifications` | `device.notifications` | listener connection + sanitised listing |
| `events` | `device.events` | recent hub events |
| `audio` | `device.audio` | volume read + tone |
| `power` | `device.power` | battery state + wake-lock round trip |
| `screen_control` | `device.screen` (control) | brightness read, best-effort wake |
| `settings_read` | `device.settings` | one global-table read |
| `files_shared` | `files.shared` | SAF write/read inside a picked tree (needs `tree_uri` config) |
| `net_ws` | `net.ws` | connect/send/recv/close (needs `ws_url` config) |
| `webhooks` | `net.server` | endpoint status + hook names |
| `chaining` | `tasks.run` | chain a task and await its terminal status |

Optional-only manifest: the sweep runs everywhere and reports refusals honestly instead of being
blocked by the CompatReport.
