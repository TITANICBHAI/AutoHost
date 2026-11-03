# MODEL_GATEWAY — the host's model client (`:core:llm`, Phase 13 Stage A)

> **Status: DESIGN — nothing here is built or verified.** Decision record: ADR-090 (`docs/DECISIONS.md`). Consumer: `docs/AGENT_RUNTIME.md`. Facts about existing code carry paths (read 2026-10-29, static only). Numbers marked **(placeholder)** are tuned later.
> Scope: a **host-internal** service. Scripts cannot call a model in Stage A; a later `ai.llm` capability (if ever wanted) would be a thin provider over this module with its own ADR.

---

## 1. Constraints this design answers (verified in this tree)

| Fact | Where | Consequence |
|---|---|---|
| No OkHttp/ktor dependency; engines use `HttpURLConnection` behind a seam | `gradle/libs.versions.toml`; ADR-090 context | the production engine is `java.net` only → it lives in the pure-JVM `:core:llm` and is tested with the JDK's own `HttpServer` |
| `INTERNET` is declared in the **main** manifest, so `core` and `full` both have it | `app/src/main/AndroidManifest.xml:6` | AI features are gated by the *user setting*, not by flavor |
| The manifest sets neither `usesCleartextTraffic` nor `networkSecurityConfig`; `res/xml/network_security_config.xml` does not exist, although `docs/ANDROID_MANIFEST.md` §2/§5 describe one | `app/src/main/AndroidManifest.xml`; ADR-096 | **PREDICTION (unverified):** at targetSdk 36 the platform default blocks cleartext, so even loopback `http` would fail until X-14 reconciles manifest and docs. The URL policy below is necessary but not sufficient |
| `SecretsProvider` is a typed NOT_FOUND stub; the flow runtime passes `secrets = emptyMap()` | `core/engine/.../host/SecretsProvider.kt`; `runtime/flow/.../FlowRuntimeProvider.kt:89` | the gateway must **not** go through the `secrets` capability; it reads the Keystore store directly (host side) |
| `SecretStore` is per-profile (`put/get(profileId, key)`), AEAD via Keystore; `SecretRegistry` holds live values **in memory only** | `core/data/.../secrets/SecretStore.kt:33-114` | a key read after a process restart is **not** in the `Redactor` unless it is registered again → the gateway registers it at load |
| `Redactor` also masks JSON keys named `api_key`, `authorization`, `token`, `secret`, `password` … | `SecretStore.kt:161` | helpful, not sufficient (a key can appear as a value anywhere) |
| Insecure-feature switches: flat ids in `InsecureFeatures.KNOWN`, persisted as `app_settings` keys `insecure.enabled` / `insecure.denied` / `insecure.relaxed` | `core/broker/.../security/InsecureFeatures.kt:18-36` | add `ai_cleartext_loopback`; the gateway reads the same state snapshot (it is **not** a broker capability, so `featureFor` is untouched) |
| `app_settings` is a key/value Room table | `core/data/.../db/Entities.kt:168` | provider profiles (non-secret) can be one JSON value; no Room migration |

---

## 2. Wire format v1 and provider-neutral types

**One protocol:** OpenAI-compatible **Chat Completions**, **non-streaming**: `POST {base}/chat/completions`.
Why non-streaming: a half-received tool call can never be executed; cancellation is `disconnect()`; no SSE parser; no partial-state bugs (android-agent keeps a partial tool call after a dropped stream — `reference/findings/android_agent.md`). Cost: no token streaming — the UI shows a "thinking" state.

Request body (fields present when applicable): `model`, `messages[]` (`system|user|assistant|tool`), `tools[]` = `{type:"function", function:{name, description, parameters(JSON Schema)}}`, `tool_choice:"auto"`, the output-limit field named by the profile (`max_tokens` **or** `max_completion_tokens`), optional `temperature`, optional `parallel_tool_calls:false` when the profile says the endpoint accepts it, `stream:false`.
Response: `choices[0].message{content, tool_calls[{id, type, function{name, arguments(string)}}]}`, `choices[0].finish_reason` (`stop|tool_calls|length|content_filter`), optional `usage{prompt_tokens, completion_tokens, total_tokens}`.

Contract sketch (not code to paste):

```kotlin
data class ChatRequest(val model: String, val messages: List<ChatMessage>, val tools: List<ToolSpec>,
                       val maxOutputTokens: Int, val temperature: Double? = null)
sealed interface ChatMessage { System(text) · User(text) · Assistant(text?, toolCalls) · Tool(toolCallId, content) }
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)
data class ToolCall(val id: String, val name: String, val argumentsJson: String, val arguments: JsonObject?) // arguments == null ⇒ the JSON was invalid
data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int, val estimated: Boolean)
data class ChatResponse(val text: String?, val toolCalls: List<ToolCall>, val finishReason: FinishReason,
                        val usage: Usage?, val latencyMs: Long, val attempts: Int)
```

Rules: `usage` is **nullable** — unknown is never zero; when the loop must account tokens it creates an estimate and sets `estimated = true`. A `ToolCall` whose `arguments` JSON does not parse is returned with `arguments = null` (the loop answers the model with a typed error; the gateway never "repairs" JSON). A response with `finish_reason = length` and tool calls is treated as `MalformedResponse` (possibly truncated arguments). Message and tool names must match `[a-zA-Z0-9_-]{1,64}`.

Native Anthropic/Gemini formats are **deferred**; models from those vendors are reached through an OpenAI-compatible endpoint where one exists (availability per provider **UNVERIFIED**). No vendor OAuth, no proprietary SDKs (ClosePaw's Codex OAuth client and Leap SDK are not adopted).

---

## 3. Provider profiles and keys

A **provider profile** (non-secret part; one JSON array in `app_settings` key `ai.providers`, P13-05):

| Field | Meaning | Default |
|---|---|---|
| `id` | stable, `[a-z0-9-]{1,32}` | generated |
| `label` | shown in UI | — |
| `baseUrl` | validated by §4 on **every** call (the cleartext switch can change) | — |
| `model` | model id string | — |
| `maxTokensField` | `max_tokens` \| `max_completion_tokens` | `max_tokens` |
| `parallelToolCallsParam` | send `parallel_tool_calls:false` | `false` (endpoint support UNVERIFIED) |
| `contextWindowTokens` | user-declared, optional (pre-flight prompt budget) | unknown |
| `extraHeaders` | non-secret only; names matching credential words are rejected | none |
| `egressAcceptedAt` | when the user accepted the data-egress disclosure for this profile | null ⇒ cannot run |
| `connectTimeoutMs` / `readTimeoutMs` | per attempt | 10 000 / 60 000 **(placeholder)** |

**API key:** never in the profile. Stored with `SecretStore.put(HOST_SCOPE, "ai.provider.<id>.api_key", key)` where `HOST_SCOPE` is a reserved profile id (`"__host__"`) that users cannot create (P13-05 validates) — `SecretStore.deleteForProfile` and `allOf(profileId)` (used to hand a *run* its declared fields) must never touch it. At gateway load: read → `SecretRegistry.register(value)` → keep only the reader seam, not the value. The key appears **only** in the engine's `Authorization: Bearer` header; never in `RunSpec`, run config, prompts, events, audit, evidence, exports, exceptions or `toString()` of any request object (a custom `toString` on engine request types, plus a canary test).

`SecretReader` and `SecretRegistrar` are the two seams `:core:llm` sees; `:app` implements them over `SecretStore` / `SecretRegistry`.

---

## 4. URL policy (pure function; P13-03)

`BaseUrlValidator.validate(raw: String, env: UrlEnv): UrlVerdict` where `UrlEnv(cleartextLoopbackEnabled: Boolean, debugBuild: Boolean)`.

| Rule | Result |
|---|---|
| not an absolute URI, no host, port outside 1–65535, whitespace/control characters | `Rejected(MALFORMED)` |
| scheme `https` | allowed |
| scheme `http` and host ∈ {`127.0.0.1`, `localhost`} and `cleartextLoopbackEnabled` | allowed |
| scheme `http` and host = `10.0.2.2` and `debugBuild` and `cleartextLoopbackEnabled` | allowed (emulator → host alias) |
| any other `http` host, **including LAN IPs** | `Rejected(CLEARTEXT_NOT_ALLOWED)` — reach a PC-hosted model with `adb reverse` (loopback) or HTTPS |
| userinfo (`user:pass@`), query, fragment | `Rejected(…)` |
| trailing `/` | trimmed; endpoint = `base + "/chat/completions"` (the base may already end in `/v1`) |

Error messages **never echo the rejected segment** (it may contain a pasted key). No trust-all `TrustManager`, no `HostnameVerifier` override, no certificate pinning in v1. The engine sets `instanceFollowRedirects = false`; any 3xx is `InvalidRequest` (a redirect must never carry the `Authorization` header to another host).
The switch `ai_cleartext_loopback` joins `InsecureFeatures.KNOWN` (comment: "gateway: http://127.0.0.1 / localhost for a PC-hosted or on-device model"); Settings shows it and every enable is audited by the existing machinery. In relaxed mode it is on (relaxed covers every known, non-denied feature — `InsecureFeatures.evaluate`).
**Dependency on X-14:** until the manifest/NSC question is settled, treat loopback cleartext as *unverified on device* (G13-d).

---

## 5. Retry (pure, deadline-aware; P13-04)

`RetryPolicy.plan(attempt, outcome, remainingRunBudgetMs, retryAfterMs?, jitter): RetryDecision`

| Item | Rule |
|---|---|
| attempts | at most **3 total** |
| delay | `1 s · 2^(attempt-1)` ±20 % jitter (injected `Jitter` seam; tests use a fixed one) |
| `Retry-After` | integer seconds honoured, **capped at 30 s** (HTTP-date form: ignore unless cheap to support) |
| retried | HTTP 408, 425, 429, 5xx; connect/read timeouts; `IOException` before any response |
| never retried | 400/401/403/404/422; a response that parsed and contains tool calls (the loop never replays it); anything once the run is cancelled; any attempt whose delay + timeout would pass the run's remaining wall-clock budget |
| interruption | the delay is a cancellable `delay`; Stop cancels it |

Numbers: **placeholders**. Reference approaches read: ClosePaw retries 5 times from 1 s to 60 s; android-agent has no call timeout (`reference/findings/`).

---

## 6. Errors → run reason codes

```kotlin
sealed interface LlmError {
  Auth · RateLimited(retryAfterMs?) · Transient(cause) · Unreachable · ContextOverflow ·
  InvalidRequest(detail) · ContentFiltered · MalformedResponse(detail) · Cancelled · NotConfigured
}
```

| `LlmError` | HTTP / condition | Run reason | Fix action |
|---|---|---|---|
| `NotConfigured` | no profile, no key, `egressAcceptedAt` null, URL rejected | `AI_NOT_CONFIGURED` | `OPEN_AI_SETTINGS` |
| `Auth` | 401, 403 | `MODEL_AUTH` | `OPEN_AI_SETTINGS` |
| `RateLimited` | 429 after retries | `MODEL_RATE_LIMITED` | — |
| `Transient` | 5xx/408/425 after retries | `MODEL_UNREACHABLE` | — |
| `Unreachable` | DNS/connect/TLS failure | `MODEL_UNREACHABLE` | `OPEN_AI_SETTINGS` |
| `ContextOverflow` | 400/413 with a context-length error (provider code or message heuristic — **UNVERIFIED per provider**) or local `PromptBudget` refusal | `MODEL_CONTEXT_OVERFLOW` / `BUDGET_CONTEXT` | — |
| `ContentFiltered` | `finish_reason = content_filter` | `MODEL_FILTERED` | — |
| `MalformedResponse` | not JSON, missing `choices`, tool call without name, truncated | `MODEL_BAD_RESPONSE` | — |
| `Cancelled` | coroutine cancelled | *(propagates as cancellation, never a failure)* | — |
| `InvalidRequest` | other 4xx, 3xx | `MODEL_BAD_RESPONSE` | — |

Codes are added to `schemas/errors.json` by regeneration (P13-02). A `Cancelled` is **never** converted into an error result: cancellation is rethrown.

---

## 7. Engine, gateway, stub model

**`LlmHttpEngine`** (seam): `suspend fun post(url, headers, body: ByteArray, timeoutMs: Int): HttpResult(status, headers, body)`.

**`HttpUrlConnectionEngine`** (production, P13-06, in `:core:llm`): `HttpURLConnection` with `useCaches=false`, `instanceFollowRedirects=false`, connect/read timeouts from the profile, request body written once, response body read with a **hard cap** (2 MB **(placeholder)**) as UTF-8, a `User-Agent: AutoHost/<version>` header. Cancellation: the coroutine's cancellation handler calls `disconnect()` so a blocked read throws and the call returns within ~1 s; the blocking work runs on the injected IO dispatcher (`AppDispatchers.io`).

**`LlmGateway`** (P13-07): `suspend fun chat(profileId, request, deadline): Result<ChatResponse, LlmError>` — load profile → `BaseUrlValidator` (every call) → key via `SecretReader` → encode → engine under `RetryPolicy` → decode → map errors → emit one `AuditSink` record per call `{provider_id, host, model, request_bytes, response_bytes, prompt_tokens?, completion_tokens?, estimated, latency_ms, attempts, outcome}` — **sizes and hashes, never content**.

**Stub model (PC-only dev tooling, P13-06):** `testing/stub-model/stub_model.py` — Python stdlib `http.server`, `POST /v1/chat/completions`; replays a JSON *script* (one assistant message per request), validates the request shape, appends every request to a JSONL for assertions, and injects faults on demand (429 with `Retry-After`, 500, slow response, invalid JSON, tool call with invalid argument JSON, connection drop mid-body). Used with `adb reverse tcp:<p> tcp:<p>` and base URL `http://127.0.0.1:<p>/v1` (needs `ai_cleartext_loopback`; subject to X-14). Python stays first-class where it belongs: PC tooling.

---

## 8. Privacy, logging, usage

- First use of a profile shows a **data-egress disclosure** (what is sent — `docs/AGENT_RUNTIME.md` §13 — to which host, that it leaves the device); `egressAcceptedAt` is recorded; changing the host clears it.
- Logs and audit: sizes, hashes, counts. **No prompt, screen, tool-result or response text.** `keep_transcript` (per run, default off) writes a bounded, redacted transcript into the run directory only.
- Usage: per call and per run sums, `estimated` flagged; shown in the run detail (Phase 14); the budget (`max_total_tokens`) reserves `prompt estimate + max_output_tokens` **before** dispatch.
- No telemetry to AutoHost; the only network destination is the user's configured endpoint.

---

## 9. Tests (all JVM unless noted)

1. **Codec golden vectors** (`testing/golden/llm_chat_vectors.json`, shared with the stub model): request encode; response decode for text-only, one tool call, several tool calls, invalid argument JSON, `content:null`, each `finish_reason`, usage present/absent.
2. **URL policy**: ≥ 30 vectors (https, each http host class, LAN IP, userinfo, query, fragment, IPv6 literal, trailing slashes, uppercase scheme, embedded whitespace, huge port, punycode host) incl. "the error never contains the rejected segment".
3. **Retry**: table tests with a fake clock and fixed jitter (3 attempts, delays, `Retry-After` cap, remaining-budget cut-off, never-retry classes).
4. **Engine** against `com.sun.net.httpserver.HttpServer`: success, 429 + `Retry-After`, 500, 302 (**not followed**), body over the cap, malformed JSON, drop mid-body, server that never answers → cancellation returns ≤ 1 s.
5. **Secret canary**: the key never appears in any exception message, `toString()`, audit record, event, evidence or log captured during every test above.
6. **Gateway integration** with `FakeEngine`: auth failure, rate-limit exhaustion, context overflow mapping, not-configured paths, egress-not-accepted refusal.
7. **Emulator/device** (G13-d): the stub model over `adb reverse`, with and without the cleartext switch; result recorded in `docs/DEVICES.md`.

---

## 10. Unknowns (do not treat as facts)

- Which providers accept `parallel_tool_calls`, `max_completion_tokens`, or return `usage` on every call; which return tool arguments as an object instead of a string; their context-overflow error shapes.
- Whether OpenAI-compatible endpoints of non-OpenAI vendors behave identically for tool calls.
- Cleartext behaviour at targetSdk 36 without a network-security config (X-14).
- Real prompt sizes of AutoHost observations (measured in Phase 13 evals).
- Whether non-streaming latency is acceptable on slow links (first real-device run; G13-c).
