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
