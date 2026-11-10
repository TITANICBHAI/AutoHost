# S11 — Home screen, polish, release 0.1 alpha. Branch `work/s11-release`.
1. **Home + 4-tab bar + active-runs bar** per `docs/UI_SPEC.md` S01/§3: tabs *Home · Tasks · Schedules · Settings*; Home = health banner, ACTIVE NOW (Stop), NEEDS YOU, UP NEXT, RECENT RUNS → See all (`HomeUiState` as in UI_SPEC §6, fed by `TaskCatalog`, the run store, the schedule engine, health). Active-runs bar above the tab bar hosting the existing `RunCapsule`.
2. **Settings regroup:** General (Setup & health, Backup & restore, About) · Shortcuts & widgets (Surfaces) · AI (Labs) · **Developer** (switch from S05: scheduler backend/guard, shell tier, Relaxed mode, Dev API + token, webhooks, reverse bridge, app packs, Inspector, Studio, JSON schedule editor).
3. **Icon + colours:** adaptive icon (`res/mipmap-anydpi-v26/ic_launcher.xml` with background, `ic_autohost` foreground, `<monochrome>` for Android 13+); dynamic colour on API 31+ for surfaces and `primary` **only** — the status colours never change (UI_SPEC §2).
4. **Accessibility:** UI_SPEC §8 — TalkBack labels on every control, 48 dp targets, font scale 200 %. Today 12 `contentDescription` in the whole app. Robolectric Compose test at `fontScale = 2f` opening Library, task page, Schedule form, Setup.
5. **Check for updates** (About): one HTTPS GET to the GitHub Releases "latest" endpoint of the owner's repo, compare `tag_name`, show **Open release page**. No download/install inside the app.
6. **Docs:** rewrite `docs/USER_GUIDE.md` for people (no ids/JSON); 5 troubleshooting entries (can't enable accessibility, stops in the background, schedule starts late, run stuck, script fails); `SECURITY.md` contact line.
7. **Walk the "ready to use" checklist below**; mark each box with evidence or "not yet". Then a GitHub Release draft: APK, SHA-256, known limits.
## "Ready to use" checklist (tick only with evidence)
Install & update: stable key; two CI APKs install over each other and tasks survive · `versionCode` rises · Release has APK + SHA-256 + known limits · README has the restricted-settings steps.
First five minutes: fresh install → *Hello* green with no permissions · Accessibility on using only the in-app guide · *Settings reader* and *Press button* run.
Daily use: every task (example/file/folder/pasted/shared/recorded) in **one** list · add by file/folder/paste · schedule by form, right local time, survives reboot · Stop from notification, bubble and volume hold · share + import on a second install · **backup → uninstall → reinstall → restore brings tasks back**.
Trust: Permission Review mandatory before first run · AI off by default · canary-secret test covers diagnostics · no file I/O or `runBlocking` on the main thread (StrictMode log clean on the phone).
Quality: all 32 modules' tests pass · launch smoke green · Back, rotation, dark mode, font 200 %, TalkBack labels on the golden path · no developer word on a normal screen.
Reliability: runs survive swipe-away and reboot · OEM guide matches the owner's two phones (written from what happened) · Copy diagnostics pastes into a bug report.
Docs & support: USER_GUIDE for people · 5 troubleshooting entries · issue template.
This is a practical subset of plan §22. Plan Gate 11 / F1 — *"a brand-new task … installed, profiled and scheduled with no Kotlin changes"* — is what S05 + S09 let the owner do on the phone without a PC.
**Gate:** every box has evidence or an honest "not yet" in the release notes. Call it **0.1 "ready to use" (beta)**, not "finished".
