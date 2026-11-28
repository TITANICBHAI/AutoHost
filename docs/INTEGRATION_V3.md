# INTEGRATION_V3 — macros, Python scripts and agents working as one

Status: DESIGN (2026-11-14), first joins BUILT (updated 2026-11-18). Built and CI-green: **B** flows read the inputs a script passes (run 34), **R** `tasks.run` hands back the child's answer and **D** Python `autohost.macros` (run 35), **A** `runTask` (a macro starts another task, ADR-102; run 36), **C1** the quick-macro editor takes inputs and offers Type text, Scroll, Press key, Run another task and Finish with a result (run 37), **C2a** the editor picks an app or a task from a list instead of asking for a package name or id (run 41), **F1** the Agent tab is a chat that starts the supervised agent and shows its steps as plain lines, with Stop (run 43). Nothing here has run on a phone. Evidence tags: **[code]** read in this repo, **[spec]** in `schemas/capability-specs.w1.json`, **[unverified]** not checked on a device or not read yet, **[opinion]** my judgement.

Owner answers that start this (2026-11-14): "yes" to the agent sending screenshots, "yes" to scripts calling a model. Both stay **opt-in per provider** and off until the person turns them on (`docs/AGENT_RUNTIME.md` §6, ADR-091).

## 1. The idea in one sentence
Do not build a fourth engine. A macro, a Python script and an agent goal are three kinds of **step**; any of them can start any other, hand it data and read its answer. Python is the conductor when logic is needed, macros are the hands when a screen must be tapped, the agent is the eyes when the screen cannot be described in advance.

## 2. What exists today (verified)
| Link | State | Evidence |
|---|---|---|
| Python starts a macro/task and waits for the result | **Works in code.** `autohost.tasks.run(task, profile, args, timeout_ms)` → `{run_id, status, result}`. Target must be named in the task's `tasks.run` grant. Chain depth max 3, wait max 10 min. | [code] `TasksRunProvider`, `sdk-python/autohost/tasks.py` |
| A macro is a task | Yes: the quick macro compiles to `task.json` + `flow.json` + a trigger rule. So its task id is what Python passes. | [code] `ui2/macro/MacroCompiler` |
| Data into the started task | **Built (B, run 34).** `args` arrive in the child's trigger payload; a flow reads them only through the names its own `env` block declares (the declared value is the default), plain values only, read-only copies in `run.args` / `run.trigger`. Anything not declared is ignored and logged by name. Before B a flow could not read them at all. | [code] `FlowInputs`, `FlowRuntimeProvider`, `FlowInputsTest` |
| Data out of the task | **Built (R, run 35).** The child's `setResult` `{code, data}` come back as JSON text in `result`. My first version of this row said `setResult` already fed `result`; it did not — the host read a key the flow runtime never sets, so `result` was always empty. | [code] `TasksRunProvider.resultText`, `SettingsAndChainProvidersTest` |
| Shared notes between steps | `store.profile` (shared by tasks on the same profile) and `store.task` (private). Non-secret values only. | [spec] |
| Macro starts Python / another task | **Built (A, ADR-102; run 36).** Flow step `runTask {task, args?, into?}`: fixed task id, plain-value inputs, wait = the step's `timeout` (default 60 s), answer in `into` as `{status, run_id, code, data, text}`. Same `tasks.run` grant and depth limit as Python. The quick-macro editor offers it as *Run another task* (C1, run 37) and the person picks the task by name from a list (C2a, run 41). | [code] `FlowParser`, `CommandExecutors.runTask`, `flows.py`, schema, vectors, `MacroCompiler` |
| Python runs a macro by id | **Built (D, run 35).** `autohost.macros.run(id, inputs)` → `MacroResult(status, code, data, text)`; refuses non-plain inputs before the call; raises `Timeout` / `NeedsUser` / `Fatal`. | [code] `sdk-python/autohost/macros.py`, `tests/test_macros.py` |
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

## 3a. The same example with what exists now (2026-11-15)
One Python script conducts; each screen job is a small macro. This is what can be written **today** (hand-written flows and a script). The tags say what is missing for the **quick-macro editor** to build the macros without JSON.

```python
from autohost import ctx, log, macros, notifications

def main():
    gemini = ctx.config["gemini_package"]       # confirm the real package name on your phone [unverified]
    macros.run("open-gemini")                    # a macro that launches the app
    macros.run("gemini-send", {"prompt": ctx.config["video_prompt"]})      # quick editor: a declared value "prompt" + a "Type text" step using {prompt}
    notifications.wait_for(package=gemini, text_regex="video", timeout_ms=15 * 60 * 1000)   # needs notification access on
    idea = macros.run("gemini-copy-idea")        # quick editor has "Scroll" and "Finish with a result"; "Copy text" is not built yet
    log.info("idea copied", chars=len(idea.data.get("idea", "")))
    macros.run("youtube-upload-short", {"title": idea.data["title"], "description": idea.data["description"]})
    return ctx.result("ok", {"title": idea.data["title"]})
```

The macro that receives the inputs declares them in its `env` (the value is the default when the script passes nothing) and answers with `setResult`:

```json
{"schema": 1,
 "env": {"title": "", "description": ""},
 "steps": [
   {"launchApp": {"package": "com.google.android.youtube"}},
   {"inputText": {"text": "${title}", "into": {"text": "Title"}}},
   {"setResult": {"code": "UPLOADED", "data": {"title": "${title}"}}}
 ]}
```

A macro can also start another task and use its answer (step **A**):

```json
{"runTask": {"task": "gemini-copy-idea", "into": "idea"}},
{"notify": {"title": "Idea ready", "text": "${env.idea.data.idea}"}}
```

Two a day at chosen times is two trigger rules on the Python task (schedules exist per task). Still unproven: that Gemini's and YouTube's screens expose enough accessibility nodes for the macros (see §3).

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
| A | Flow command `runTask(task, args) → variable`, same grant and depth limit as `tasks.run` | S–M | **Done** (ADR-102): run 36, 1520 tests green |
| B | Flow variables from `args` and richer `result` (JSON) | S–M | **Done**: inputs through a declared `env` (run 34), result text (run 35) |
| C | Declared macro **inputs** (named variables, with a default) that Python can set per run | M | **Done**: the flow side is B's `env` block (run 34); the quick-macro editor's *Values it can be given* and the steps Type text, Scroll, Press key, Run another task, Finish with a result are C1 (run 37, 1548 tests green). In texts a value is written `{name}`, a saved answer `{answer.code}` or `{answer.data.name}` |
| C2 | Quick-editor follow-ups | S–M | **C2a done** (run 41, 1569 tests green): every step and "when" that names an app has an app button, *Run another task* has a task button; each opens a searchable list under the button showing names only (apps with a launcher icon, this app left out, "Any app" for an optional one; tasks with their kind in plain words). It is an inline list, not a pop-up: the first version used an `AlertDialog` and the three tests that opened it failed with "Compose did not get idle" (run 39/40, cause unknown, never seen on a phone) while the drop-down menus passed. Not built: apps without a launcher icon cannot be picked (no typed fallback, by the no-package-names rule), *Copy text* into a value, a "Use from a script" snippet, editing a macro after saving it |
| D | Python `macros` module: list, read, **set inputs**, run | M | **`run` with inputs done** (run 35). `list` and `read` are not built |
| E | Agent tool `run_task` (+ allowlist of tasks the user ticks) | M | **E1 done** (run 52, `runtime/agent` 175 → 206 tests green; the owner lifted the `AGENTS.md` freeze for this): the 19th tool; a ticked task is allowed for that run only, under its own grants (`ToolPolicy` rule 2b); the allowed tasks ride the goal text and the tool is hidden when none is allowed; it waits up to 90 s (cap 120 s) for the child's `{code, data}`. **E2 done** (run 54, 1731 tests green, +17): the chat's setup panel has *Saved tasks the agent may start* (cap 12; remembered with the chat; a deleted task drops out; only tasks that exist at send time are handed over; hidden until the person has a saved task). **E3a done** (run 56, 1737 tests green, +6): stopping a run (the chat's Stop, a script's Stop, Stop everything) also stops the saved tasks it started, but only when it ends *cancelled*: a run that finishes or fails by itself leaves them running, because a script may start a macro and exit on purpose. The bookkeeping and the provider's hand-over of the asking run are unit tested; the wiring in `AppGraph` is not. **E4a done** (commit 9430184; run 59 hit an outside Maven Central 429 and has no `app` results; run 60, the next commit, is the proof: 1758 tests green, `runtime/agent` 211 → 227, `app` 512): `run_task` takes an optional `inputs` (up to 8 `name=value` texts), only names the task declares are accepted, the approval question lists the values, and a call with values is asked every time. **E4b done** (run 61, 1788 tests green, `app` 512 → 542, +15 tests run in both flavours): when the person ticks a macro in the chat, the names it takes are read from its own flow file and put in the run's config, so the agent is told which values each task takes and the approval shows them; scripts and installed tasks declare none, so they still take no values. **E3c done** (run 73, 1859 tests, `runtime/agent` 264 → 273): a 20th tool, `task_status`, waits for a task that `run_task` left running, by the `run_id` it gave back. **Not built:** the observation is withheld when a macro leaves the agent's target apps; the TOCTOU re-check is skipped for tasks. **Contract audit, A2 to A13** (runs 62 to 77, 1788 → 1866 tests green; run 68, a docs-only commit, had one flaky UI-test failure, and run 76 was a compile error of my own in the new end-to-end test, see STATUS): the agent's hand-offs to the phone services and to the model were read side by side with the other side, and eleven real bugs that the test fakes had hidden were fixed, each with a test in the provider's real shape: the result shape (A3, `AgentEnvelopes`), tap and typing by screen-item number (A4), words as a structured selector (A5), the name of the approval answer (A6), a `wait` that really waits (A7), the keyboard flag (A8), `ask_user` choices as a real list (A2, run 69), a 120 s window for a question so the run's 180 s idle limit cannot end it first (A9, run 70), and a Gemini 3 or 4 tool call's `extra_content` (thought signature) sent back unchanged (A10, run 71; from Google's documentation, not tried against a live key), the profile's model id on the wire instead of the loop's placeholder name (A12, run 74), and the model call's time limit read as a duration instead of being subtracted from the epoch clock, which had refused every call before it left the phone, Test connection included (A13, run 75). A JVM test now runs the real wiring end to end against a played model and a played phone (A11, run 77, `AgentWiringEndToEndTest`). Never seen on a phone; no real provider has received the tool list |
| F | Agent chat tab: type what you want, see steps, Stop, approvals | M–L | **F1 done** (run 43, 1656 tests green, +87 new): the Agent tab is a conversation. A message starts the same supervised run the Ask form started (`AgentRunStarter`, same preflight); the run store's events become plain lines (a tool becomes a phrase such as "Opening an app", a package name in the agent's own words becomes the app's name, a failure shows the catalog text for its reason code and never an exception message); goals are remembered (50, newest last) with the apps and model used last; Stop is one tap; a setup strip asks for a model first (and AI switched on), then the apps the agent may use; "Open details" opens the run screen and Back returns to the chat. Failure texts come from `schemas/errors.json` through a generated `ReasonCopy` that a test compares with the catalog. **F2, not built:** answering the agent's questions inside the chat (they still arrive as a separate prompt screen and a notification), a follow-up message that carries the earlier answer into the next goal, a step's screenshot (G/H). **F2a done** (run 46, 1691 tests green, +18 new): a refusal, or a failed run, shows a button with the catalog's words when the catalog names a fix the chat can carry out (open AI settings, open accessibility settings, review permissions); the catalog's other fixes draw no button. Run records live in memory, so after the app is closed the chat keeps the goals and says "the details of this run were not kept" |
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
