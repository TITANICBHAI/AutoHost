# ClosePaw — study notes (v4 planning pass)
> **Status:** working notes from *static reading* of `closepaw-0.1.0.zip` by the planning agent (2026-10/11). Nothing was built or run. Line counts and paths refer to that archive. Statements tagged UNVERIFIED were not confirmed. **Licence:** Apache-2.0 (verified: LICENSE + NOTICE "Copyright 2026 ClosePaw Authors")
> These notes are the evidence behind `docs/REUSE_MATRIX.md` rows; they are not instructions and contain no copied source.

# ClosePaw first-hand code findings (static reading only; nothing compiled/run)
Root: /home/claude/work/src/closepaw/closepaw-0.1.0/app/src/main/kotlin/ai/closepaw/
Legend: V = verified by reading source; I = inference; U = unverified

## Size (V, measured): agent 29 files/3726 lines; tool 41/6062; platform 32/5131; llm 26/4150; perception 6/837; history 16/2474; session 12/2427; trace 11/1243; memory 3/387; protocol 27/728; ui 96/16096; browser 38/6225; termux 4/859; auth 5/826; app 17/3864; onboarding 8/1220; debug 3/743.

## agent/Agent.kt (240) — READ FULL
- V ReAct loop `while(shouldContinue())`; pause()/resume() via MutableStateFlow + CompletableDeferred (pause confirmed only when current turn finishes); stop() sets AtomicBoolean; cancellationSignal CompletableDeferred<AgentStopReason>.
- V Auto-compaction before each turn; 3 consecutive failures => stop (the ONLY production stop besides complete_task/errors/user).
- V NO production turn/time/token cap: `evalTurnBudget` null in production (AgentExecutionConfig.kt:71-77; doc settings.md:46 "There is no Max Turns setting"; loop.md:182 "not a production turn cap"; multiagent.md:38 only delegated subagents have timeoutMs).  => AutoHost must add hard budgets itself.
- V uiSettleDelayMs default 3000 ms per turn (AgentExecutionConfig.kt:29); default modelName "gpt-5.2" (hard-coded default).
- V Errors: one recoverable retry (MAX_RECOVERABLE_RETRIES=1) then stop.
## agent/AgentTurnRunner.kt (255) — READ FULL
- V Correct cancellation: `catch (e: CancellationException) { throw e }` BEFORE `catch (e: Exception)` (contrast ARIA bug).
- V Pipeline per turn: PERCEPTION (captureScreen + AppClassifier.maskIfBlocked) -> prepareTurn (NavigationState + LoopDetectionPolicy) -> PLANNING (LLM stream) -> EXECUTION (tools) -> decideTurnOutcome.
## agent/TurnPlanningPhaseRunner.kt (309), TurnExecutionPhaseRunner.kt (272) — READ FULL
- V Planning: system prompt from AgentRoleDef + skill catalog; observation computed once (TurnObservation.capture); prompt built by PromptBuilder; memory recall + app skill injected; LLM streamed through Turn.runStreaming with reactive compaction retry.
- V Execution: for each selected tool call -> history FunctionCall -> toolRouter.execute(...) -> post-action observation (delay 500 ms, captureScreen, maskIfBlocked) -> history FunctionCallOutput -> trace -> events. First non-Success result aborts remaining calls in the turn.
- V "Verification" of effects is done by the MODEL reading the next observation; host-side check is only UiChangeDetector (Changed/Unchanged/Unverifiable) used inside executors.
## agent/cognition/policy/TurnToolPolicy.kt (119), LoopDetectionPolicy.kt (55) — READ FULL
- V Arbitration: hoist cognitive tools (scratchpad, write_todos, remember_experience); keep rest in model order; complete_task only honoured when no screen-changing tool selected in the same turn. Completion = MODEL-DECLARED (status success|failure) — host does not verify.
- V LoopDetection: only "stable screen" (5 turns, Jaccard>=0.95) => factual warning to LLM; no hard stop (advisory warnings were removed because of false positives).
## tool/PolicyEngine.kt (181), AppClassifier.kt (136) — READ FULL
- V Modes ALWAYS_ASK|AUTO_APPROVE|SMART x tiers BLOCKED|CAUTIOUS|NORMAL -> Allow|Deny|AskUser. Order: non-screen-changing allow; back/home escape allow; effective tier BLOCKED deny (stricter of current vs destination package); browser_script special-case; session allow-list (gated by ALWAYS_ASK); mode dispatch (SMART: CAUTIOUS asks, NORMAL allows). Unknown package => CAUTIOUS (fail-safe).
- V Bundled BLOCKED is an absolute floor (user overrides refused at write time AND classify() pins BLOCKED). maskIfBlocked() returns empty elements + null image so BLOCKED app content never reaches the LLM.
- V Both are small, pure Kotlin (only android.util.Log + org.json) -> strong ADAPT candidates for AutoHost ScreenGuard/AppTier provider.
## tool/ToolRouter.kt (457) — READ FULL
- V Lifecycle Validating -> PolicyCheck -> AwaitingApproval (60 s timeout) -> Scheduled -> Executing -> Success|Error|Cancelled; per-call CancellationToken; resolveApproval(callId, decision); cancel/cancelAll; TOCTOU guard after approval (foreground package re-check + BLOCKED re-check); snapshot re-captured after approval wait; open_app destination package pre-resolved so "stricter wins" works.
- V Inner `catch (e: Exception)` around invocation.execute() (line 293) would also swallow CancellationException (minor).
## tool/impl/MobileActionTool.kt (343) — READ FULL
- V One consolidated tool `mobile_action` with action in {click,long_press,type,scroll,swipe}; targeting priority element_index > text(+text_index) > x/y (pixels); strict JSON schema (additionalProperties:false) + server-side validation (duration<=30000, coords>=0, scroll rejects bare x/y); every call carries `agent_thought`.
## tool/action/* — READ: PointActionExecutorCore (326), ClickExecutor (52), UiChangeDetector (157), TargetResolver (129)
- V Channel fallback loop: node ACTION_CLICK (needs semantic hint) first, gesture tap second; coordinate-only targets go straight to gesture; before dispatch: display-bounds check + cancellation check; after success: capturePostActionAnalysis (300 ms settle) => ActionOutcome.Success(verified = changeResult==Changed).
- V refinePointActionTarget: if resolved element isn't clickable, promote to the smallest actionable container, then nearest actionable child (area<80% of container; ambiguity guard nearest^2*2 <= runnerUp^2).
- V UiChangeDetector: FNV-1a hash over sorted elements' structural fields (excludes isFocused) + 8x8 average perceptual hash fallback when a11y tree empty; 3-valued result Changed/Unchanged/Unverifiable ("unverifiable is a distinct outcome, not silently Changed").
- V TargetResolver is a pure function (no Android deps): NotFound/Ambiguous/Resolved + coordinate fallback with warning.

---

# ClosePaw findings, part 2 (written after the 2nd compaction)
Root: closepaw-0.1.0/app/src/main/kotlin/ai/closepaw/. Static reading only; nothing built or run.
Part 1 is closepaw_findings.md (agent loop, router, policy, executors).

## Sizes (measured), files/lines
agent 29/3726 | tool 41/6062 | platform 32/5131 | llm 26/4150 | perception 6/837 | history 16/2474 | session 12/2427 | trace 11/1243 | memory 3/387 | protocol 27/728 | ui 96/16096 | browser 38/6225 | termux 4/859 | auth 5/826 | app 17/3864 | onboarding 8/1220 | debug 3/743
Totals: 615 Kotlin files (~54K main lines); 210 unit-test files (45,414 lines); 27 androidTest files.

## platform/ (candidates to port into AutoHost :capabilities:a11y; ADAPT, verify API levels)
- NodeActionPerformer.kt (383): node-first actions on the Main dispatcher; identity guard (bounds match, or id/class/label match) returns Failure on mismatch so the gesture fallback is used; directional scroll actions (API 23) fall back to FORWARD/BACKWARD; long-click has a clickable fallback; set-text inserts at the cursor unless `clear`, handles hint text, restores the cursor, verifies via node.refresh() (catches silent failures such as NumberPicker); Enter uses ACTION_IME_ENTER (API 30 guard) with a click fallback.
- AccessibilityGestureInjector.kt (175): tap duration from ViewConfiguration.getTapTimeout(); withTimeoutOrNull(5000); `dispatched == false` handled; callbacks guarded by continuation.isActive; overlay touch gate sets FLAG_NOT_TOUCHABLE plus a 50 ms settle so the app's own overlay does not swallow injected gestures; global actions for back/home/recents.
- AccessibilityNodeFinder.kt (207): closest-centre clickable+visible node containing the point; focused-editable finder; scrollable finder; text-input detection via ACTION_SET_TEXT (covers WebView/custom widgets).
- AccessibilityPlatform.kt (619; lines 42-470 read): captureScreen masks if the current package OR ANY window root in the stack is BLOCKED; retries up to 3x at 150 ms for empty roots; windows sorted by layer, excluding TYPE_ACCESSIBILITY_OVERLAY and TYPE_INPUT_METHOD; OEM null-root workaround; getDisplayInfo uses maximumWindowMetrics (API 31 guard) else getRealMetrics; screenshots via takeScreenshot (API 30 guard) and takeScreenshotOfWindow (API 34).
- Accessibility config res/xml/agent_accessibility_config.xml: events typeWindowStateChanged|typeWindowContentChanged; flags flagDefault|flagRetrieveInteractiveWindows; canPerformGestures, canTakeScreenshot, canRetrieveWindowContent; notificationTimeout 100.

## perception/
- Perceptor.kt (393) + PerceptorInternals.kt (272): multi-root traversal, isVisibleToUser filter, password masking to "[password]", keyboard-node filtering, visibility-ratio thresholds, min size, dedup keys, two pools (interactive / non-interactive) with caps and truncation (interactiveKeepRatio), spatial sort, text_index / desc_index occurrence counters. The prompt JSON uses toString(2), which wastes tokens; use compact output.

## llm/
- LLMClient.kt (155): abstract class with chatWithTools / chatWithToolsStreaming / isReady / cleanup; unified LLMStreamEvent (Created, TextDelta, ToolCallDone, Completed, Failed); typed RateLimitException(retryAfterMs) and TransientException. COUPLED to OpenAI SDK types (com.openai.models.responses.FunctionTool, ResponseInputItem), so it must be re-typed to provider-neutral types for AutoHost. Constants: MAX_RETRIES 5, INITIAL_BACKOFF 1 s, MAX 60 s, x2. DEFAULT_MODEL "glm-5".
- ChatCompletionClient.kt (298): streaming tool-call delta assembly keyed by index; finish_reason "length" -> TransientException, "content_filter" -> Failed, "stop"/"tool_calls" -> emit ToolCallDone; a stream that ends without finish_reason -> TransientException; awaitClose closes the stream on cancellation. Uses OpenAI Java SDK 4.14.0. maxOutputTokens is applied only on the non-streaming path (the streaming buildParams omits it).
- LLMClientFactory.kt (135): cache keyed by model name plus AuthStore generation (compute() atomic rebuild); throws MissingCredential for a blank OTHER baseUrl so the key is never misrouted to api.openai.com. It uses runBlocking inside compute(); do not copy that as-is.
- OtherBaseUrlValidator.kt (91): https-only in release; http only for localhost / 127.0.0.1 / 10.0.2.2 in debug; rejects userinfo, query and fragment; error messages never echo the rejected segment; trims the trailing "/". Has a unit test (OtherBaseUrlValidatorTest). Depends on BuildConfig.DEBUG (injectable). ADAPT (tiny, pure JVM).
- InsecureSslConfig: lives in app/src/debug/.../llm/ (trust-all TLS gated by BuildConfig.INSECURE_SSL_FOR_EVAL) with a no-op stub in src/release. DO NOT CARRY OVER.
- CodexResponseClient.kt: talks to https://chatgpt.com/backend-api/codex/responses via OAuth (undocumented backend; ToS and credential risk). DO NOT ADOPT.
- LFMLLMClient.kt: proprietary LiquidAI Leap SDK. DO NOT ADOPT. It also documents LocalLlmSemantics gaps (drops non user/assistant roles; random tool-call IDs).
- auth/AuthStore.kt (228): EncryptedSharedPreferences + MasterKey AES256_GCM via androidx.security:security-crypto:1.1.0-alpha06 (I believe this library is deprecated; UNVERIFIED). AutoHost has its own SecretStore plan, so do not port.

## Licences (app/src/main/assets/open_source_licenses.json): exactly 140 entries (the GPT "140" claim is TRUE)
- Apache variants 129 (94 "The Apache Software License, Version 2.0", 14 "Apache-2.0", 12 "The Apache License, Version 2.0", 6 "Apache License, Version 2.0", 1 "Apache 2.0", 1 "Apache 2", 1 "Apache License 2.0"); MIT 6; Bouncy Castle 3 (bcpkix/bcprov/bcutil 1.84); CC0 1 (net.i2p.crypto:eddsa:0.3.0); "Leap Terms of Use" 1 (ai.liquid.leap:leap-sdk-android:0.9.2, PROPRIETARY).
- NOTICE vs generated JSON conflicts: Shizuku is "Apache" in NOTICE but MIT in the JSON; Lucide is ISC in NOTICE but MIT in the JSON (artifact icons-lucide-cmp-android-debug). NOTICE calls itself non-authoritative.
- HiddenApiBypass (org.lsposed) is listed in NOTICE: a Play-policy risk tied to Shizuku/VirtualDisplay.
- OpenAI Java SDK 4.14.0, OkHttp 5.2.1 and 4.12.0, Jackson 2.18.2 are present (heavy).

## Binaries and symlinks
- Binaries: doc/release/play-store/a11y-demo.mp4 (2,546,423 B); eval/tools/shizuku.apk (2,571,773 B); gradle/wrapper/gradle-wrapper.jar (43,462 B).
- Symlinks: .cursorrules, GEMINI.md, AGENTS.md -> CLAUDE.md; .codex, .agents -> .claude; .claude/skills/coding-standards and .claude/skills/verify -> absolute /Users/moonkey/workspace/agent-config/... (DANGLING).
- Exclude binaries and symlinks from any reference snapshot; record path, size and sha256 instead.

## Build
- app/build.gradle.kts (254 lines; first 140 read): minSdk 31 (IMPOSED by the Leap SDK; its own comment says "If we need to support Android < 12, consider a cloud-only flavor"), compile/target 36, plugin com.jaredsburrows.license 0.9.8, release minify+shrink, signing via ENV vars (no secrets in the repo), aidl = true (Shizuku).
- Unit tests use isReturnDefaultValues = true; only 1 test file uses Robolectric.

## Tests that exist (NOT run by me)
PolicyEngineTest, AppClassifierSecurityTest, AppClassifierOverrideTest, TargetResolverTest, PerceptorTest, PerceptorInternalsTest, NodeActionPerformerTest, ToolRouterTest, OtherBaseUrlValidatorTest, CompactorTest, LoopDetectionPolicyTest, TurnToolPolicyTest, MemoryStoreTest, ChatCompletion*Test.

## Still unread (ClosePaw)
Turn.kt, Compactor.kt, HistoryManager.kt, prompt assets, app_tiers.json entry count, TypeExecutor / ScrollExecutor / SwipeExecutor, MemoryStore, trace code and redaction, UI capsule code, eval/, PRIVACY_POLICY.md, a11y-declaration.md, doc/PROGRESS.md, rest of build.gradle.kts, protocol/*.
