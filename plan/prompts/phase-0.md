# Phase 0 — Feasibility spikes (kickoff prompt)

You are the implementing agent for AutoHost. Read `AGENTS.md`, then `plan.md` (§0–§5, §18 Phase 0), `docs/REFERENCES.md` §0–§1, `docs/SPIKES.md`, `docs/DEVICES.md`, `docs/GATES.md` (Gate 0).

## Goal
Test the riskiest assumptions **on real hardware** before any production code exists, and record measured results. Nothing built here is kept: spike code lives under `spikes/` and is deleted at Gate 1.

## First, ask the human (once) and record the answers in `docs/DEVICES.md`
Primary phone (model, Android version, OEM skin, `getprop ro.product.cpu.abilist`, RAM, WebView version, rooted?), any additional real devices, whether a PC with `adb` and a USB cable is available, whether Android Advanced Protection is enabled, and two or three real third-party apps they may eventually automate (used only as test targets). Then set up emulators for API 30, 33, 34 and the latest.

## Tasks (see `docs/PROGRESS.md` P0-01 … P0-32)
- **S1 Python runtime:** Chaquopy + `requests`; two worker processes (`:w0`, `:w1`); AIDL round trip (latency); kill one worker, main survives; vendored pure-Python import at runtime; phone ABI (check 32-bit); arm64/x86_64; 16 KB-page emulator.
- **S2 Accessibility reality:** enable the service on the phone; read another app's tree; click, set text, scroll; simulate a wedged read; **MediaProjection screenshot with the consent dialog auto-accepted by the accessibility service** (record every dialog variant, labels and view ids); launch from accessibility context / FGS / overlay permission; emulator checks for API 30+ screenshots, API 33 restricted settings, Advanced Protection.
- **S3 Background survival:** exact alarm → foreground service → 10-minute run (a service declaring `specialUse` must start foreground on API 29 with the two-argument call); keep-awake watchdog with the screen off and a recovery activity; Doze + reboot; 6-hour unattended 10-minute cadence; emulator checks API 31/33/34.
- **S4 Shell tier:** `adb tcpip 5555` + our own client connecting to `127.0.0.1:5555` with one-time key authorization; `input`, `uiautomator dump`, `screencap`, `am force-stop`; `pm grant … WRITE_SECURE_SETTINGS` then self-enable the accessibility service (survive reboot); **test whether a UiAutomation connection suppresses the accessibility service**; review ADB library licences (Apache/MIT only; clean-room otherwise); Shizuku PC-started mode (optional); wireless pairing only if an API 30+ device is available.
- **S5 Text input and IME:** a minimal `InputMethodService` committing Unicode/password text; background clipboard read via the IME; selecting the IME with secure settings; compare `ACTION_SET_TEXT` vs IME vs paste.
- Decide the **app licence**; write ADRs for every fallback chosen.

## Rules
- Measure, don't guess: record numbers, device, API level and commit for each result in `docs/SPIKES.md`.
- Clean-room: do not copy code from Mobilerun Portal (AGPL) or OpenAutoJs (GPL+NC). Ideas only (`docs/REFERENCES.md`).
- Any step needing a physical action (enable a service, tap a prompt, plug in USB): ask the human, then continue.
- Update `docs/PROGRESS.md` as you go; run `python tools/progress.py` and `--check`.

## Done when
All five spike verdicts are recorded, `DEVICES.md` (with the UNVERIFIED-on-hardware list) and `DECISIONS.md` are updated, and Gate 0 (`G0-a … G0-e`) is ticked **with evidence**. Report: a one-page summary of each verdict, fallbacks chosen, and any change you recommend to `plan.md`.

## Do not
Start production modules; write Gradle multi-module scaffolding; pick libraries without a licence check; skip the Android 10 phone and rely on emulators.
