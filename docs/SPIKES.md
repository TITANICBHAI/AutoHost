# SPIKES — Phase 0 results
Spike code is throwaway under `spikes/`. Record **measured** results, not impressions.

## Template (copy per spike)
### S? — title
- **Question:**
- **Setup (device, API, build, commit):**
- **Procedure:**
- **Results (numbers):**
- **Verdict:** PASS / PASS-WITH-FALLBACK / FAIL
- **Fallback chosen (if any):**
- **Evidence (paths/links):**
- **Follow-ups / ADRs:**

---
### S1 — Python runtime (Chaquopy, multi-process)
### S2 — Accessibility reality (read, act, MediaProjection + auto-accept, launch chain)
### S3 — Background survival (exact alarm → FGS, keep-awake, Doze, reboot)
### S4 — Shell tier (ADB-over-TCP, secure settings, UiAutomation exclusivity)
### S5 — Text input and IME

---

## Phase 10 spikes (written with the code, measured at the phone — plan §18 P10)

These four run **before** any browser/vision capability is trusted on hardware. The code ships with
seams so the spike can swap implementations without touching providers. Until measured, every
browser/vision availability stays honest (`UNVERIFIED-on-hardware` in `docs/DEVICES.md`), and the
providers already behave correctly for the "engine missing / old WebView" outcomes.

### S25 — WebView automation with real input events
- **Question:** can the in-app WebView browser be driven with *real* MotionEvent streams (not
  `evaluateJavascript`-dispatched synthetic events) at usable latency on the Android 10 phone?
- **Setup (device, API, build, commit):** Android 10 phone, recorded WebView version
  (`DEVICES.md`), debug build with the P10 browser module, fixture page
  `samples/web-login-scrape/web/login.html`.
- **Procedure:** 1) load fixture page; 2) inject tap on the submit button via
  `Activity.dispatchTouchEvent` (down → 2 move frames → up, 90–130 ms total); 3) assert the page's
  JS click counter increments (real trusted event) and navigation happened; 4) repeat ×50, record
  p50/p95 dispatch→settle latency; 5) repeat with `input`-pipeline disabled to record the
  JS-dispatch baseline for comparison; 6) type into the username field with real key events
  through `dispatchKeyEvent` and confirm the site's own listener sees them.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (fails ⇒ WebViewBrowserProvider falls back to JS-dispatched events marked
  `input_mode:"synthetic"`, honest in every result; provider code already supports both)
- **Fallback chosen (if any):** JS-dispatch with `trusted:false` flag — visible in results, never
  claimed as real input.
- **Evidence (paths/links):** PENDING-ON-HARDWARE
- **Follow-ups / ADRs:** ADR-070 (browser provider chain), Gate 10-a run export.

### S26 — Per-profile cookie isolation
- **Question:** does `WebView.setDataDirectorySuffix` (API 28+) actually isolate cookies AND
  localStorage between two profiles inside one process on the phone's WebView version?
- **Setup:** same build; two profiles "p1"/"p2"; fixture page that renders `document.cookie` and
  a localStorage counter.
- **Procedure:** 1) open fixture with p1, set cookie `k=1` via JS + increment LS; 2) close page;
  3) open with p2, read cookie + LS; 4) assert empty/zero; 5) reopen p1, assert `k=1` survived;
  6) kill process, reopen p1, assert persistence; 7) run CookieManager.getCookie globally from
  the host process to confirm the host cannot see profile cookies without the suffix.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (fails ⇒ per-profile isolation degrades to "shared jar + documented",
  `browser.webview` availability reason gains `PROFILE_ISOLATION_SHARED` and tasks that need
  isolation get a CompatReport row — honest, no silent sharing)
- **Fallback chosen (if any):** document shared-cookie mode; profiles still separate browser
  state in app logic only.
- **Evidence (paths/links):** PENDING-ON-HARDWARE
- **Follow-ups / ADRs:** ADR-070; `browser.cookies` results carry `profile` always.

### S27 — CDP over an ADB stream
- **Question:** does the local-adbd route (`adb forward tcp:<p> localabstract:<socket>` executed
  through our own shell transport, then a plain TCP socket to 127.0.0.1:<p>) reach the devtools
  socket of (a) our own debuggable WebView and (b) the user's Chrome, on Android 10?
- **Setup:** shell tier paired (Gate 9 path); `WebView.setWebContentsDebuggingEnabled(true)`
  build flag for (a); Chrome stable for (b).
- **Procedure:** 1) run `adb forward` via ShellProvider; 2) GET `http://127.0.0.1:<p>/json/list`;
  3) open the WebSocket target; 4) `Page.navigate` + `Runtime.evaluate "1+1"`; 5) record round-trip
  latency ×20; 6) repeat for `localabstract:chrome_devtools_remote`; 7) verify forward cleanup
  (`adb forward --remove`) and that no socket survives a run-terminal.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (route (a) failing ⇒ `browser.cdp` reports NEEDS_SETUP(WEBVIEW_DEBUGGING_OFF);
  route (b) failing ⇒ user-browser CDP marked UNSUPPORTED, browser.cdp stays our-WebView-only —
  the provider already separates the two modes)
- **Fallback chosen (if any):** browser.cdp limited to our own WebView; browser.a11y covers the
  user's browser read/act path.
- **Evidence (paths/links):** PENDING-ON-HARDWARE
- **Follow-ups / ADRs:** ADR-071.

### S28 — OCR/template match on-device
- **Question:** what do ML Kit text-recognition (bundled AAR) and a pure-Kotlin NCC template
  match cost on the Android 10 phone (32-bit, low-RAM) for a full screenshot and a 200×60 region?
- **Setup:** same build; `samples/ocr-demo` fixture images under `testing/fixtures/vision/`.
- **Procedure:** 1) OCR full 720p screenshot ×10 — p50/p95 ms + RSS delta; 2) OCR 200×60 region
  ×10; 3) NCC match of a 60×28 needle over 720p haystack (stride 4, then refine) ×10 — ms +
  match score on a true positive and a true negative; 4) ZXing decode of a rendered QR ×10;
  5) memory before/after each block (worker-recycled).
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (NCC too slow at stride 4 ⇒ adaptive stride/early-exit documented and
  defaults lowered; OCR too slow ⇒ region-only mode becomes the documented default)
- **Fallback chosen (if any):** smaller default region caps; `max_ms` budget argument that
  downgrades to TIMEOUT honestly.
- **Evidence (paths/links):** PENDING-ON-HARDWARE
- **Follow-ups / ADRs:** ADR-072 (vision engines + licences); PERF.md row.

---

## v4 spikes (planned 2026-11-01 — measured at the phone; nothing has been run)

### S6 — Local inference feasibility (Phase 16; gates the whole of Phase 16 and any on-device agent loop)
- **Question:** can a small on-device model run safely and usefully on the Realme C3 (Android 11 / API 30) and the Galaxy M31 (Android 12 / API 31), isolated in an `:llm` process — and is it good enough for helper use or for a UI-agent loop? (Decision: ADR-092; design and evidence: `docs/LOCAL_INFERENCE.md`.)
- **Setup:** debug build with a throwaway harness under `spikes/s6-local-inference/` (not on any Gradle source set); a LiteRT-LM engine in a bound service with `android:process=":llm"`; one small model per phone chosen by the executor (record file name, size, quantisation, context length, licence status); both phones charged, screen on, no other heavy apps; `adb shell dumpsys meminfo`, `dumpsys thermalservice`, `getprop ro.build.version.release`.
- **Procedure:** the six criteria of `docs/LOCAL_INFERENCE.md` §5, verbatim: (1) 10 load → 3 prompts → unload cycles, peak PSS and `availMem` before load; (2) force-kill `:llm` during generation, app survives with a typed failure; (3) time-to-first-token and tokens/s at 1 K/2 K/4 K prompt tokens, median and worst of 5; (4) 10-minute continuous run, thermal status reached; (5) 10 fixed extraction/classification prompts, correct count and malformed-JSON count; (6) a 5-step fixture task at the real `AgentView` size inside the agent budgets (separate verdict). Structure borrowed (ideas only, attribution in `THIRD_PARTY_NOTICES.md` if the QA text is adapted): opendroid's QA plan groups checks into environment gate, acquisition, inference, failure and switching.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (PASS helper / PASS helper+agent / FAIL / NO-GO; thresholds are placeholders to be confirmed by the owner)
- **Fallback chosen (if any):** FAIL or NO-GO ⇒ Phase 16 stays unbuilt (or only the clean-room llama.cpp fallback P16-06 is tried); the agent keeps using the remote gateway.
- **Evidence (paths/links):** PENDING-ON-HARDWARE (`device: <model>, Android <n> / API <n>` on every line)
- **Follow-ups / ADRs:** ADR-092; tasks P16-01…P16-08; gates G16-a…G16-d.

### S7 — Overlay indicator and glow: cost and touch-through (Phase 14)
- **Question:** what do the run capsule overlay and the optional glow cost in frame time and battery on the owner phones, and do they ever swallow injected or real touches meant for the app below?
- **Setup:** debug build; capsule overlay with the two-window rule (REUSE_MATRIX RM-F4: one touchable window for the capsule, one non-touchable for decoration); fixture app with a scrollable list and a button under the capsule's position; `adb shell dumpsys gfxinfo <pkg> framestats`.
- **Procedure:** 1) scroll the list for 60 s with the capsule hidden, shown, and shown with glow — frame-time p50/p90/p99 and jank count each; 2) inject a tap at a point under the glow region and under the capsule's non-touchable window — the button under it receives it; 3) the capsule's own Stop button receives a real touch while an injected gesture runs; 4) battery/thermal note over a 10-minute run with glow on (qualitative unless `dumpsys batterystats` is read); 5) TalkBack: capsule state is announced, Stop reachable.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (glow stays default-off regardless; a measurable jank or touch loss ⇒ glow removed, capsule kept)
- **Fallback chosen (if any):** capsule without glow; status-bar notification only.
- **Evidence (paths/links):** PENDING-ON-HARDWARE
- **Follow-ups / ADRs:** ADR-094; tasks P14-01…P14-09; gates G14-c, G14-d.

### S8 — Agent feasibility: does one supervised "Ask" run work end to end? (Phase 13; run BEFORE P13-10)
- **Question:** through the planned path (compact observation → model tool call → broker action → host-verified completion), does a supervised agent run finish simple tasks on the owner's phones, at what success rate, latency, token count and cost — and does the BLOCKED-app denial behave on the owner's real installed-app list?
- **Setup:** debug build; the stub model first (deterministic scripted tool calls), then one cloud model of the owner's choice through the gateway of `docs/MODEL_GATEWAY.md` (key in `SecretStore`, `ai_cleartext_loopback` off); the fixture app or Settings; Realme C3 (API 30) and Galaxy M31 (API 31) as available. Run it after Epoch V and the Phase 12 action fixes, so the actions measured are the hardened ones (`docs/ACTION_HARDENING.md`) and the spike does not measure defects A1–A9 instead of the agent.
- **Procedure:** at least 10 single-step runs per model per phone; per run record the host-verified outcome, steps, wall time, input and output tokens, and cost; then the denial test: ask the agent to open each app of the owner's real installed-app list that the tier policy marks BLOCKED and record false positives and false negatives; compare every measured number with the placeholder budgets in `docs/AGENT_RUNTIME.md`.
- **Results (numbers):** PENDING-ON-HARDWARE
- **Verdict:** PENDING (thresholds are placeholders; the owner confirms them. A FAIL reduces Phase 13 to the gateway, approvals and stub runs, or stops it — the owner decides)
- **Fallback chosen (if any):** the agent stays off; scripts and flows are unaffected.
- **Evidence (paths/links):** PENDING-ON-HARDWARE (`device: <model>, Android <n> / API <n>` on every line)
- **Follow-ups / ADRs:** task P13-20; feeds G13-e; ADR-091.

