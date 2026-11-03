# LOCAL_INFERENCE — optional on-device models (Phase 16; NO-GO for agent loops until spike S6 passes)

*Status: PLAN. Static reading of reference projects plus the research notes of 2026-10-25/27; **no model was loaded, no library was built, nothing ran on a phone.** Every number marked placeholder is to be replaced by the S6 measurement. Decision record: ADR-092 (D-16). Phase 16 can be dropped without affecting Phases 12–15.*

## 1. Decision

1. The default model path for agent runs is a **remote OpenAI-compatible endpoint** (ADR-090, `docs/MODEL_GATEWAY.md`). The "cheap on CPU" goal is met without any on-device inference.
2. Local inference is a **separate, optional capability**, off by default, behind a `LocalModel` seam in `:core:llm`, running in an **isolated `:llm` process** so a native crash or an out-of-memory kill cannot take the app or a run down.
3. **NO-GO for agent loops** (a model that sees screen dumps and chooses actions) on the owner's phones until **spike S6 passes on both** (Realme C3, Galaxy M31; ADR-095). A pass for *helper use* (classification, extraction, short single-step planning of capability calls) does not unlock agent loops.
4. A recorded NO-GO is a valid outcome of G16-a and G16-d.

## 2. Why the default is NO-GO (evidence, all static)

| Fact | Source | Tag |
|---|---|---|
| opendroid's catalogue lists on-device models with context windows of 1,280–4,096 tokens (Qwen2.5-0.5B q8 ekv1280: 1,280; Gemma 4 E2B-it and E4B-it: 4,096) and file sizes of 0.55–4.9 GB (E2B-it 2,588,147,712 B) | `scratchpad/notes/opendroid_findings.md` (read from opendroid `ModelRepository`/catalogue) | static reading |
| A system prompt, tool schemas and one screen observation already take ~1–3 K tokens (`docs/AGENT_RUNTIME.md` §7.3 budgets: `max_chars` 12,000 ≈ 3 K tokens by the ÷4 estimate — placeholder) | AGENT_RUNTIME §7.3, §16 | PREDICTED |
| opendroid's catalogue marks the Gemma 4 entries `minSdk 31`; the Realme C3 is API 30 | opendroid findings; `docs/DEVICES.md` | static; LiteRT-LM's own API floor **UNVERIFIED** |
| opendroid gates models by TOTAL RAM (≥ 3 GB file needs 8 GB, ≥ 2 GB needs 6 GB, ≥ 1 GB needs 4 GB); the owner's RAM sizes are unverified (C3: 2/3/4 GB, M31: 6/8 GB) | opendroid findings; DEVICES | UNVERIFIED |
| opendroid's own QA plan records an emulator run killed by `mem-pressure-event` **before any token**; "everything below section 4 is untested, not passing" | opendroid `docs/qa/qwen-litert-test-plan.md` via findings | static; later releases claim fixes, unverified |
| opendroid's known defect D-2: a native SIGABRT (uncatchable) when a prompt exceeds the engine's `maxNumTokens`; mitigated by a chars/4 pre-check | opendroid findings | static |
| opendroid's provider creates a new conversation per request (no KV-cache reuse across steps → full prefill each step) and does tool calling by prompt-engineered JSON | opendroid findings | static |
| ARIA's `llama.cpp` directory is empty and its engine is a stub that clicks node #1 | ARIA audit | anti-reference |

Conclusion: a screen-driven agent loop on these devices is **unproven and probably infeasible**; a small helper model may be feasible. S6 decides with numbers.

## 3. Candidates (all UNVERIFIED until S6)

| Candidate | What is known | What is not |
|---|---|---|
| **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`, 0.14.0 in opendroid) | Engine/Conversation API; GPU and CPU backends; artifact ships `x86_64` + `arm64-v8a` only (per opendroid's QA doc); model formats `.litertlm` / `.task` | its licence (UNVERIFIED), minimum API, 16 KB status, GPU behaviour on the owner phones, behaviour under memory pressure |
| **llama.cpp** (MIT, "Copyright (c) 2023-2026 The ggml authors", LICENSE read 2026-10-25) | An Android binding in `examples/llama.android` and Termux instructions in its Android doc (`android.md` under its `docs` folder); CMake build; hardware detection up to SME2 on Arm | whether its Android binding is maintained for our use, JNI wrapper effort, 16 KB alignment of what we would build, model/licence choice |
| Others (MediaPipe LLM Inference, ONNX Runtime, ML Kit GenAI Prompt — proprietary, **not adopted**) | not studied | everything |

A clean-room llama.cpp JNI fallback (P16-06) is built **only if** LiteRT-LM fails S6 and a helper use still matters. No llama.cpp code is vendored by this pass.

## 4. Design if S6 passes

- **Process isolation:** a bound service in its own `android:process` (`:llm`); the host talks to it through a narrow AIDL/Messenger interface; killing or OOM-ing it yields a typed `LOCAL_MODEL_*` failure and leaves the app and its runs alive (G16-c).
- **`LocalModel` seam** in `:core:llm`: `load(artifact)`, `generate(request, budget)`, `unload()`; non-streaming first (same wire-neutral types as the remote gateway); tokenizer-accurate prompt budgeting (adapt RM-E3: a real token count, not chars/4) so a prompt can never exceed the engine's limit.
- **RAM gate on AVAILABLE memory** (`ActivityManager.MemoryInfo.availMem`, `lowMemory`) at load time — never on total RAM — with a margin recorded by S6 (placeholder).
- **Thermal:** `PowerManager` thermal status listener (API 29) pauses or refuses new local calls at a level recorded by S6 (P16-05).
- **Model artifacts:** adapt opendroid's integrity pipeline (RM-E1 manifest + verifier + atomic install, SHA-256 before native load; RM-E2 resumable download worker with size cap and `noBackupFilesDir`; foreground-service type for downloads is OPEN — Android 15's `dataSync` limits are **UNVERIFIED**, decide in `docs/ANDROID_MANIFEST.md`). Weights are **never bundled** (RM-E9); gated models need the user's own licence acceptance in the UI (P16-07) — this document asserts no model's licence terms.
- **Selection policy (P16-05):** local models are used only for READ_ONLY / REVERSIBLE helper work; anything the broker classifies as irreversible goes to the remote model or the user. The agent runtime never selects a local model for a loop unless S6's agent criterion (§5 criterion 6) passed.
- **Privacy:** local inference keeps screen text on the device; the data-egress disclosure (plan §14.7) does not apply to local-only runs.

## 5. S6 criteria (authoritative; `docs/SPIKES.md` S6 copies them)

Run on **both** owner phones with a model small enough to try (record file size, quantisation, context length and licence status). All thresholds are **placeholders** until the first measurement; the owner confirms them.

1. **Load/unload:** 10 consecutive load → 3 prompts → unload cycles complete with no process kill; record peak PSS and `availMem` before load.
2. **Isolation:** force-kill `:llm` during generation; the app process survives and reports the typed failure.
3. **Latency:** time-to-first-token and tokens/s at 1 K, 2 K and 4 K prompt tokens; record median and worst of 5 runs each.
4. **Thermal:** a 10-minute continuous run; record the thermal status reached (placeholder limit: never SEVERE or worse).
5. **Helper quality:** 10 fixed extraction/classification prompts with known answers (placeholder: ≥ 8/10 correct, no malformed JSON when JSON was requested).
6. **Agent criterion (separate verdict):** a 5-step fixture task at the real observation size (`AgentView` output) completes with the on-device model inside the agent budgets, with the same safety envelope. Failing this keeps agent loops NO-GO even if 1–5 pass.

Verdicts: PASS (helper) / PASS (helper + agent) / FAIL (record why) / NO-GO (a hard floor such as API level or RAM). Evidence names the device and Android/API level.

## 6. Tasks and gates

Tasks P16-01…P16-08 and gates G16-a…G16-d live in `docs/PROGRESS.md` (Phase 16); procedures in `docs/GATES.md` (Gate 16). Reuse rows: `docs/REUSE_MATRIX.md` §E (RM-E1 integrity — adapt; RM-E2 download worker — adapt; RM-E3 prompt budget — adapt; RM-E4 LiteRT provider — reference; RM-E5 LiteRT-LM — defer; RM-E6 llama.cpp — defer; RM-E7 ARIA native — do not adopt; RM-E8 QA-plan structure — adapt; RM-E9 weights not bundled).

## 7. Unknowns (do not treat as facts)

LiteRT-LM licence, minimum API and ABIs beyond opendroid's statement; llama.cpp binding maintenance; per-device RAM and thermal behaviour; whether any model that fits the owner's phones can drive a UI at useful quality; Android 15+ foreground-service limits for downloads; the exact S6 thresholds.
