# S07 — First run, the "restricted settings" guide, plain failure messages. Branch `work/s07-first-run`.
Plan §22: *"Setup & Health takes a fresh device to 'ready' without outside instructions."* For a sideloaded app the first blocker is Android's restricted-settings rule (REF 26).
1. **`ReasonCopy`** (new, ~60 lines, tested; CONVENTIONS already names it, it does not exist): bundle `schemas/errors.json` into app assets with a Gradle `Copy` task (no hand copy → no drift), parse with kotlinx-serialization,
   `ReasonCopy.message(code)` / `fixAction(code)`. Tests: every reason code parses; every `fixAction` of every reason code is a known fix-action id.
2. **Fix-action completeness** — `schemas/errors.json` has 23 fix-action ids; `FixHub.kt` handles 8, **4 under another spelling**, **11 not at all**:
   | errors.json id | today | do |
   |---|---|---|
   | OPEN_NOTIFICATION_ACCESS_SETTINGS / OPEN_EXACT_ALARM_SETTINGS / REQUEST_BATTERY_EXEMPTION / OPEN_STORE_PAGE | handled as OPEN_NOTIFICATION_LISTENER_SETTINGS / OPEN_EXACT_ALARM / BATTERY_EXEMPTION / OPEN_STORE | accept both spellings |
   | OPEN_APP_INFO_UNUSED_SETTINGS | missing | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`, `package:app.autohost` |
   | OPEN_OVERLAY_SETTINGS | missing | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` |
   | OPEN_USAGE_ACCESS_SETTINGS | missing | `Settings.ACTION_USAGE_ACCESS_SETTINGS` |
   | REQUEST_POST_NOTIFICATIONS | missing | runtime permission `POST_NOTIFICATIONS` via the existing permission activity |
   | REQUEST_PROJECTION_CONSENT | missing | the existing projection consent request |
   | OPEN_AI_SETTINGS | missing | in-app route to AI settings |
   | SHOW_PC_COMMAND_SHELL / SHOW_PC_COMMAND_SECURE | missing | in-app text card with the copyable command |
   | **SHOW_RESTRICTED_SETTINGS_HELP**, **EXPLAIN_ADVANCED_PROTECTION**, **SHOW_OEM_GUIDE** | missing; **nothing emits the first one** | new in-app screens (step 3) and make the Accessibility setup path emit the first when `SDK_INT ≥ 33` and the service is off |
   Add a unit test that loads `errors.json` and fails if any non-`NONE` fix-action id is not handled by `FixHub` (this is what drifted).
3. **Screens (plain words, strings.xml):**
   - *Welcome* (empty list): "Let's run your first automation — it needs no permissions." → creates **Hello**, runs it, shows the green result and its log line (also the first proof the runtime works).
   - *Turn on Accessibility*: **(1)** [Open AutoHost's app info] → **(2)** "Tap ⋮ (top right) → **Allow restricted settings**" → **(3)** [Open Accessibility settings] → "choose AutoHost, turn on" → **(4)** [Check again] (poll the service state).
     Why: *"Android blocks this switch for apps that did not come from an app store, until you allow it once."* **[menu path from memory — confirm on the owner's phone]**.
   - *Advanced Protection* card; *OEM guide*: pick the row by `Build.MANUFACTURER` from `docs/OEM_MATRIX.md` (Samsung, Xiaomi/HyperOS, Huawei, Oppo/Realme, OnePlus, Vivo, stock). **Every row is still `PENDING-ON-HARDWARE`** → label the card "not yet tested on this phone model".
4. **Failure card (UI_SPEC §7):** extract the issue renderer from `HealthScreen` (title, `userMessage`, **Fix** button) as `ReasonCard`; show it on the Run screen when a run ends FAILED / NEEDS_SETUP, and use the same text in the failure notification. Technical detail goes under *Details*.
5. **Print the phone checklist** (below) in plain words for the owner and say exactly what to send back.
## Phone checklist (the golden path; each step needs less than the next)
1 Install the CI APK, open it; Setup shows the grants · 2 *Hello* from Examples → Run (runtime, broker, live log, history) · 3 *HTTP check* · 4 turn on Accessibility with the in-app guide; *Settings reader* ·
5 *Press button* on a real app · 6 notification **Stop** and the kill switch mid-run · 7 schedule *Hello* every 15 min with the new form, reboot the phone · 8 force a failure, open the failure card, **Share** the report ·
9 (if S09 landed) add a 3-line `.py` that prints and run it. Send back: a screenshot per step, the Setup screen, and the *Copy crash report* text if one appears.
**Gate:** CI green with the new tests; checklist printed.
