# INTEGRATION_V3 — macros, Python scripts and agents working as one

Status: DESIGN (2026-11-14). Nothing here is built except what is tagged [code]. Evidence tags: **[code]** read in this repo, **[spec]** in `schemas/capability-specs.w1.json`, **[unverified]** not checked on a device or not read yet, **[opinion]** my judgement.

Owner answers that start this (2026-11-14): "yes" to the agent sending screenshots, "yes" to scripts calling a model. Both stay **opt-in per provider** and off until the person turns them on (`docs/AGENT_RUNTIME.md` §6, ADR-091).

## 1. The idea in one sentence
Do not build a fourth engine. A macro, a Python script and an agent goal are three kinds of **step**; any of them can start any other, hand it data and read its answer. Python is the conductor when logic is needed, macros are the hands when a screen must be tapped, the agent is the eyes when the screen cannot be described in advance.

## 2. What exists today (verified)
| Link | State | Evidence |
|---|---|---|
| Python starts a macro/task and waits for the result | **Works in code.** `autohost.tasks.run(task, profile, args, timeout_ms)` → `{run_id, status, result}`. Target must be named in the task's `tasks.run` grant. Chain depth max 3, wait max 10 min. | [code] `TasksRunProvider`, `sdk-python/autohost/tasks.py` |
| A macro is a task | Yes: the quick macro compiles to `task.json` + `flow.json` + a trigger rule. So its task id is what Python passes. | [code] `ui2/macro/MacroCompiler` |
| Data into the started task | `args` arrive in the child's trigger payload as `args`. Whether a **flow** can read them as variables is **not established**: flow variables resolve `env → config → profile` and `run.*`. | [code] `AppGraph.chainRunner`, `VariableScope` · [unverified] |
| Data out of the task | One string (`result`; flows set it with `setResult`). | [code] |
| Shared notes between steps | `store.profile` (shared by tasks on the same profile) and `store.task` (private). Non-secret values only. | [spec] |
| Macro starts Python / another task | **Missing.** Flow commands are launchApp, tapOn, inputText, scroll, copyTextFrom, setClipboard, takeScreenshot, notify, runFlow (a sub-flow file), repeat, retry, setResult, nextRun, ask, defineVariables … no `runTask`. | [code] `FlowParser` |
| Agent starts a task/script | **Missing.** Agent tools: 9 `ui_*`, 4 `app_*`, `ask_user`, `todo_write`, `wait`, `finish`. | [code] `tool_catalog.json` |
| Script/macro starts the agent | **Missing.** No agent module in the SDK. | [code] |
| Script calls a model | **Missing** (`MODEL_GATEWAY.md`: needs a new `ai.llm` capability + ADR). | [code] |
| Agent sees the screen as pixels | **Missing.** `ChatMessage.User` carries text only. Screenshot capture, OCR and template match exist as capabilities. | [code] `core/llm/LlmTypes.kt` · [spec] |
| Script edits a macro | **Missing, and risky** (see §5). | [code] |
| Wait for "Gemini finished" | `device.notifications.wait_for` exists for Python. Needs notification access on. | [spec] |
| Daily at two times | Schedules and trigger rules exist (per task). | [code] |
| Floating ball | `BubbleOverlay` exists (surface). Its actions are not configurable yet. | [code] `surfaces/BubbleOverlay.kt` |

## 3. Your example, step by step
"Every day make a video in Gemini, then upload it to YouTube Shorts; two videos a day."

| # | Step | Best doer | Existing piece | Gap / risk |
|---|---|---|---|---|
| 1 | Two daily times fire the pipeline | Schedule | trigger rules | none |
| 2 | Open the Gemini app | Macro node | `launchApp` | none |
| 3 | Script notices step 2 is done, types the request | Python | `ui` module, `tasks.run` returns when the macro ends | handoff works only if Python is the conductor (§4) |
| 4 | Scroll, copy the idea Gemini wrote | Macro or Python | `scroll`, `copyTextFrom`, clipboard | Gemini's screen may expose few accessibility nodes **[unverified]** |
| 5 | Ask Gemini to make the video, and split out title and Shorts description | Python + model | none today | needs `ai.llm` (yes) or let Gemini itself write them and copy them |
| 6 | Wait until the video is built and downloaded | Python | `notifications.wait_for` | the Gemini package name and notice text must be confirmed on your phone **[unverified]** |
| 7 | Open YouTube, tap +, choose the video | Macro with element picker, or agent | `device.ui`, element picker (§6) | the system file picker is a different app; most fragile step |
| 8 | Enter title and description | Python | `set_text` / IME | none beyond step 7 |
| 9 | Upload, confirm | Macro/agent | | YouTube can change its screens at any time |

**Honest read of the example [opinion]:** every step has a doer, but steps 4, 6, 7 and 9 depend on two apps whose screens are not under our control. That is the risk, not the plumbing. Two cheaper routes exist and should be offered, not hidden: (a) the model-maker's own API for the video (a key and a bill instead of screen driving); (b) the YouTube upload API (needs a Google Cloud project and OAuth; new unverified apps may have uploads locked to private). Screen driving is for when no API exists or you do not want one. I have not checked either API's current limits, prices or terms, so treat them as leads.

Also, a policy point I cannot resolve for you: automated daily uploads of AI-made video may need YouTube's AI-content disclosure, and automating a consumer app can break that app's terms. I did not research the current rules. Check them before you rely on this.

## 4. The model: Python conducts, macros act, the agent sees
```
Schedule / trigger / button / bubble
          │
   "Workflow" task  (Python, readable, editable)
     ├─ tasks.run("macro.open-gemini")        → macro (nodes)
     ├─ llm.chat("write title + description") → model
     ├─ agent.run("make the video, tell me when it is ready", apps=[gemini])
     ├─ notifications.wait_for(...)
     └─ tasks.run("macro.upload-short", args={title, desc})
```
- **Python as conductor keeps chain depth at 1** (limit is 3), so the 3-hop limit never bites a normal pipeline.
- A visual **Workflow builder** (list of steps: Macro / Script / Agent goal / Ask me / Wait for notice / If) **generates this Python**, which the user can open and change. No second runtime to maintain. Needs the `py` flavor; the `lite` flavor can only chain macros (via the new `runTask` below).

## 5. New pieces, ranked by cost
Cost = my rough count of CI-checked rounds, without a device **[opinion]**.

| # | Piece | Cost | Notes |
|---|---|---|---|
| A | Flow command `runTask(task, args) → variable`, same grant and depth limit as `tasks.run` | S–M | lets a macro hand over to Python or another macro |
| B | Flow variables from `args` (`trigger.args.x`) and richer `result` (JSON) | S–M | first read how the flow runtime builds its scope **[unverified]** |
| C | Declared macro **inputs** (named variables, with a default) that Python can set per run | M | the safe way for "script modifies a macro" |
| D | Python `macros` module: list, read, **set inputs**, run | M | covered by `tasks.run` grants |
| E | Agent tool `run_task` (+ allowlist of tasks the user ticks) | M | agent runtime is frozen by `AGENTS.md`; you lifted that for this |
| F | Agent chat tab: type what you want, see steps, Stop, approvals | M–L | `AskSheet` and `AgentLoop` exist; the tab is mostly UI + run lifecycle |
| G | Image messages (`ChatMessage.User` with images) + per-provider "send screenshots" switch + sensitive-app block + secure-screen refusal + cap of 2 per request | M–L | privacy-critical; tests first |
| H | Agent tools: `screenshot`, `ui_tap`(x,y), `ui_swipe`, with per-call approval until the user allows a run | M | ADR-091 (10) says exactly this |
| I | `ai.llm` capability + Python `llm.chat` (budget, kill switch, AI-off default, no secrets in prompts) | M | new ADR |
| J | Python `agent.run(goal, apps, max_steps)` returning a result | M–L | reuses the loop; same budgets and approvals |
| K | Macro **node editor**: action picker, typed fields, installed-app picker, **live screen element picker** | L | adapt OpenTasker (MIT) pickers; see §6 |
| L | Workflow builder (steps → generated Python) | L | after A–E |
| M | Floating ball with configurable actions (Ask agent, Run macro X, Stop all, Screenshot to agent) | M | `BubbleOverlay` exists |
| N | Local model on the phone | XL | owner decision; a 2–4 GB phone is marginal |

**Rule on "script rewrites a macro" [opinion, security]:** do **not** allow it in v1. A script that can rewrite another task's steps can also widen that task's permissions (a macro's grants are scoped to the apps it names). Give scripts **inputs** (C, D). A separate `macros.edit` capability can come later behind an insecure switch with a per-call approval.

## 6. Macro editor: "pick from nodes, not type text"
What the reference zip (OpenTasker, MIT) has and we can adapt, with the file header and `THIRD_PARTY_NOTICES.md` row the licence check requires: a searchable **action picker**, a **typed field form** per action (dropdowns, app picker, file picker, variable chips), an installed-app picker, a context → task → actions **graph view**. What we add on top, because our macros drive other apps' screens:
- **Screen element picker**: take a snapshot of the foreground app (`device.ui.snapshot`), show it as a list/outline, tap the element you mean, and we store a selector. This replaces typing button text and is what makes macros reliable.
- **Two ways to build**: the current quick form (kept) and the node editor (opt-in per macro), both producing the same `flow.json`.
- Each node shows what it needs ("needs accessibility on") from the same `Readiness` rules used on the task card.

## 7. UI structure this implies
Bottom bar, five slots with icons: **Tasks · Agent · Build · Activity · Settings**.
- **Tasks**: what you can run, "Needs: …" on cards, Run.
- **Agent**: chat with the cloud or local agent; shows each step, can attach a screenshot per step (if enabled), can start scripts and macros by name.
- **Build**: macros, scripts, folders, templates, workflows (the old "+ Add" lives here).
- **Activity**: runs, schedules, triggers (the old Today and When).
- **Settings**: AI and keys, access and permissions (the old Setup readiness list), safety, developer.
- **Onboarding is its own full-screen flow** shown once, not a tab and not a card on a screen. Skippable steps; reachable again from Settings.

## 8. Complexity summary
- **Already there:** Python → macro, store, schedules, notifications wait, screenshot/OCR capabilities, the agent loop, the bubble.
- **Small joins (A–D):** macro ↔ Python data and calls. This is the heart of your request and it is not large.
- **The expensive, risky parts:** G/H (pixels to a cloud model: privacy, approvals), K (node editor), and the fragility of driving Gemini/YouTube screens (outside our control).
- **Cannot be checked here:** all of it on a real phone. Every estimate above is untested against hardware.

## 9. Proposed order (each step ends CI-green and is pushed)
1. Crash fix (done) → identity + 5-slot bar + separate onboarding (UI only).
2. A, B, C, D (macro ↔ Python joins) with tests against the real flow parser and the Python sim.
3. Agent tab (F) text-only, then E (`run_task`), then I (`ai.llm`).
4. G, H (screenshots) with the opt-in switch and tests first.
5. K (node editor) in slices: app picker → action picker → element picker.
6. M (bubble actions), then L (workflow builder).

## 10. Questions for the owner
1. Is a `lite` build (no Python) still wanted, or may the app require Python (`py` flavor only)? It decides whether macros need their own `runTask` (A) or can lean on Python.
2. Gemini example: should the model write titles/descriptions inside AutoHost (`llm.chat`, needs a key), or should Gemini write them and AutoHost copy them?
3. May I use an official API where one exists for a step (video, upload), offered next to the screen-driving option?
