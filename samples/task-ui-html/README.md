# Task UI (HTML) — Tier C sample

A task whose detail screen hosts a **sandboxed WebView** (plan §10.1 Tier C, UI_SPEC S16).

## What it demonstrates
- `ui/index.html` + `ui/app.js` + `ui/app.css` shipped inside the task bundle; the manifest declares
  `"ui": {"mode": "html", "file": "ui/index.html"}`.
- The **JS bridge**: `autohost.invoke(handler, argsJson)` calls a `@ui.handler` in `main.py` through
  the `:ui` worker (30 s cap) and returns the outcome JSON synchronously;
  `autohost.state()` reads the host-side UI state map; run-status pushes arrive via
  `window.autohostEvent("run", {status: ...})`.
- **Sandbox**: the host denies every non-`file:` request (no network), refuses navigation off the
  bundle, and only serves paths inside this task's directory. The page's strict CSP
  (`default-src 'none'`) is defense-in-depth on top of the host boundary.

## Files
- `task.json` — manifest with `ui.mode = "html"`.
- `main.py` — `increment` / `reset` handlers + a trivial `main()` (a run = one click).
- `ui/` — the page, script and stylesheet.

## Try it
1. Copy the folder to the dev folder (`adb push task-ui-html /sdcard/Android/data/app.autohost/files/dev/`).
2. Open **Tasks → Task UI (HTML)** → the **UI** tab.
3. Tap the buttons — the count lives in `clicks.json` beside `main.py`.
