# REFERENCE_COMPARISON — AutoHost vs ClosePaw, OpenDroid, Brahma-Echo, OpenTasker (2026-11-13)

Evidence level per claim: **[code]** read in AutoHost's repo; **[ref-code]** seen in the reference's source; **[ref-readme]** only the reference's README says so (not re-checked in code). Everything about the references' *behaviour on a phone* is unverified: nothing here was run.

## Q1 — Are we on par / better?
**Answer.** Better on breadth, safety and test depth; behind on the "it has actually run on a phone" proof, and behind each reference on one specific axis.

| vs | AutoHost is ahead on | AutoHost is behind on |
|---|---|---|
| **ClosePaw** (agent-first phone control) | 47 capabilities / 157 methods vs its ~15 tools [code vs ref-readme]; permission broker + completion verifier + stuck detector; schedules, triggers, flows, Python SDK; 1413 JVM tests | agent has no subagent (`delegate_task`), no scratchpad/memory, no skills, no `termux_shell`, no Shizuku virtual display [ref-code: tool names]; its UI/overlay has run on devices, ours has not |
| **OpenDroid** | structure, permissions, tests, Python, flows | in-app on-device model (LiteRT-LM engine host + model manager) [ref-code]; many LLM providers with fallback [ref-code: `enum Provider`]; wake word, habit→macro mining [ref-readme]; ~20 executor classes [ref-code] |
| **Brahma-Echo** | n/a — a Windows desktop assistant, different category | n/a. Its licence forbids reuse; ideas only, no code taken |
| **OpenTasker** | UI targeting by selector, agent, Python, permission broker | automation breadth: 96 actions + 13 flow controls [ref-readme] vs our 29 flow commands (+ Python for anything else) [code]; ~17 trigger events (notification, app, battery/power/screen, network/SSID, SMS, toast, webhook, task chain) [code] vs its contexts for location, Bluetooth, NFC, calendar/sun, calls, media playback [ref-code: `core/contexts`]; Tasker/MacroDroid import [ref-readme] |

**Confidence 0.7.** AutoHost side read in code (0.9); reference side is partly README-only (≈0.6).
**Caveats.** "Ahead" counts what exists in code; none of it is proven on hardware (D-24/25). Counts of the references' tools come from READMEs. OpenTasker's size (~97k lines incl. tests) means its real depth is much more than a table can show.

## Q2 — Can our agent (BYOK and local) do what theirs can?
**Answer.** BYOK: yes for any OpenAI-compatible endpoint [code: `core/llm`, `BaseUrlValidator`]. Local: only through a localhost OpenAI-compatible server (another app, Termux, a computer on the network); AutoHost cannot load a model itself, OpenDroid can. Task coverage: our agent has 18 tools [code: `tool_catalog.json`] — 4 app tools, 9 `ui_*` tools, `ask_user`, `todo_write`, `wait`, `finish`. It has **no screenshot tool**, so it is blind on screens the accessibility tree does not describe (canvas, games, some web views) although `device.screenshot` and `vision` capabilities exist. It lacks the ClosePaw extras listed above.
**Confidence 0.8.** Tool list read from the catalog; gateway behaviour from code and tests.
**Caveats.** "Any OpenAI-compatible endpoint" is by design, not tested against each provider. Native Anthropic/Gemini APIs are not called directly [code: single OpenAI-style client — not exhaustively searched]. An on-device model on the owner's phone (Realme C3, 2–4 GB RAM, owner-stated, unverified) would be marginal anyway.

## Q3 — Can our Python scripts do what these can do?
**Answer.** For device actions, yes: `sdk-python/autohost` has 41 modules covering UI, apps, notifications, clipboard, files, HTTP/sockets, browser, shell, SMS, telephony, location, camera, microphone and more [code]. No: scripts cannot call the model or run the agent (no `llm`/`agent` module in the SDK) [code: module list]; and Python needs the `py` flavor (Chaquopy; heavier on CPU/RAM).
**Confidence 0.85.**
**Caveats.** "Covers" means a wrapper exists; whether each call works on a device is unverified. ClosePaw's `browser_script` runs JS through Chrome DevTools; our `browser.cdp` needs the shell tier and is marked draft/unverified [code].

## Q4 — What to build next (ranked by value for effort)
1. **Agent screenshot tool** (+ coordinate tap/swipe tools): the capabilities exist; add catalog entries. Small.
2. **Python `llm` module** over the existing gateway. Small.
3. **Quick macros** (OpenTasker-inspired "when → do"): compiled to the existing flow runtime and trigger rules, so no second engine. Medium. (In progress.)
4. **More trigger events**: location/geofence, Bluetooth, calendar, call state. Medium each; each needs a permission screen.
5. **Agent scratchpad/memory and `delegate_task`** (ClosePaw, Apache-2.0, adapt with header + notice). Medium–large.
6. **On-device model** (OpenDroid LiteRT-LM, Apache-2.0): large dependency; owner decision, probably not for a 2–4 GB phone.
Not doing: Brahma-Echo code (licence), Tasker XML import (large, low value now).

## Credit
ClosePaw (Apache-2.0, ClosePaw Authors), OpenDroid (Apache-2.0 variant), OpenTasker (MIT, SysAdminDoc) are credited in `THIRD_PARTY_NOTICES.md` and `reference/`; any file adapted from them carries the header required by `docs/REUSE_MATRIX.md`. Brahma-Echo is credited as inspiration only.
