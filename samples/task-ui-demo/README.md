# Task UI Demo — Tier B declarative sample

A task whose detail screen hosts the **declarative UI renderer** (plan §10.1 Tier B, UI_SPEC S16):
`ui/main.json` describes the screen; AutoHost renders it — no HTML, no script.

## What it demonstrates
- **Widgets**: text, markdown, card, row, input, button (filled/outlined/danger), stat, table,
  progress, logview — the frozen v1 set.
- **Bindings**: `{{config.greeting_name}}` (task config), `{{run.status}}` (latest run),
  `{{input.who}}` (the renderer's input widgets), `{{state.*}}` (handler-published state patches).
- **Actions**: `call(handler, args)` → a `@ui.handler` in `main.py` runs in the `:ui` worker
  (30 s cap) and returns a dict; a reserved `"state"` key in that dict updates `{{state.*}}` live.
  `run` starts a real run; `navigate` jumps tabs; `confirm` wraps an action in a two-step dialog.
- **logview** shows the task's most recent run's `ui.console` lines (`ui.console.write` in `main.py`).

## Files
- `task.json` — manifest with `ui.mode = "declarative"`, `ui.file = "ui/main.json"`.
- `main.py` — `greet` / `add_item` / `reset` handlers + a trivial `main()`.
- `ui/main.json` — the declarative document (schema: frozen v1 widget set).

## Try it
1. Copy the folder to the dev folder (`adb push task-ui-demo /sdcard/Android/data/app.autohost/files/dev/`).
2. Open **Tasks → Task UI Demo** → the **UI** tab.
3. Type a name, tap **Greet** — the stat and the table update through state patches.
