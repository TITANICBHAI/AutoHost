# Worklog — AutoHost multi-agent shared log

> Append-only. Each session appends one section per task with the template at the bottom of this file.
> Read this file before starting work; write your section when a task is done.

---
Task ID: S9-0 (setup)
Agent: main (session 9)
Task: Phase 6 part 1 setup — review + worklog bootstrap

Work Log:
- Extracted `autohost-phase5-code.zip` to the workspace; confirmed `python3 tools/progress.py --check` ok.
- Baseline suites green: sdk-python 144 tests OK, root schema tests 29 tests OK.
- Read: HANDOFF.md (session 8 snapshot), AGENTS.md, plan.md §7.9 + §18 P6, docs/PROGRESS.md Phase 6,
  schemas/flow.schema.json, schemas/capability-specs.w1.json, schemas/errors.json, schemas/broker.schema.json,
  docs/REPO_LAYOUT.md (:runtime:flow layout), autohost_ref/schedule.py + tools/gen_schedule_vectors.py
  (the P4 pattern this phase mirrors), autohost/_runtime.py, autohost_sim (device/match/runner),
  core/engine ScheduleEngineGoldenTest.kt (the Kotlin golden-replay pattern), core/model Selector.kt + SelectorEngine.kt,
  runtime/api (RuntimeProvider/RunHandle), samples/press-button-flow, docs/DECISIONS.md (latest ADR-050).

Stage Summary:
- Session scope (owner's todo, Phase 6 part 1 only):
  1. P6-01a Python reference flow interpreter (`autohost_ref/flows.py`) + tests (51 green).
  2. Golden vectors `tools/gen_flow_vectors.py` -> `testing/golden/flow_vectors.json` (44 cases) + replay test.
  3. P6-01b `:core:model` flow types + `:runtime:flow` scaffold + FlowParser (Kotlin, written).
  4. P6-02/03/05 interpreter + executors + selectors + expressions + variables (Kotlin, written).
  5. P6-04 control flow when/repeat/retry/optional/runFlow (in interpreter, written).
  Then: update all MDs, zip, hand back. Later parts of Phase 6 (P6-06..P6-12, gates) come next session.
- Owner rules: no Gradle/device verification in this environment (code written, marked (WRITTEN)); Python tests run here;
  all MDs updated; zip after the listed todos.
- Design notes for flows.py (reference interpreter), following the schedule.py pattern (pure stdlib, no I/O):
  * parse_flow() validates + normalizes (fail closed, precise FlowError), mirroring parse_rule().
  * Flow selector form (text/id/desc/class/package as str|list|{value,mode}, relations, index, state, bounds)
    normalizes to the broker wire form (op/terms/Match) so the sim's select() evaluates it unchanged.
    Default label mode is "eq" (precise by default); `mode` key overrides.
  * `timeout` modifier is in SECONDS (sample flow.yaml `timeout: 20` reads as seconds); converted to ms for broker calls.
  * Commands map onto wave-1 broker methods (device.app.launch/stop, device.ui.*, device.screenshot.capture,
    device.ime.clipboard_set, notify.post.post, ui.prompt.ask). Failure codes are broker error codes only.
  * Hooks: onFlowStart before body (failure fails the run); onFlowComplete after body on success only.
  * optional: swallows typed StepFailure (never Cancelled/WaitUser); retry {times} = retries after first attempt
    (SDK flow.retry convention), fixed backoff; repeat {times|while}; while re-evaluated per iteration with a cap.
  * Variables: ${config.x} ${profile.x} ${env.X} ${run.attempt} resolve by scope; ${secret.NAME} resolves ONLY in
    action positions (inputText.text when secret:true, setClipboard.text) and is redacted in events; unresolved
    placeholders stay literal (fail closed, same as scope templating).
  * Expression language (when.true / repeat.while): && || ! comparisons + string concat, parens, literals,
    bare/dotted identifiers (env > config > profile); non-boolean when.true result or unknown identifier = typed failure.
  * Outcome: {"status": SUCCEEDED|FAILED|CANCELLED|WAITING_USER, "reason"?, "message"?, "result"?, "next_run"?}.
  * Vectors pin exact broker call logs (templated args, normalized selectors) + event sequence (ts stripped)
    + outcome; device model is data-only so the Kotlin golden test can mirror it (ScheduleEngineGoldenTest pattern).

---
Task ID: S9-1 / S9-2
Agent: main (session 9)
Task: P6-01a reference flow interpreter + golden vectors

Work Log:
- Wrote `sdk-python/autohost_ref/flows.py` (~1440 lines, pure stdlib): parse_flow/parse_step
  validation (fail closed, precise FlowError), flow->wire selector normalization (eq default
  mode, state folding, relations, index folding into one relation term), Scope + ${...}
  templating (secrets only in action positions, redaction), expression language (tokenizer +
  recursive descent: && || ! comparisons + - concat, parens, dotted identifiers), Exec host
  surface, run_flow + 29 command executors incl. when/optional/retry/repeat/runFlow hooks.
- Wrote `sdk-python/tests/test_ref_flows.py`: exactly 51 tests (parse 10, selectors 8,
  templating 7, expressions 7, run semantics 19) - ALL GREEN.
- Wrote `tools/gen_flow_vectors.py`: 44 cases (42 run + 2 parse_error) over a data-only device
  model (screens/launch_map/start/installed/screenshot/prompt_answers/fail_first); pinned
  outcome + exact broker call log + event sequence. Two generator bugs found and fixed while
  reviewing outputs: fail_first key must rsplit on the LAST dot (capability names are dotted);
  two selector cases needed a foreground screen (start: home).
- Generated `testing/golden/flow_vectors.json` (44 cases) and wrote the replay test
  `sdk-python/tests/test_flow_vectors.py` (contract + per-case subTest replays) - GREEN.

Stage Summary:
- sdk-python suite: 198 tests OK (was 144; +51 reference, +3 vector contract).
- Golden file is the shared contract with the Kotlin FlowInterpreter (Kotlin golden test comes
  with the :runtime:flow work). fix in flows.py during tests: defineVariables body IS the
  variable map (only modifier names reserved); parse_flow now rejects unknown top-level keys;
  "!" added to the expression operator set.

---
Task ID: S9-3 / S9-4 / S9-5
Agent: main (session 9)
Task: P6-01b + P6-02/03/05 + P6-04 — Kotlin flow types, parser, interpreter (written)

Work Log:
- `:core:model` FlowDocument.kt: FlowDocument/FlowStep/FlowWhen data classes (when-condition
  selectors in wire form; bodies stay flow-form JSON).
- New `:runtime:flow` JVM module (build.gradle.kts, settings include): kotlin-jvm +
  serialization + coroutines, deps core:model + core:common. No new catalog entries needed.
- FlowParser.kt: parse(text)/fromJson(JsonObject) -> FlowDocument; validation order + error
  messages mirror autohost_ref.flows.parse_flow exactly (incl. Python-style sorted list reprs);
  toWireSelector normalises the flow selector form at parse time for when-guards and at
  execution time for command bodies.
- JsonValues.kt: JSON scalar inspection that distinguishes int/float/bool/quoted-string like
  Python (drives templating + expressions).
- SelectorResolver.kt (REPO_LAYOUT's SelectorResolver): execution-time ${...} label resolution
  over wire selectors (unresolved stays literal, fail closed).
- VariableScope.kt: FlowScope (config/profile/env/run/secrets, bare-name env>config>profile),
  resolveString/resolveValue (whole-string placeholder keeps native type), redact, child scopes.
- ExprEvaluator.kt: tokenizer + recursive descent (||, &&, !, comparisons, + -, parens,
  dotted names), secrets refused, ordering needs same types, bool-vs-number equality false.
- FlowInterpreter.kt: FlowBroker/FlowExec/RunState/FlowOutcome(toJson), runFlow with hooks
  semantics, per-step when/optional handling, evidence events, typed outcomes.
- CommandExecutors.kt: all 29 executors incl. P6-04 control flow (when/optional/retry/repeat/
  runFlow) mapping onto wave-1 broker methods; click verification failure = TIMEOUT/retryable.
- Tests written (not compiled - no Gradle here): FlowParserTest (13), SelectorResolverTest (8),
  ExprEvaluatorTest (8), VariableScopeTest (7), FlowInterpreterTest (19 control-flow cases with
  StubBroker), FlowInterpreterGoldenTest (replays all 44 flow_vectors.json cases against a
  VectorDevice that mirrors autohost_sim dispatch + reuses :core:model SelectorEngine).

Stage Summary:
- Reference-side fixes made while writing the mirror: when.visible labels now resolve at
  execution (were left literal - bug); runFlow env precedence = parent env < child doc env <
  runFlow env (explicit override wins); when.true / repeat.while: false skips/exits, non-boolean
  fails closed (INVALID_ARGS). Vectors regenerated; Python suite 198 OK.
- Naming follows REPO_LAYOUT (FlowSelectors -> SelectorResolver); deliberate deviation: one
  CommandExecutors.kt instead of a per-command directory (single dispatch table, shared
  helpers) - recorded in ADR-051.

---
Task ID: S9-6
Agent: main (session 9)
Task: docs update + final verification

Work Log:
- docs/PROGRESS.md: P6-01..P6-05 marked (WRITTEN) with evidence lines; dashboard regenerated
  (overall written 35%, Phase 6 30%); progress.py --check ok.
- docs/DECISIONS.md: ADR-051 (flow v1 semantics, Python reference as executable spec, vector
  contract, Kotlin layout deviation) + ADR-052 (JSON flows in v1, YAML parser plug deferred to
  P6-06 with licence check on a Gradle machine).
- docs/REPO_LAYOUT.md: :runtime:flow file list updated (CommandExecutors.kt single table,
  JsonValues.kt, types in :core:model, golden replay tests).
- HANDOFF.md: rewritten as the session-9 snapshot (status table, decisions, interfaces incl.
  the vector device model, what-to-write-next P6-06..P6-12, verification checklist with the
  new :runtime:flow:test + gen_flow_vectors drift check, first-build notes).
- Final verification: sync_sdk --check ok; gen_artifacts --check ok; progress --check ok;
  sdk-python 198 tests OK; schema tests 29 OK.

Stage Summary:
- Session scope complete: P6-01a (reference + 51 tests green), golden vectors (44 cases +
  replay green), P6-01b/02/03/05 (Kotlin written), P6-04 (control flow written + tests).
- Remaining Phase 6 for next session: P6-06 FlowRuntimeProvider (in-process, no worker),
  P6-07..P6-12 (recorder, guarded replay, overlay, view/edit, simulator flows, sample), Gate 6.

---
Task ID: S10-1
Agent: main (session 10)
Task: P6-06 FlowRuntimeProvider — in-process flow runtime, no worker

Work Log:
- FlowInterpreter.kt: added the optional FlowExec.stepGuard hook (runs after the when gate,
  before the step counter; null = no-op, golden vectors unaffected) — the seam P6-08's
  GuardedReplay plugs into.
- YamlParserPlug.kt (ADR-052 executed as an interface): install()/installed + resetForTests;
  .yaml entries fail closed with the ADR-052 message until a Gradle machine installs yamlkt.
- FlowSources.kt: entry resolution (manifest entry, else flow.json -> flow.yaml), parseFlowText
  by extension (JSON via AhJson, YAML via plug), loadSubflows (every sibling flow file under
  file-name + stem keys; task.json excluded; unparsable subflows only fail if a runFlow step
  actually targets them).
- FlowRuntimeProvider.kt: RuntimeProvider type="flow"; start() parses + loads subflows, then
  runs FlowInterpreter in a coroutine on the injected scope; BinderFlowBroker wraps the AIDL
  binder (call -> Envelope decode, error.code/message/retryable -> BrokerCallException, scalar
  results wrapped {"value": ...} like the sim adapter, IO dispatcher injected); events forwarded
  via binder.emit (no ts — the orchestrator stamps); separate heartbeat coroutine (2s, mirrors
  the worker heartbeat thread so STARTING->RUNNING + heartbeat-loss detection still apply);
  unexpected interpreter crash -> deathListener (INTERRUPTED), not a flow failure; secrets=[]
  (SecretsProvider is still a typed stub — ${secret.NAME} fails NOT_FOUND, fail closed).
- RunControls/InProcessRunHandle: cancel/pause/resume/kill; pause gates between steps and inside
  sleeps (monotonic deadline, 50ms slices); kill = cancel + job cancellation; finish latch.
- Wiring: :runtime:flow build.gradle.kts += :core:broker; :app += :runtime:flow; AppGraph
  constructs FlowRuntimeProvider (apiLevel=ApiGate.sdkInt, viewport from displayMetrics),
  registers it in RuntimeRegistry, wires its death listener to orchestrator.onWorkerDeath.
- FlowRuntimeProviderTest.kt (9 tests, written): happy path (finished event carries
  SUCCEEDED outcome), missing entry, parse failure before any broker call, YAML-without-plug
  fail-closed message, installed-plug happy path (scripted plug, exact-text assert — no YAML
  grammar shipped), subflow name+stem resolution + task.json exclusion, cancel-while-paused,
  pause-release, BrokerCallException mapping, scalar-result wrapping.

Stage Summary:
- Flow tasks now start like Python tasks: same RunSpec, same token, same orchestrator, same
  broker — but the interpreter is an in-process coroutine (Gate 6-a's "no :w* process").
- Deliberate: YAML stays plug-gated (ADR-052); recorder.* Dev API stubs stay stubbed until P6-09.

---
Task ID: S10-2 / S10-3
Agent: main (session 10)
Task: P6-07 Recorder + P6-08 StepSnapshot/StateMatcher/GuardedReplay (Kotlin, written)

Work Log:
- StepSnapshot.kt: StepSnapshot (semantic node keys `cls|id|text|desc` + package/activity),
  RecordingStepMeta (label, pre_state, needs_confirmation, selector_rank, elapsed_ms, secrets),
  RecordingSidecar (schema/flow/recorded_at_ms/on_mismatch/state_timeout_ms/steps) with JSON
  round trips. ADR-053 (this session): per-step snapshots live in a sidecar
  `<flow>.recording.json`, NOT inside the flow document — flows stay schema-strict and
  diffable; the guard matches snapshots to steps by label.
- Recorder.kt (pure JVM): RecordedEvent/RecordedNode/RecordedScreen -> RecorderResult.
  Selector ranking per plan: unique resource-id > unique desc > unique text > relational
  (lone same-class node below a uniquely-labelled anchor, verified against the recorded node
  list) > bounds (flagged needs_confirmation = the coordinate case, G6-e). Consecutive TEXT
  events on one field coalesce to a single inputText (last text wins). Password fields never
  store text: body.text becomes ${secret.INPUT_n} with secret:true + sidecar secret names
  (canary discipline). Every recorded body carries an explicit `label` (modifiers are body
  keys) so replay labels match sidecar entries. toFlowDocument renders schema-1 flow JSON.
- StateMatcher.kt: node-set Jaccard * 0.85 + package/activity * 0.15 (0.5 each), threshold 0.85
  INCLUSIVE (pinned by test); LiveState.fromSnapshot reduces a device.ui.snapshot result with
  the same nodeKey rule; mismatchEvidence builder for the guard's evidence payload.
- GuardedReplay.kt: implements the FlowExec.stepGuard seam added in S10-1. Steps without a
  sidecar entry are NOT guarded (hand-edited flows replay unguarded by design). Polls
  device.ui.snapshot every 250ms until state_timeout_ms; snapshot failure = "cannot confirm"
  (keeps polling; budget expiry = mismatch). on_mismatch: stop -> FlowFailure
  PRE_STATE_MISMATCH (non-retryable, evidence event carries step/score/threshold, Gate 6-c);
  ask_user -> FlowWaitUserException (WAITING_USER); continue -> warn log, run anyway.
- FlowScope.empty() added (tests/guards).
- Tests written: RecorderTest.kt (11: id wins, repeated id falls through, desc rank, two
  below-anchor candidates refuse relational, lone field gets relational, bounds flagged,
  coalescing, password placeholder, keys/scrolls + unknown-key warning, sidecar labels/keys,
  FlowParser round trip + sidecar round trip); GuardedReplayTest.kt (10: identical/half/
  boundary scores, snapshot reduction, pass/stop/ask/continue policies, unguarded steps,
  poll-settles, snapshot-failure budget expiry). FlowRuntimeProviderTest.kt (9, from S10-1).

Stage Summary:
- 30 new JVM tests written (await Gradle). The recorded-flow contract is now: flow.json
  (schema-strict, labels inside bodies) + flow.json.recording.json (sidecar, ADR-053).
- Deliberate: recorder drops events it cannot target (no bounds) with warnings; unknown keys
  dropped with warnings — review UI shows both lists before save.

---
Task ID: S10-4
Agent: main (session 10)
Task: P6-09 recorder overlay + review UI + Dev API recorder.* (Kotlin, written)

Work Log:
- capabilities/a11y: new RecorderTap.kt (RecorderTapEvent.fromEvent — CLICK/LONG_CLICK/TEXT
  with source node fields; every node read wrapped against stale-node RuntimeExceptions);
  A11yBridge gained armRecorder/disarmRecorder/isRecorderArmed + recorderEvents SharedFlow
  (buffer 64, armed-only forwarding — zero cost when off); the service's onAccessibilityEvent
  forwards tap events before the existing when-block.
- :app recorder/RecorderController.kt: arm -> collect A11yBridge.recorderEvents, per tap read
  the live screen via SimpleA11yReader (scoped to the tapped package; activity from
  ForegroundTracker when it matches), match the acted node by exact bounds then <=24px nearest,
  convert to runtime:flow RecordedEvents; stop -> Recorder.record -> UiState{result, flowJson};
  save(folder, flowJson, result) writes flow.json + flow.json.recording.json into the dev
  folder (injected devRoot); synchronized(events) around the buffer.
- :app recorder/RecorderOverlay.kt: TYPE_ACCESSIBILITY_OVERLAY strip (● REC + counter + Stop)
  attached through the a11y service's WindowManager (no SYSTEM_ALERT_WINDOW needed); every
  attach/detach failure is an honest no-op — recording continues without the overlay; fresh
  overlay per session via AppGraph.recorderOverlay().
- :app ui/RecorderReviewScreen.kt: live-recording view (count + instructions + Stop) then the
  review list — command, body preview, selector-rank chip (STABLE ID/DESC/TEXT/RELATIONAL/
  COORDINATES), delete + reorder, warnings; G6-e gate: Save disabled while any bounds-flagged
  step is unconfirmed; save rebuilds flow+sidecar from the edited list and writes both files.
- Navigation: Routes.RECORDER_REVIEW added; AppNavHost routes it (back refreshes dev tasks);
  Library header button cycles ● Record -> ● REC (n) (disabled) -> ✓ Review — Android 10+
  blocks background activity launches, so the overlay Stop parks the session and the user
  returns via the Library button (documented in-code).
- DevApiWiring: recorderStart/recorderStop now drive the same controller (arm →
  RECORDING_BLOCKED on failure with the controller's reason; stop → steps/warnings/flow/sidecar
  JSON or NOT_RECORDING). ADR-051 authoring loop over MCP works headlessly (overlay optional).
- designsystem: added the String-parameter StatusChip overload (success/warning/neutral tones)
  to Chips.kt — TaskScreens/HealthScreen/RunScreen already used this shape; it was one of the
  known "Compose drift" first-build gaps (HANDOFF §6), now closed at the source.

Stage Summary:
- Recorder path complete: a11y tap -> controller -> Recorder (P6-07) -> review -> flow.json +
  sidecar on disk -> dev-folder import; coordinate confirmation enforced before save (G6-e).
- Known limits recorded: recorder covers click/long-click/text/back/home/enter/recents/scroll
  (plan's v1 set); multi-window/IME-only gestures are out of scope until Phase 7 surfaces.

---
Task ID: S10-5 / S10-6
Agent: main (session 10)
Task: P6-10 flow view/edit + P6-11 simulator flows + P6-12 press-button-flow twin

Work Log:
- P6-10 (written): TaskScreens.kt gained a Flow tab for runtime.type=="flow" (Overview/Config/
  Profiles/History/Flow) with FlowSection — view the flow document as text, edit-as-text,
  Validate & Save through FlowSources.parseFlowText (the runtime's own parser; a bad edit never
  reaches disk), YAML entries read-only with the ADR-052 explanation until the plug lands.
- P6-11 (green here): new sdk-python/autohost_sim/flows.py — SimBroker over FakeDevice
  (SimError->BrokerError, scalar wrap, call log), load_flow_document (manifest entry, else
  flow.json -> flow.yaml; YAML via PyYAML with a typed FlowError when missing), load_subflows
  (file name + stem keys, raw docs — the reference's runFlow re-parses), run_flow_task returning
  the same Outcome shape as runner.run_task (Result on SUCCEEDED, events, CANCELLED via the
  device clock's on_tick). tests/test_sim_flows.py: 10 tests GREEN (json happy path incl.
  device.calls launch, YAML entry, default entry order, named-entry miss + no-entry miss
  messages, parse error, subflow by stem, failure class NOT_FOUND + evidence, cancel mid-loop,
  event order).
- P6-12 (green here): samples/press-button-flow gained flow.json — the generated JSON twin of
  flow.yaml (ADR-052: sample stays YAML-authored, twin makes it runnable pre-plug) + README
  documenting the twin rule, the sim path and the Gate 6-a on-device expectation;
  tests/test_samples.py PressButtonFlowSample: 3 tests GREEN (yaml/json twin equality gate,
  twin runs in the simulator end to end via support.press_device -> SUCCEEDED/pressed,
  twin validates against flow.schema.json via jsonschema_mini).
- Suite totals: sdk-python 211 tests OK (was 198: +10 sim flows, +3 sample flow); root schema
  tests 29 OK.

Stage Summary:
- Both runtimes now run in the simulator through one call surface (run_task / run_flow_task) —
  the authoring loop (write flow -> sim run -> fix -> ship) works without a phone.
- On-device flow run + recorded-flow replay remain Gate 6 (needs the phone); everything that
  can be executed here is green.

---
Task ID: S10-7
Agent: main (session 10)
Task: docs + final verification + packaging

Work Log:
- docs/PROGRESS.md: P6-06..P6-12 marked (WRITTEN) with evidence lines; "Last updated" line
  rewritten for session 10; dashboard regenerated (overall written 38%, Phase 6 tasks 12/12
  written; Gate 6 items stay open — phone required); progress.py --check ok.
- docs/DECISIONS.md: ADR-053 (recorded-flow sidecar + guard seam + secret placeholder rule +
  inclusive threshold) + ADR-054 (in-process flow runtime, ticker heartbeats, death mapping,
  fail-closed secrets/YAML).
- docs/REPO_LAYOUT.md: :runtime:flow file list extended (provider/sources/plug/recorder/snapshot/
  matcher/guard + new tests); a11y list += RecorderTap.kt (overlay host stays :app, P7 overlay
  note updated); :app list += recorder/ + RecorderReviewScreen.
- docs/DEVAPI.md: recorder.start/stop documented as real (P6-09) with payload shapes.
- docs/UI_SPEC.md: S18 rewritten to the built behavior (overlay via a11y window, review gate,
  JSON edit-as-text); S04 Flow/Code tab note updated (Task Studio still Phase 11).
- HANDOFF.md: rewritten as the session-10 snapshot (status table, ADR-053/054, interfaces incl.
  the recorder tap contract and sim flow surface, what-next = Phase 7 + Gate 6, verification
  checklist with 211 tests, first-build notes for the new modules, first-build failure list).
- Final verification (all green): sdk-python 211 tests OK; root schema tests 29 OK;
  sync_sdk --check ok; gen_flow_vectors regeneration byte-stable (44 cases, reference
  untouched this session); gen_schedule_vectors regeneration ok; gen_artifacts --check ok;
  mcp_eval 3/3 PASS; progress --check ok.

Stage Summary:
- Phase 6 written end to end (P6-01..P6-12). Remaining for the next session: Phase 7 (task UI
  surfaces), then Phases 8-11. Gate 6 (G6-a..G6-e) awaits the phone; the on-device checklist
  is in HANDOFF §4 + docs/GATES.md.

---
Task ID: S11-1..S11-2
Agent: main (session 11)
Task: Phase 7 part 1 — P7-03/P7-04 Python side (UI-handler path + imperative ui.*)

Work Log:
- Recon: read HANDOFF (session-10 snapshot), plan §5/§7/§8/§10, UI_SPEC, REPO_LAYOUT, and the
  worker/prompt/notify/manifest code paths; found two pre-existing gaps to close in passing:
  `uiPromptProvider` was never registered in the CapabilityRegistry (P5-06 gap), and `notify.post`
  had NO Kotlin provider at all (CAP-notify.post was still an empty tracker row).
- Python SDK: `autohost/_runtime.py` — extracted `load_entry_module` (+ `EntryNotFound` with
  reason=ENTRY_NOT_FOUND) and added `run_ui_handler(handler_spec_json, bridge)` (+ `_handlers_of`):
  imports the task entry, finds the `@ui.handler(name)` (first registration wins, documented),
  calls it with the args dict, classifies the outcome exactly like runs (SUCCEEDED with
  result.data / FAILED HANDLER_NOT_FOUND|HANDLER_RESULT|EXCEPTION / CANCELLED / WAITING_USER).
  Handler trigger type is "ui" with payload {handler, args}; runtime run_id = "ui-<handler>".
- `autohost/ui.py`: dialog (buttons ≤4, "" on dismissal/timeout), form (fields validated:
  name unique + identifier-shaped, type ∈ text|number|switch|select, select needs choices,
  ≤24 fields; returns {name: value} or None), overlay (duration 250..30000, returns host verdict),
  console.write (emits redacted `ui.console` events, level info|warn|error).
- `autohost/notify.py`: actions validated — ≤3, each {label, handler, args?}.
- Stubs: ui.pyi (form/overlay signatures + console), notify.pyi (Any-typed actions).
- Sim: FakeDevice._ui_prompt extended with dialog/form/overlay (scripted via prompt_answers:
  str answers dialogs, dict answers forms; missing/TIMEOUT = honest timeout).
- Tests: tests/test_ui.py (13) + tests/test_ui_handlers.py (11, run_ui_handler contract pinned via
  a JsonBridge over SimTransport). Suite: 235 tests OK (was 211).

Stage Summary:
- The Python half of the UI-handler contract is frozen: HandlerSpec JSON in, run-outcome JSON out.
- Console writes are emit-only events (no broker capability) — logview feeds off run events.

---
Task ID: S11-3..S11-6
Agent: main (session 11)
Task: Phase 7 part 1 — Kotlin: UI-handler path, imperative ui.*, Tier B renderer, Tier C host

Work Log:
- `:core:model`: TaskManifest gains `ui: UiSpec?` (mode auto|declarative|html + file; matches
  schemas/task.schema.json if/then). New `taskui/DeclarativeUi.kt` — Tier B document model,
  fail-closed parser with JSON-path errors (DeclarativeUi.UiDocError), the 16-widget v1 set,
  actions (run/run_stage/call/navigate/confirm), UiBindingScope (config/profile/run/state/input;
  run.* whitelisted to status/stage/reason/attempt), UiBindings evaluator (whole-string placeholder
  keeps the native type — same rule as flow templating; unresolved renders empty + warns),
  `when` visibility truthiness; table.rows accepts a static array OR a binding string resolved at
  render time. DeclarativeUiTest (10, JUnit4).
- `:core:engine`: `taskui/UiHandlerPolicy.kt` — UiHandlerSpec.build (fails closed on names/paths),
  UiHandlerGate (single-flight per task, refuse-not-queue), UiHandlerOutcome (parse worker JSON;
  BUSY/TIMEOUT sentinels; 30 s cap constant), UiHandlerIdlePolicy (30 s idle stop). Test (6).
  `host/NotifyProvider.kt` — the missing notify.post provider (pure JVM, NotificationSink
  fun-interface): validates ≤3 actions {label, handler, args?} + progress 0..1, returns {id}.
  Test (3).
- AIDL `core/broker/.../IUiHandlerWorker.aidl` — callHandler(specJson, broker) blocking + ping.
- `:runtime:python`: UiHandlerWorkerService rewritten from the P1 stub — Chaquopy on first use,
  single-flight, PyBridge(broker, runToken-from-spec), calls autohost._runtime.run_ui_handler,
  32 s deadline with cooperative request_cancel + 3 s grace then honest HANDLER_TIMEOUT, idle
  self-stop after 30 s (low-RAM rule §5.2), worker log sink → logcat autohost.ui.
- `:app/taskui/UiHandlerController.kt` — per call: load latest version/manifest/config, build
  grants (GrantBuilder), mint a short-lived RunGrants token ("ui-<taskId>", profileId=null,
  empty profile — handlers never get profile secrets), copy token into spec, bind the worker,
  withTimeoutOrNull(30 s), revoke token, unbind. Busy = refuse.
- `:app/taskui/TaskUiController.kt` — binding scope assembly (config + latest run status/stage/
  reason/attempt + state), state patches (handler results' "state" key merged), console lines
  (ui.console events of the latest run via data.runs.eventsAfter), action dispatch (run with
  optional stage payload, call with {{input.*}} bound into declared args, navigate).
- `:app/prompt`: UiPromptProvider gained dialog/form/overlay methods (PendingPrompt extended with
  buttons/fields; validation mirrors the SDK; overlay via the new UiTextOverlay —
  TYPE_ACCESSIBILITY_OVERLAY through the a11y service's WindowManager, honest {"shown": false,
  "reason": "ACCESSIBILITY_OFF"} when disconnected); PromptActivity renders dialog buttons +
  form fields (text/number/switch/select, secret masking). AppGraph NOW REGISTERS uiPromptProvider
  (P5-06 first-build gap closed) and notifyProvider.
- `:app/ui/taskui/DeclarativeRenderer.kt` — Compose renderer for the parsed document (all 16
  widgets; markdown as plain lines — honest subset; two-step confirm dialog; "provided by the
  task" trust footer; inputs collected into {{input.*}}).
- `:app/ui/taskui/HtmlUiHost.kt` — Tier C WebView: JS on, everything else off; non-file requests
  denied 403, off-bundle file paths denied 404 (canonicalized under the task root); JS bridge
  object "autohost" (invoke → runBlocking handler call, state, binding); run-status pushes via
  evaluateJavascript on scope changes; missing file = honest error page.
- `:app/ui/TaskScreens.kt` — task detail gains a UI tab for declarative/html manifests (Tier A
  tasks keep using Config); UiSection loads the doc, renders Tier B or C, navigate actions switch
  tabs; the renderer panel is height-bounded (nested LazyColumn rule).
- `:app/notify/AndroidNotificationSink.kt` — notify.post → `alerts` channel notification, actions
  become PendingIntents to NotificationActionReceiver; receiver gained ACTION_RUN_HANDLER →
  graph.uiHandlers.call via goAsync (result logged; failures never crash the host).
- AppGraph.startRun gained an optional triggerPayload param (run_stage plumbing; StartRequest
  already carried it).

Stage Summary:
- The UI-handler spine works end to end on paper: Tier B button / Tier C bridge / notification
  action → UiHandlerController → :ui worker → run_ui_handler → @ui.handler → outcome → surface.
- ADR-055 records: :app placement deviation (no :feature: split), the handler contract, sandbox
  rules, the console-as-events decision, and the v1 limits (markdown plain, profile-picker v1,
  checkpoint-seeded state deferred).

---
Task ID: S11-7
Agent: main (session 11)
Task: Phase 7 part 1 — samples + docs + packaging

Work Log:
- samples/task-ui-demo (Tier B): task.json (declarative, file ui/main.json), main.py
  (greet/add_item/reset handlers + counters.json), ui/main.json exercising the widget set,
  README. samples/task-ui-html (Tier C): task.json (html), main.py (increment/reset),
  ui/index.html + app.js + app.css with strict CSP meta + bridge usage, README.
- Root schema suite passes with both new samples auto-validated (29 OK); SDK suite 235 OK.
- Docs: PROGRESS.md (P7-01..05 evidence + CAP-notify.post/CAP-ui.prompt + Last updated),
  DECISIONS.md (ADR-055), REPO_LAYOUT.md, UI_SPEC.md (S16/S17 rewritten to built behavior),
  HANDOFF.md (session-11 part-1 snapshot).
- Verification (sandbox-applicable only): python3 tools/progress.py --check; both unittest suites;
  gen_artifacts --check. Kotlin/Gradle remains owner-verified (no Gradle/SDK here).
- Packaged: download/autohost-phase7-part1.zip.

Stage Summary:
- Phase 7 part 1 complete as written: P7-01..P7-05. Part 2 (P7-06..P7-12: bubble, tiles,
  shortcuts, widget, RUN_TASK intent, inspector overlay, volume-key stop) awaits "continue".

---
Task ID: S12-1..S12-6
Agent: main (session 12)
Task: Phase 7 part 2 — P7-06..P7-12 (bubble, tiles, shortcuts, widget, RUN_TASK intent, inspector, volume-key stop)

Work Log:
- Pure policies (all unit-tested): `:core:engine/surfaces/` — TileBindings (JSON slot bindings for
  the 5 tiles, fail-closed parse/format), ExternalRunRequest (fail-closed RUN_TASK contract:
  constant-time token via MessageDigest.isEqual, identifier-shaped fields, JSON payload, result
  codes STARTED/REJECTED/BAD_TOKEN/TASK_NOT_RUNNABLE), BubblePolicy (auto=UI-acting run / always /
  off). `:core:model/SelectorBuilder.kt` — smallest re-finding selector for a node (id → unique
  text → unique desc → class+index with text-anchored parent → pkg+class), round-trips through
  SelectorEngine (SelectorBuilderTest 6).
- a11y module: VolumeKeyHoldDetector (pure hold state machine; threshold fires once while held,
  early release dead, repeats ignored — 8 tests). AutoHostAccessibilityService.onKeyEvent: armed
  DOWN consumed (volume HUD quiet during hold), DOWN schedules the threshold poll, fires
  A11yBridge.fireGlobalStop exactly once; XML accessibilityFlags += flagRequestFilterKeyEvents.
  A11yBridge gained keyFilterProvider + globalStopHook (plain volatile slots, same process).
- App surfaces package: SurfacesPrefs (SharedPreferences: bubble mode, tile bindings, external
  token lazy-UUID + regenerate, key filter config, widget task ids); SurfaceRuns (the ONE
  run-start path for all surfaces — trigger surface|external + source payload; throttled widget
  refresh); BubbleOverlay (draggable TYPE_ACCESSIBILITY_OVERLAY strip: status/Open/Stop) +
  BubbleController (runs + a11y-connection collectors; per-run uiActing recorded by AppGraph.startRun);
  AutoHostTileService + Tile1..5Service (bound→run, unbound→open Surfaces; PendingIntent form on
  API 34+); Shortcuts (requestPinShortcut + dynamic sync ≤4) + SurfaceRunActivity (translucent
  noHistory trampoline → run, refused→open app); RunTaskReceiver (exported, token-guarded);
  TaskRunsWidget (Glance 1.1.1: 5 rows task+last Room status+Run) + widget xml; InspectorOverlay
  (full-screen bounds canvas, tap→deepest/smallest node→SelectorBuilder→clipboard, Copy/Exit
  strip) + InspectorController (live foreground scope via ForegroundTracker, never AutoHost;
  window-change + 1 s poll refresh); ui/SurfacesScreen (bubble chips, 5 tile bindings + API 33
  requestAddTileService, widget picker, external token + adb example, inspector start/exit, key
  filter switch/key/hold, pin shortcuts); PinShortcutRow on task detail; SettingsScreen gained the
  Surfaces card; Routes.SURFACES wired through MainActivity/AppNavHost (tile cold-start route extra).
- Wiring: AppGraph — globalStop(reason) kill switch, bubble.start() + A11yBridge hooks in init,
  runUiActing map (startRun records, terminal collector forgets), SurfaceRuns.refreshWidgets in the
  runs collector. RunForegroundService.start hardened (catches background FGS refusal, honest log).
  Manifest: 5 tile services, widget receiver, SurfaceRunActivity, exported RunTaskReceiver.
  libs.versions.toml + app gradle: androidx.glance:glance-appwidget 1.1.1.
- Tests: SurfacesPoliciesTest (13), SelectorBuilderTest (6), VolumeKeyHoldDetectorTest (8).
- Docs: PROGRESS (P7-06..12 evidence, dashboard regenerated, written 73%), DECISIONS (ADR-056),
  HANDOFF (session-12 snapshot + part-2 first-build notes), REPO_LAYOUT, UI_SPEC (S19/S20 rewritten
  to built behavior + S21 Surfaces settings), ANDROID_MANIFEST (tile/widget/receiver/trampoline).
- Verification (sandbox-applicable): progress.py --check ok; schema suite 29 OK. Kotlin/Gradle
  remains owner-verified.

Stage Summary:
- Phase 7 complete as written (P7-01..P7-12). Every §10.3 surface row has code; Gate 7 (G7-a..e)
  awaits the phone. Next: Phase 8 (capability breadth, raw bridge, event triggers) on "continue".

---
Task ID: S13-1..S13-7
Agent: main (session 13)
Task: Phase 8 part 1 — P8-13 insecure switches, P8-02 EventHub + sources, P8-03 trigger rules runtime, P8-01 notification listener, P8-04 Triggers UI, P8-05 clipboard, P8-06 intents (+ spec/artifact/SDK/sim/tests/docs)

Work Log:
- P8-13 first (foundation for everything risky): `:core:broker/security/InsecureFeatures.kt`
  (pure policy: 9 KNOWN switches, permission-then-capability feature map, deny-wins-over-relaxed,
  stale-id-dropping parse; InsecureFeaturesTest 8) + `interceptor/InsecureFeatureInterceptor.kt`
  appended after Scope through the existing BrokerService `interceptors` hook (pre-declared
  INSECURE_FEATURE_DISABLED / DISABLED_BY_SETTING / OPEN_INSECURE_SWITCH error shape);
  `:app/security/InsecureSwitchStore.kt` (volatile snapshot over 3 app_settings keys, audit WARN
  on every change); Settings gained the "Developer & security" section (relaxed toggle + per-feature
  switches with plain-language hints). ADR-059.
- P8-02: `:core:engine/events/` — pure EventHub (kind fan-out, self-unsubscribing subscribers,
  throwing-subscriber isolation, bounded 200 ring; EventHubTest 7), TriggerEvent, EventSourceCatalog
  (every §11.9 kind with access class, required grant, params doc; FGS/SERVICE kind splits);
  `:core:engine/host/EventsProvider.kt` = device.events.recent. `:app/events/` — AndroidEventSources
  (dynamic receivers: screen on/off, user.present, power.*, EDGE-triggered battery with 15/25
  hysteresis; ConnectivityManager default-network callback; wifi_ssid only with fine location —
  documented), EventHubController (a11y foreground-transition + toast bridges, notification-bridge
  feed, receiver-source start/stop), EventHubService (low-priority specialUse FGS existing exactly
  while an enabled rule needs receivers — plan §11.8). ADR-057.
- P8-03: `:core:engine/triggers/` — EventMatcher (fail-closed regexes, battery op/percent, network
  types, external name, chain task+on), TriggerDecisionEngine (max_launch_count → cooldown →
  debounce, two-phase debounce protocol), BusyGate (drop default / queue bounded oldest-dropped-first /
  replace); TriggerRulesTest 17. `:app/schedule/TriggerController.kt` — hub subscription, §11.8
  grant gate (required grant per source from the catalog, checked against the target task's granted
  permission ids via the new TriggerRepository.grantedPermissionsOf), debounce timers, busy queue
  drained on run-terminal, replace = new AppGraph.cancelRunsOfTask, externalFire(name), task.chain
  via onRunFinished, time.* rules armed through the SHARED scheduler backends with `trig-<ruleId>`
  fireIds (AlarmReceiver routes; BootReceiver + ReconcileWorker + app-start reconcile re-arm);
  TriggerRepository.recordEvent reuses the schedule_events stream. Dev API: RpcRouter + DevApiServer +
  DevApiWiring gained `triggers.catalog`; `triggers.status` is now live (counters, cooldown remaining,
  debouncing, busy depth, grant_ok); `triggers.test {fire:true}` dispatches a synthetic event through
  the guards (fake backend updated). ExternalRunRequest gained the token-guarded `--es trigger` form
  (TRIGGER_AND_TASK fail-closed) and RunTaskReceiver routes it to externalFire. ADR-058.
- P8-01: new `:capabilities:notifications` module — NotificationBridge (process-local snapshot +
  posted/removed flow, sanitised fields only), AutoHostNotificationListener (snapshot on connect;
  press/dismiss handlers published into volatile slots — listener-only operations),
  NotificationAccessPolicy (pure ENABLED_NOTIFICATION_LISTENERS parse), NotificationsProvider
  (list/read/press {index|label}/dismiss/dismiss_all/wait_for poll+TIMEOUT; package scoping from the
  run's grant; availability = live connection + NEEDS_SETUP/OPEN_NOTIFICATION_LISTENER_SETTINGS);
  manifest service; HealthEnv.notificationListenerConnected wired; NotificationsProviderTest +
  NotificationAccessPolicyTest (8 JVM tests, resetForTest seam). ADR-060.
- P8-04: `:app/ui/TriggersSection.kt` on the task-detail Overview tab (UI_SPEC S22): per-rule status
  card (30 s auto-refresh line), enable/test/delete, source-driven Add-trigger editor (source chip
  row incl. time.*, per-source params, cooldown/debounce/max_launch_count, busy-policy chips + queue
  max, inline TRIGGER_RULE_INVALID), red §11.8 grant-gate notes; writes via TriggerRuleEntity +
  triggerController.refresh().
- P8-05: `:capabilities:clipboard` — ClipboardPolicy (focus gate, honest device.ime.clipboard_get
  hint + SELECT_IME fix), ClipboardProvider (get/set, 1 M cap; AppFocusTracker wired from activity
  lifecycle in AppGraph), AndroidClipboard; ClipboardProviderTest 4. P8-06: `:capabilities:intents` —
  IntentPolicy (http(s)-only URLs, closed 14-page settings map, grant-scoped actions/packages with
  the http(s)-VIEW exception, explicit-action broadcasts), IntentProvider (dispatch seam, honest
  {dispatched, verified:false}, SCOPE_VIOLATION/INTENT_NOT_GRANTED), AndroidIntents;
  IntentPolicyTest + IntentProviderTest (6). settings.gradle.kts + app/build.gradle.kts updated —
  and the app module gained the previously-missing ime/screen/projection deps (first-build fix).
- Specs/artifacts/SDK/sim/tests: 4 new capability entries in schemas/capability-specs.w1.json
  (device.notifications/clipboard/intent/events); **gen_artifacts.py now also syncs the broker
  resource copy** (byte-for-byte, drift-gated — it had silently diverged before); CAPABILITIES.md +
  _capabilities_gen.py regenerated; SDK `clipboard.py`/`intents.py`/`notifications.py`/`events.py`
  + stubs + `__init__` exports (caught and fixed a builtin-`list` self-shadowing bug in
  notifications.list); sim FakeDevice gained the 4 capability handlers + inject_notification /
  emit_event helpers; new `tests/test_p8_capabilities.py` (16).
- Docs: PROGRESS (P8-01..06/13 evidence + dashboard regenerated + focus line), DECISIONS
  (ADR-057..060), REPO_LAYOUT (capabilities + app sections incl. deviations), DEVAPI (triggers.*),
  UI_SPEC (S22), ANDROID_MANIFEST (listener service + EventHub FGS), HANDOFF (session-13 snapshot,
  status table, ADR summary, part-1 first-build notes, part-2 next steps).
- Verification (sandbox-applicable): progress.py --check ok; schema suite 29 OK; SDK suite 251 OK;
  gen_artifacts --check ok. Kotlin/Gradle remains owner-verified (no Gradle/SDK here).
- Packaged: download/autohost-phase8-part1.zip.

Stage Summary:
- Phase 8 part 1 complete as written (7 of 16 tasks). The event/trigger backbone, the first
  capability wave and the two-level switch gate all have code + tests. Part 2 (P8-07..P8-12,
  P8-14..P8-16 + webhook trigger path + dev_api_remote enforcement + CompatReport row) awaits
  "continue".


---

## Session 14 — Phase 8 part 2 (P8-07..12, P8-14..16 + dev_api_remote) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P8-07 files** — `:capabilities:files`: SharedFilesPolicy (tree-verbatim grant, relative-path guard, media flag, 5 MB caps), FilesSharedProvider (SAF list/read/write/delete + MediaStore media_* over AndroidSharedFiles), FilesPrivateProvider (W1 first-build gap: no host provider had ever been registered for files.private); SDK files.shared + stub; sim add_tree/_files_shared.
- **P8-08 net** — `:capabilities:net`: NetPolicy (wildcard domain allowlist, scheme split, header redaction), HttpProvider+JavaHttpEngine (W1 net.http gap closed), WsClientProvider+JvmWsSocketFactory (RFC 6455 client, run-scoped handles closed at run-terminal), WebhookServer (POST /hook/<name>, token-mandatory, localhost + LAN opt-in, 401 audited) + NetServerProvider; app WebhookController → TriggerController.externalFire; Settings → Webhooks (S23); ADR-061.
- **P8-09 audio/power** — `:capabilities:audio`: AudioProvider (volume/ringer/DND-honest/TTS/vibrate/torch/tones over AndroidAudio incl. TTS warm-up) + PowerProvider (battery, task-scoped wake lock with 30-min cap per runId, keep-awake, releaseAll on the run-terminal collector); SDK audio.py/power.py.
- **P8-10 screen control + ui.overlay** — ScreenControlPolicy + ScreenProvider wake/dismiss_keyguard/lock/brightness (a11y GLOBAL_ACTION seam; secure keyguard never bypassed; WRITE_SETTINGS-gated brightness); UiOverlayProvider/OverlayPolicy + app TaskOverlaySink (TYPE_ACCESSIBILITY_OVERLAY, per-run views); SDK device.screen control + ui.highlight/panel/overlay_clear.
- **P8-11 settings/self/chain** — engine host SystemSettingsProvider (system/secure/global reads) + TasksRunProvider (fail-closed tasks allowlist, CHAIN_DEPTH 3 terminating cycles, honest await); app ChainRunner (startRun trigger=chain + payload depth); schedule.self spec entry (ctx.next_run existed since P4-11); SDK tasks.py/device.settings_read/many.
- **P8-12 raw bridge + import guard** — ADR-062; RawBridgeProvider (authorize handshake; gates are the interceptor chain); SDK android.py rewritten (authorize → jclass/system_service; loud CapabilityUnavailable before); _scan.raw_bridge_calls (Chaquopy imports + autohost.android calls; lookalike-safe) + ScanReport.rawBridge + ImportPipeline(rawBridgeGuard) → RAW_BRIDGE_UNDECLARED; fixed a corrupted DESKTOP_ONLY suggestion line (bytecode had been running the correct form).
- **P8-14 lazy permissions** — LazyPermissions pure map (+ tests); app/fix/FixHub (every fix action → Intent; in-app routes; unrouteable honest-false); PermissionRequestActivity (runtime asks + SAF pick with persistable trees → saf.last_tree; PermissionOutcomes); HealthScreen Fix buttons live (P2-19 no-op removed); manifest entry.
- **dev_api_remote enforcement** — DevApiPolicy mutable (fixed the enabled-forever-false first-build bug that would always have answered DEV_API_OFF); start() binds 0.0.0.0 only with the switch; restart() rebinds; InsecureSwitchStore.refresh → refreshDevApiPolicy; Settings toggle syncs policy.enabled; CapabilityResolver switch rows corrected to FEATURE ids (device.ui never gated; android.api.direct → raw_api) + relaxed-mode test.
- **P8-15 showcase** — samples/capabilities-showcase (13 optional capabilities, 12 honest sections; sim-tested ×2). **P8-16 checklist** — docs/CONVENTIONS.md § Capability module template checklist (8-step §6.3 table).
- **AppGraph/manifest** — 10 new providers registered; run-terminal cleanup extended (power locks, ws handles, overlays); webhook reconcile at app start; PermissionRequestActivity in the manifest; app deps + 4 modules.

**Tests:** Python 283 OK (251 + 30 test_p8_part2 + 2 showcase), schema 29 OK, mcp_eval 3 PASS, gen_artifacts/progress/sync_sdk --check all ok. New JVM tests written (owner-verified later): FilesModuleTest 12, NetModuleTest 18, AudioPowerProviderTest 8, ScreenControlTest 11, SettingsAndChainProvidersTest 8, RawBridgeProviderTest 2, LazyPermissionsTest 3, DevApiTest +2, CapabilityResolverTest +1; ScreenProviderP3Test updated to the new SystemState interface.

**Docs:** PROGRESS (focus/updated lines, P8-07..16 evidence), HANDOFF (session-14 snapshot: head, 9 new status rows, ADR-061..063 summary + first-build notes, §4 next steps → Phase 9, §5 checklist), DECISIONS (ADR-061..063), REPO_LAYOUT (:capabilities:{files,audio,net,rawbridge}, engine host/triggers/events/health sections, app/fix+webhook+TaskOverlaySink), GATES (G8 written-evidence pointers, device items explicit), UI_SPEC (S23 Webhooks), ANDROID_MANIFEST (PermissionRequestActivity), CONVENTIONS (P8-16 table), CAPABILITIES regen (26 capabilities).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 29 OK; SDK suite 283 OK; gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3. Kotlin/Gradle remains owner-verified.
- Packaged: download/autohost-phase8-part2.zip.

---

## Session 15 — Phase 9 part 1 (P9-01..07: ADB client, transports, shell providers) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P9-01 ADB client core** — `:capabilities:shell`: `AdbProtocol.kt` (24-byte LE framing, CNXN/AUTH/OPEN/OKAY/CLSE/WRTE, magic+CRC32 validation, MAX_PAYLOAD 4096), `AdbKeyStore.kt` (RSA-2048 identity generated on first use + persisted PKCS#8 b64 under filesDir/adb, SHA1withRSA token signing, the adbd-native public-key struct: words/n0inv/modulus/rr/exponent little-endian via BigInteger, base64 + " name\0"), `AdbClient.kt` (CNXN/AUTH handshake with one public-key offer on the second challenge; `shell,v2:` service — separated stdout/stderr + real exit code over 5-byte-chunked frames; honest legacy `shell:` fallback with exit_code null; binary-safe `stdoutBytes` for screencap).
- **P9-02 AdbTcpTransport** — fresh connection per exec, connect+read timeouts, memoized status probe (30 s cooldown) with Live/AwaitingAuthorization/NeedsSetup(SHELL_TIER_NOT_PAIRED|SHELL_CONNECT_TIMEOUT), invalidateStatus before exec; `ShellTransports` ordered holder (first live wins).
- **P9-03 WirelessPairingTransport** — ADR-065; `WirelessPairing.kt` SPAKE2 (RFC 9382) P-256-SHA256-HKDF-HMAC pure core (SEC1 compressed M/N decode, on-curve member checks, LE64 transcript, Ke/KcA/KcB schedule, HMAC confirmations); `WirelessPairingTransport` pair() over the `PskTlsChannel` seam (JSSE factory probes for a PSK-capable provider — Conscrypt on device); honest draft status (TLS_PSK_PROVIDER_MISSING / WIRELESS_PAIRING_DRAFT) until Gate-9 byte-verifies the framing.
- **P9-04 Shizuku + Root transports** — ADR-064; `ShizukuTransport.kt` (Exec seam, memoized probe) with the Shizuku-API-backed `app/shell/ShizukuProcessExec.kt` (the only file touching `dev.rikka.shizuku:api:13.1.5`, added to libs.versions.toml + THIRD_PARTY_NOTICES); `RootTransport.kt` (su -c, `su -c id` probe memoized 60 s, ProcessFactory seam so JVM tests use real ProcessBuilder processes).
- **P9-05 device.shell + allowlist** — ADR-066; `CommandAllowlist.kt` (fail-closed: missing grant deny, empty allowlist deny, `*`/`?` globs, UNCONDITIONAL metacharacter deny `; & | \` ( ) < > \n \r` + `$`, 4000-char cap), `ShellCommands.kt` builders (uiautomator dump, screencap, input tap/swipe/text with escaping, keyevent map, dumpsys), `ShellProvider.kt` (run {command, timeout 1..120s} + transports status; the `shell` switch consumed upstream by the P8-13 interceptor); `device.shell` spec entry (risk high) + gen_artifacts regen (3 copies, drift-gated).
- **P9-06 ShellUiProvider** — the T3 `device.ui` fallback link, same 15-method surface as A11yUiProvider, registered AFTER a11y (first-available rule); reads via `uiautomator dump` → `UiautomatorDump.kt` (dependency-free recursive pull parser, no android.util.Xml — JVM-testable); acts via `input` (click/long_click honest verified:false unless expect observed, tap/swipe/press, set_text focus+clear+escape, scroll_until, wait_for/settle by signature, keyboard_visible via dumpsys); secret:true REFUSED (TEXT_INPUT_REJECTED — ADR-066); device.ui.act package scoping enforced.
- **P9-07 ShellScreenshotProvider** — the T3 `screencap -p` link after the projection provider; binary-safe via stdoutBytes, PNG IHDR width/height, evidence-dir write with the standard {path,width,height,provider} shape, honest SCREENSHOT_BLOCKED on non-PNG (legacy CRLF corruption), region/scale via android.graphics when available.
- **App wiring** — AppGraph: adbKeys + ShellTransports chain (adb-tcp 127.0.0.1:5555 → wireless draft → shizuku → root) + three provider registrations in fallback order; Settings switch hint updated (Phase 9 note dropped).
- **SDK + sim + tests** — `autohost/shell.py` (run/transports, bounds validated SDK-side) + `.pyi` + __init__ exports; sim `_device_shell`/`add_shell_tier`/`_shell_verdict` (mirrors the Kotlin allowlist semantics); new Python `test_p9_part1.py` (13: allowlist semantics, metachar deny incl. pattern-matching-but-refused, no-grant/empty gates, awaiting-authorization, glob parity); new JVM tests written (owner-run): ShellModuleTest (CommandAllowlist 7 + ShellProvider 7 + UiautomatorDump 3 + providers 7), AdbClientTest 7 (fake adbd on real loopback sockets: both handshake paths, shell,v2 round-trip with exit 3, legacy fallback, CRC rejection, framing round-trip, keystore persist/restore, signature verify), WirelessPairingTest 5 (RFC 9382 Appendix-B vector pA byte-exact + cross-implementation transcript pins from scripts/spake2_ref.py + wrong-password divergence + member check).
- **Crypto validation chain** — scripts/spake2_ref.py (independent Python reading of RFC 9382) reproduced the official vector's pA prefix byte-for-byte, then generated the full transcript pins embedded in WirelessPairingTest.
- **Docs** — PROGRESS (P9-01..07 evidence, focus line, session 15, dashboard regen: Phase 9 written 46%), HANDOFF (session-15 snapshot, 4 new status rows, ADR-064..066 summary + first-build notes, §4 next steps → part 2, §5 checklist + shell module), DECISIONS (ADR-064..066 full), REPO_LAYOUT (:capabilities:shell file list + app additions), GATES (G9 written-evidence pointers, device items explicit), CONVENTIONS (checklist rows device.shell + shell device.ui link), VERSIONS (Shizuku 13.1.5), THIRD_PARTY_NOTICES (Shizuku Apache-2.0), README (status refresh), CAPABILITIES regen (27 capabilities).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 29 OK; SDK suite 299 OK; gen_artifacts --check ok; sync_sdk --check ok; golden vectors unchanged; mcp_eval PASS×3. Kotlin/Gradle remains owner-verified.

**Part 2 next (P9-08..12):** UiAutomationProvider (exclusive with a11y), device.app.manage + installer auto-accept, radios/display/mock-location/logcat, SecureSettingsHelper self-heal (a11y re-enable, IME auto-select, keep-awake), pairing guide UI + post-reboot reminder + OPEN_SHELL_SETUP routing; device.settings write path (W4).


---

## Session 16 — Phase 9 part 2 (P9-08..12: UiAutomation link, app manage, logcat/radios, self-heal, setup UX) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P9-08 UiAutomationProvider** — ADR-068; `:capabilities:shell`: `UiAutomationExclusivity.kt` (pure — start refuses on a11y-connected/toggle-off; reconnect closes the session), `UiAutomationSessionClient.kt` (line-JSON client over the `SocketFactory` seam; ping/dump/click/text/gesture/key/screenshot/quit, typed `RpcError`); `app/shell/UiAutomationRunner.kt` (Instrumentation via `am instrument -e port <p>`, no `-w`; loopback ServerSocket, one client; a11y tree serialized to the uiautomator XML shape in document order so `UiautomatorDump` + flat indexes apply unchanged); `UiAutomationProvider.kt` (same 15-method device.ui surface, registered between a11y and shell.uiautomator; Unsupported(UI_AUTOMATION_SESSION_DOWN) when idle; honest `performed` verification; **secret:true ALLOWED — loopback socket, not a command line**); `app/shell/UiAutomationController.kt` (spawn via first live transport + bounded ping probes; `onA11yEvent` close hook fed by an AppGraph collector on A11yBridge.service; experimental toggle persisted `ui.automation.experimental`, default off); manifest `<instrumentation>`.
- **P9-09 device.app.manage** — ADR-069; `:capabilities:app`: `AppManagePolicy.kt` (pure verdicts + the confirm rail) + `AppManageProvider.kt` (force_stop = shell `am force-stop` VERIFIED via exit code → T1 App-info UI → killBackgroundProcesses BEST_EFFORT/FAILED; clear_state/uninstall/install need `confirm:true`; grant/revoke with fully-qualified-name validation, verified:false; install mode pm = `stat` → `cat|pm install -S` VERIFIED on Success; mode installer → `app/install/InstallController.kt` ACTION_VIEW via FileProvider (manifest provider + res/xml/file_paths.xml) with `AutoAcceptPolicy.Flow.INSTALL` pending — the existing gate taps the new `PACKAGE_INSTALLER_CONFIRM` dialog ids + flow-dialog affinity (projection never taps installer buttons, policy-tightened with `accepts()`); honest CONFIRMED/WAITING_USER/FAILED outcomes, never a package readback).
- **P9-10 logcat/radios/display/mock + settings write** — `LogcatOutput.kt` (pure epoch parser) + `LogcatProvider.kt` (snapshot with tag/level/contains/package→pidof/max_lines/since_ms + clear; `logcat` switch upstream); `DeviceControlProviders.kt` (`RadioProvider` svc/airplane-two-step, honest verified:false; `DisplayProvider` uimode/orientation/font_scale; `MockLocationProvider` appops with a REAL `appops get` readback; `mock_location` switch upstream); `ShellCommands.kt` grew all builders under the quoting discipline; **ADR-067 settings write**: `SystemSettingsProvider.write` behind `device.settings.write` + the `Writer` seam → `app/shell/SecureSettingsHelper.kt` (direct WRITE_SECURE else shell `settings put`; one log door), `SettingsWritePolicy` (protected-key denylist — self-heal ownership; confirm for secure/global; caps; read-back verification); specs entries device.app.manage/device.logcat/device.radio/device.display/device.location.mock + the device.settings write method; gen_artifacts regen (3 copies).
- **P9-11 self-heal extensions** — `app/selfheal/ShellSelfHeal.kt`: shell-fallback `ensureAccessibility` (P4-14 keeps the direct path), `selectIme`/`restoreIme` (default_input_method, previous id returned), `keepAwake`/`keepAwakeRestore` (stay_on_while_plugged_in, prior value read first); protected keys are written HERE deliberately — tasks can never reach them through the policy.
- **P9-12 setup UX + reminder** — SettingsScreen "Shell tier (advanced)" section (UI_SPEC S24): live transport chips, Check now (off-main re-probe + `shell.was_live` memory), Copy adb steps, the WRITE_SECURE grant block + CLI pointer, the experimental UiAutomation toggle with exclusivity copy; FixHub routes OPEN_SHELL_SETUP; `app/shell/RearmReminder.kt` (boot/replace/app-start, `shell.was_live` gate, `shell_reminders` channel notification) wired in BootReceiver (appScope) + AppGraph init. PC helper: the P5-12 CLI commands already print the rituals — unchanged by design.
- **SDK + sim + tests** — `app_manage.py`/`logcat.py`/`devicectl.py` + `.pyi` stubs + `device.settings_write` + `__init__` exports + sync_sdk copy; sim `add_grants(switches=)`/`add_logcat` helpers + `_device_app_manage`/`_device_logcat`/`_device_radio`/`_device_display`/`_device_mock_location` + settings-write mirroring the protected-key policy; new Python `test_p9_part2.py` 20 (two-level gates, confirm rails, honest filters, mock verified, settings write round-trip); new JVM tests written (owner-run): ShellPart2Test 16, UiAutomationTest 10, UiAutomationControllerTest 5 (app module), AppManageTest 8, SettingsWriteTest 6, InstallerAutoAcceptTest 4.
- **Docs** — PROGRESS (focus/updated lines, P9-08..12 evidence, dashboard regen: Phase 9 written 100%), HANDOFF (session-16 snapshot, Phase-9 status row, ADR-067..069 summary + first-build notes, §4 → Phase 10, §5 counts), DECISIONS (ADR-067..069 full), REPO_LAYOUT (shell part-2 files + app additions), GATES (G9 written-evidence pointers per item), UI_SPEC (S24), ANDROID_MANIFEST (instrumentation + FileProvider), CONVENTIONS (6 new checklist rows), CAPABILITIES regen (32 capabilities).
- **Incident (fixed):** a HANDOFF.md edit script briefly truncated the file to 2 lines; restored byte-identical from autohost-phase9-part1.zip and re-applied the session-16 edits line-precisely (verified: 133 lines, all sections present).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 34 OK; SDK suite 319 OK (299 + 20); gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3. Kotlin/Gradle remains owner-verified.

**Phase 10 next (P10-01..08):** spikes (WebView automation, cookie isolation, CDP over ADB, OCR/template) → browser.webview/browser.a11y/browser.cdp-if-viable, vision.*, contacts/calendar/media/location/camera/mic/sensors/SMS (full flavor). Gate-9 device items join Gates 6/7/8 at the phone.

---

## Session 17 — Phase 10 part 1 (P10-01..05: browser tier, vision, spike protocols) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P10-01 spikes** — `docs/SPIKES.md` S25–S28: full procedures + measurable assertions (WebView real-input vs JS baseline ×50 latency, setDataDirectorySuffix cookie/localStorage isolation matrix, the CDP-over-adbd route for both our WebView and the user's Chrome, ML Kit/NCC/ZXing cost blocks); verdicts PENDING-ON-HARDWARE with each fallback already implemented (synthetic input_mode, PROFILE_ISOLATION_SHARED, our-WebView-only CDP, adaptive stride/region caps).
- **P10-02 browser.webview** — ADR-070; new `:capabilities:browser`: `BrowserPolicy.kt` (NetPolicy-mirroring domain allowlist, http/https + `task:<bundle-relpath>` with `..`-refusing containment, open/read permission split, MAX_PAGES 4), `ChallengeDetector.kt` (captcha/anti-bot/rate-limit/denied markers; login pages deliberately NOT challenges), `WebViewBrowserProvider.kt` (9 methods over the pure `Host` seam; `input_mode` honesty; type text never echoed; challenge objects ride page results), app `BrowserController.kt` (run-scoped registry, process-global profile lock, `task:` canonical containment, closeAll at run-terminal) + `BrowserActivity.kt` (visible surface: chrome strip + Close, back = close-through-controller; real MotionEvent streams DOWN→2×MOVE→UP, KeyCharacterMap real key events for ASCII, JS fallback flagged `synthetic`; per-profile setDataDirectorySuffix); manifest `.browser.BrowserActivity`; UI_SPEC S25.
- **P10-03 browser.a11y** — `A11yBrowserProvider.kt` (T1 user-browser link: VIEW intent + foreground wait, visible-text clicks, focused-field ACTION_SET_TEXT typing, visible-text extract, back-close; cookies refused `COOKIES_UNSUPPORTED_LINK`; `task:` refused) + app `A11yBrowserLink.kt` (SimpleA11yReader/NodeActions binding); registered after webview (first-available).
- **P10-04 browser.cdp** — ADR-071; `CdpClient.kt` (pure id-correlated JSON, event skipping, honest timeouts) + `CdpBrowserProvider.kt` (`adb forward tcp:<p> localabstract:webview_devtools_remote_app.autohost` through the shell transport → `/json/list` → WS; `status` carries `verified_on_hardware:false` + the S27 note; URL allowlist still applies; closeForRun removes the forward); app `CdpTransportFactory.kt` (HTTP target discovery + a ~60-line RFC 6455 client frame codec over the P8-08 `JvmWsSocketFactory`) + `VisionImages`; run-terminal teardown in the AppGraph collector.
- **P10-05 vision** — ADR-072; new `:capabilities:vision`: `VisionPolicy.kt`, `TemplateMatcher.kt` (pure zero-mean NCC, coarse stride + refine, per-call thread-safe needle prep), `OcrEngine.kt`+`MlKitOcrEngine.kt` (isolated ML Kit touchpoint), `QrDecoder.kt`+`ZxingQrDecoder.kt` (isolated ZXing touchpoint), `VisionProvider.kt` (`ocr`/`template_match`/`qr_decode`; `hint:true` on every result; run/task-scoped image containment); libs.versions.toml mlkit 16.0.1 + zxing 3.5.3; VERSIONS + THIRD_PARTY_NOTICES rows.
- **Rider fix** — `_scan.py:156` live NameError (`DESKTOP_ONLYodule` → `DESKTOP_ONLY[module]`); suggestions now say the browser capability is shipped; 2 regression tests.
- **Specs/artifacts/SDK/sim/tests** — 4 spec entries (browser.webview/browser.a11y/browser.cdp/vision) → 36 capabilities, gen_artifacts regen (3 copies, drift-gated); SDK `browser.py`/`vision.py` + stubs + `__init__` exports + sync_sdk copy; sim `_browser_webview`/`_browser_a11y`/`_browser_cdp`/`_vision` + helpers (add_browser_grant, add_web_page, add_a11y_browser, add_cdp, add_ocr, add_qr, set_template_result); new `tests/test_p10_part1.py` (33: webview 14 incl. the page-cap/id-allocator collision story, a11y 5, cdp 5, vision 7, scan regressions 2); new JVM tests written (owner-run): BrowserModuleTest 27, VisionModuleTest 15 (TemplateMatcher golden vectors: exact-position score 1.0, brightness invariance, true-negative).
- **Docs** — PROGRESS (P10-01..05 evidence + rider note + focus/updated lines + dashboard regen 54% written), HANDOFF (session-17 snapshot, 8 new status rows, ADR-070..072 + first-build notes, §4 part-2 next steps, §5 +352/browser/vision gradle lines), DECISIONS (ADR-070..072), REPO_LAYOUT (browser/vision module sections + app additions), GATES (G10-a/b/c written-side pointers), CONVENTIONS (4 checklist rows), UI_SPEC (S25), ANDROID_MANIFEST (BrowserActivity), VERSIONS, THIRD_PARTY_NOTICES, README (status refresh).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 29 OK (+5 gen_artifacts = 34); SDK suite 352 OK (319 + 33); gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3. Kotlin/Gradle remains owner-verified.
- Packaged: download/autohost-phase10-part1.zip.

**Part 2 next (P10-06..08 + riders):** contacts/calendar/media + location/camera/mic/sensors + SMS/telephony (full flavor) in `:capabilities:data`, the W3 riders (net.socket TCP/UDP, net.reverse device-initiated WS), Gate-10 samples (web-login-scrape + ocr-demo + challenge fixture), docs wrap-up.

---

## Session 17 (continued) — Phase 10 part 2 (P10-06..08 + W3 riders + Gate-10 samples) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P10-06 personal data** — ADR-073; new `:capabilities:data`: `DataPolicy.kt` (pure caps), `PersonalDataProviders.kt` (Contacts/Calendar/Media over `PersonalDataStores` seams + `LazyPermissionProvider`; base64 in the seam, NOT android.util in providers), app `app/data/AndroidStores.kt` (ContentResolver/MediaStore).
- **P10-07 hardware** — `HardwareProviders.kt` (Location with the honest BACKGROUND_LOCATION gate + stale marking, one-shot Camera/Mic, permissionless one-shot Sensors) + `app/data/AndroidHardware.kt`.
- **P10-08 sms/telephony** — ADR-074; `SmsTelephonyProviders.kt` (full-flavor gates, two-level sms/telephony switches upstream, premium/short-code rails, per-run 10-send budget cleared at run-terminal, delivered:false/verified:false always; wait_for on an injected Clock) + `app/data/AndroidTelephony.kt`; `refreshDataPermissionState()` in AppGraph.
- **W3 riders** — ADR-075; `:capabilities:net` `SocketProviders.kt`: SocketProvider (TCP ≤8 run-scoped handles + one-shot UDP, NetPolicy domains) + ReverseWsProvider (device-initiated WS; token = first frame never a URL; plain ws:// localhost-only); `net_reverse` added to InsecureFeatures.KNOWN + featureFor; run-terminal teardown + SMS budget clear wired in the AppGraph collector.
- **Gate-10 samples** — `samples/web-login-scrape` (task.json with `secret_fields`, main.py, web/login.html + web/challenge.html fixtures, README) and `samples/ocr-demo` (vision.ocr/template_match/qr_decode demo, needle.png placeholder, README); **all three Gate-10 behaviours sim-proven**: scrape SUCCEEDED (cookie true, secret never echoed), challenge → WAITING_USER:SECURITY_CHALLENGE via the `open_challenge` demo switch, OCR region read with hint:true.
- **Specs/artifacts/SDK/sim/tests** — 11 spec entries → 47 capabilities + gen_artifacts regen; SDK contacts.py/calendar.py/media.py/location.py/camera.py/microphone.py/sensors.py/sms.py/telephony.py/sockets.py + stubs + exports; sim 11 handlers + helpers (add_contacts/add_events/add_media/set_location/add_sensors/add_sms/set_call_state/add_socket_grant); new Python `test_p10_part2.py` (23) + 4 sample tests; new JVM tests written (owner-run): DataClusterTest 22, SocketProvidersTest 12.
- **Fixes along the way** — three self-shadowing SDK bugs caught by the tests before landing (location.current, sensors list builtin, sockets scope-object handling), the sim page-id/media-id allocator collisions, and two manifest schema misses (`schedule.default` null) — all fixed with the suite green.
- **Docs** — PROGRESS (P10-06..08 + rider evidence, Gate-10 sim-proof pointers, focus/updated lines, dashboard regen), HANDOFF (session-17 snapshot extended, 3 new status rows, spec counts 47/27 modules, §4 → Phase 11, §5 +379/data/net gradle lines, part-2 first-build notes), DECISIONS (ADR-073..075), REPO_LAYOUT (data module + app/data + net riders), GATES (G10 sim-proof pointers), CONVENTIONS (4 checklist rows), ANDROID_MANIFEST (P10 status note), README (379 + Phase 10 complete).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 29 OK (+5 gen_artifacts); SDK suite 379 OK (352 + 23 + 4); gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3. Kotlin/Gradle remains owner-verified.
- Packaged: download/autohost-phase10-part2.zip.

**Phase 11 next:** QuickJS runtime, plugin-APK SDK + example, task repository import, app packs, Task Studio, PC web inspector, backup/restore, release signing, upgrade/migration tests, user guide, OEM matrix (plan §18 P11); Gate 11 = final acceptance.

---

## Session 18 — Phase 11 part 1 (P11-01, P11-03 pure half, P11-10: the JS runtime, repo index, migration tests) — complete as written

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P11-01 the JavaScript (QuickJS) runtime** — ADR-076; new pure-JVM `:runtime:js` (settings.gradle include; build.gradle mirrors :runtime:flow): `JsRuntimeProvider.kt` (in-process like flows — one QuickJS instance per run on a dedicated daemon thread (script eval blocks; a pool thread must never be pinned), one heartbeat coroutine, the same `finished`-event contract through the same binder, engine-internal crash = worker death via the death listener; `classify()` = the JS mirror of `execute_with_runtime`: ok/cancelled/needs_user(+resume_hint)/autohost(reason||code)/entry(ENTRY_NOT_FOUND)/exception(stack as traceback)), `JsBridge.kt` (the ONE op table — call/emit/sleep/ctx/state_save/secret/clock/result/next_run/capabilities — served synchronously on the engine thread; envelope unwrap; emit redacted; pause parks, cancel raises typed CANCELLED; `JsBrokerCaller` seam; `JsRunControls` with the injectable monotonic; `IsoFormat` seconds-precision), `JsEngine.kt` (the pure seam + `JsEngineFactory`; outcome contract {status:ok,value}|{status:error,kind:entry|autohost|needs_user|cancelled|exception}; engines expose the entry namespace as `__autohost_exports`), `JsSources.kt` (entry resolution (explicit must exist; blank → main.js, FlowSources rule) + the `./lib/*.js` module loader (relative-only, canonical containment under the task dir + lib/ requirement, 512 KiB/file, 32-file cap)), `JsRunPolicy.kt` (32/16 MB heap by RAM class, 1 MB stack, deadline = manifest timeout_minutes; shouldInterrupt = cancel-then-deadline), `JsPrelude.kt` (resource loader).
- **prelude.js** — `runtime/js/src/main/resources/autohost/js/prelude.js`: the JS v1 surface (ADR-076): the single-arg `__autohost_bridge([op, payloadJson])` frame, `autohost.call` (the universal spec-locked verb), emit/log/ctx (config/profile/run/trigger/state()/save/secret/result/progress/now/minutesSince/sleep/nextRun/profileStore/taskStore/evidence), a full `sel` port of the Python SDK sel.py (parity-tested, 18-builder matrix), `Result`, error classes (AutoHostError/NeedsUser/Cancelled/Retryable), `capabilities()`; `__autohost_run_main()` resolves main (`__autohost_exports.main` → global `main`), marks ctx.results, serializes outcomes with kind + stack.
- **P11-02 engine touchpoint** — `app/runtimejs/QuickJsEngineFactory.kt` (the ONLY file importing `com.dokar.quickjs.*`; owner-verified first build): single-arg bridge function, limits best-effort with an honest warning, 200 ms interrupt watcher driving `interruptEvaluation()` (no poll-handler in the binding), module loader serving the entry as `__autohost_entry__` + delegating `./lib/*` to the JsSources loader, prelude script → entry module re-export → `JSON.stringify(__autohost_run_main())` returned verbatim; runBlocking on the dedicated engine thread is the documented blocking boundary. AppGraph: `jsRuntime` (JsBrokerCaller adapter over the binder, fail-closed secrets pending the SecretsProvider — same as flows/Python, shared Redactor, low-RAM input from ActivityManager) + registration after flowRuntime + `setDeathListener { orchestrator.onWorkerDeath }`. libs.versions.toml quickjs = 1.0.3; app deps `:runtime:js` + quickjs-kt-android.
- **P11-03 pure half — RepoIndex** — ADR-078; `core/engine/tasks/RepoIndex.kt` (pure): index.json v1 parse (schema gate; per-entry reverse-DNS id, strict semver, required source, optional title/runtime/sha256(64-hex)/min_host_api/notes; unknown keys typed; duplicate ids keep the first occurrence; malformed root → index null + issues), `select()` verdicts (Install / Upgrade(from,to) / UpToDate / Reject(HOST_TOO_OLD|REPO_OLDER) — downgrades need an explicit uninstall), `verifySha` (case-insensitive exact hex). The fetch/CLI/UI consumer is part 2 (Task Studio).
- **P11-10 upgrade/migration tests** — `core/engine/tasks/UpgradeMigrationTest.kt` (7): the migration contract pinned (current-schema pass-through, HOST_TOO_OLD for newer manifests, malformed → IllegalStateException, chain purity) + the SemVer §11 matrix (patch/minor/major, pre-release before release, numeric < alphanumeric pre-ids, build metadata ignored); joins SchemaMigrationTest (Room) + ManifestMigrations (P2-05).
- **Sim JS host** — ADR-077; `sdk-python/autohost_sim/js_host.py` (`run_js_task`: spawns `node js/node_shim.cjs <prelude> <entry>`; serves the SAME op table over a real `Runtime`+SimTransport — config/secrets/redaction/cancellation/capability dispatch identical to Python task runs; classification mirrors `execute_with_runtime` branch-for-branch; select-loop with a 120 s host deadline; stderr captured for shim crashes) + `js/node_shim.cjs` (dumb stdio transport; ESM via node-24 require-of-ESM + syntax detection, plain scripts via vm.runInThisContext; `__autohost_exports` for ESM mains; done outcome line) + `js/prelude.js` (byte-identical copy of the runtime resource — a drift test pins them).
- **Sample** — `samples/js-press-button` (task.json runtime javascript + main.js + README): the archetype-A task rewritten on the JS surface (KV cooldown, launch → wait_foreground → wait_for → click, honest failure path with evidence + nextRun + rethrow, NODE_ACTION verified in sim).
- **Docs** — new `docs/SDK_JS.md` (the one-table contract: task shape, surface, ops, engine contract); PROGRESS (P11-01/03/10 (WRITTEN) + evidence, G11-a/b/c/e written-side pointers, focus/updated lines, dashboard regen — Phase 11 written 20%, fixed a line-29 mangling that had swallowed rule 1); HANDOFF (session-18 snapshot, 4 new status rows, next-steps → part 2, checklist +:runtime:js/:core:engine gradle lines + 392 counts, §6 quickjs-kt first-build note); DECISIONS (ADR-076..078 full); REPO_LAYOUT (:runtime:js section, app P11 additions, engine RepoIndex, sim js files, samples list, module count 28); GATES (G11 written-side pointers); CONVENTIONS (JavaScript tasks section); VERSIONS (quickjs-kt-android 1.0.3 row); THIRD_PARTY_NOTICES (quickjs-kt Apache-2.0 + bundled QuickJS MIT); samples/js-press-button/README; README (392 tests + Phase 11 part 1 status).
- **Tests** — new JVM tests written (owner-run): JsSourcesTest 6, JsRunPolicyTest 5, JsBridgeTest 15 (ops/envelopes/redaction/cancel-during-sleep via the virtual monotonic/ctx shapes/state round-trip/next_run validation), JsOutcomeTest 8 (branch-for-branch classification), JsRuntimeProviderTest 6 (scripted fake engine: finished/death/cancel-interrupt/bridge reachability/caps); new Python `test_p11_part1.py` 13 (RUN here: sample press + cooldown + missing-button evidence/next_run, exception/NeedsUser/entry/NOT_FOUND classification, cancel via sleep, state round-trip, capabilities, F2 three-runtimes coexistence, prelude byte-identity drift, 18-builder sel parity vs autohost.sel).
- **Honest limitations recorded (ADR-076):** an in-process runtime cannot be force-killed like a worker process — kill() drives the interrupt handler; a run wedged inside one broker call unwinds when the call returns; caps bound the blast radius. JS v1 surface deliberately small (`autohost.call` + helpers; per-module ergonomics later without runtime changes). Secrets fail closed for all three runtimes until the SecretsProvider ships.

**Verification (sandbox-applicable):** progress.py --check ok; schema suite 29 OK; SDK suite **392 OK** (379 + 13); gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3; node smoke of the full JS sim path (sample → SUCCEEDED/pressed). Kotlin/Gradle remains owner-verified (quickjs-kt touchpoint flagged for first-build API verification).

**Part 2 next (P11-02..09, 11, 12):** plugin-APK SDK + example plugin (F4), app packs (.aapack §7.10), Task Studio, `autohost ui` web inspector, reverse-WS Dev API transport (rides ADR-075), backup/restore (minus secrets), release signing + registration, user guide + OEM matrix, optional port exercise; then the Gate-11 acceptance pack (F1–F5).

## Session 19 — Phase 11 part 2 (P11-02/03/04/05/06/07/08/09/11/12: plugins, packs, studio, inspector, reverse transport, backup, release, guides, port) — complete as written; **ALL ELEVEN PHASES written**

**Scope delivered (all (WRITTEN), per the owner's standing verify-at-the-end choice):**
- **P11-02 plugin host** — ADR-079; pure `core/engine/plugins/PluginHost.kt` (PluginBinder seam over the `ICapabilityProvider` AIDL; api band 1..1; fail-closed describe() — `plugin.*` ids, snake_case methods, permission per method, one malformed entry rejects the WHOLE plugin; host-collision refusal; `PluginProvider` with typed envelope handling); `PermissionIndex.mergedWith` (host wins, fragments never shadow) + AppGraph `attachPluginPermissions` (live index + grant rebuild); app `plugins/PluginController.kt` (query/bind/reconcile via the CAPABILITY_PROVIDER intent + manifest `<queries>`); new module `:plugin-sdk` (build + `PluginCapabilityService.kt` dependency-free base); standalone `samples/plugin-example` (own settings/catalog, exported service, README F4 recipe). JVM PluginHostTest 8 written.
- **P11-03 consumer** — Studio repo tab (`StudioController.loadRepo/installFromRepo`: https/file only, RepoIndex verdicts, sha-verified install, source "repo") + CLI `autohost repo` (`autohost_cli/repo.py` mirrors parse/select/SemVer-§11; `tasks.list` feeds real installed versions; RepoParseTest 6).
- **P11-04 app packs** — ADR-080; pure `core/engine/tasks/AppPacks.kt` (pack.json v1 fail-closed; resolve in task order; configPayload; label fallback); **mechanism = run-config injection** (`config.packs`, reserved, host wins with warning) wired in AppGraph.startRun; `TaskManifest.usesPacks` + ImportPipeline PACK_MISSING warning rows; app `packs/PackController.kt` (.aapack install with zip-slip rejection, version store, injectPacks); SDK `autohost/packs.py` + stub + export + public `sel.from_json` (selector DOCUMENTS are the pack selector language); sim `installed_packs` + `resolve_packs` in runner/flows/js_host; sample `samples/pack-presser`. JVM AppPacksTest 5 written; Python PackSampleTest 4 + PackSdkTest 4.
- **P11-05 Task Studio** — ADR-083; pure `StudioDraft.kt` (overlay edits, validate via the importer's ManifestValidator, per-runtime templates) + StudioDraftTest 5; app `ui/studio/{TaskStudioScreen,StudioController}.kt` (dev-folder create/open/save, entry-text editor, Validate/Save, repo tab; Library → Studio route).
- **P11-06 web inspector** — `autohost_cli/webui.py` + `ui-web` (localhost page: live tree, selector try-box, screenshot; `/api/<method>` proxy injects Authorization — the token never reaches the browser; 404/502 honest envelopes); WebUiTest 4.
- **P11-07 reverse transport** — ADR-081; pure `core/devapi/WsFrames.kt` (RFC 6455 both roles, 7/16/64-bit lengths, mask rules, RFC accept vector) + `ReverseDevApiLink.kt` (client handshake, first-frame token, same dispatch; WsFramesTest 6 + ReverseDevApiLinkTest 3 over loopback); app `devapi/ReverseLinkController.kt` (bounded backoff, dev-api gate, honest state) + Settings S26a; PC `autohost_cli/bridge.py` + `bridge` (WS server + first-frame token + localhost HTTP proxy; single-reader relay after fixing the reader-loop race; BridgeTest 3).
- **P11-08 backup/restore** — ADR-082; pure `core/engine/backup/BackupCodec.kt` (v1 doc; secrets as NAMES only; encode refuses poisoned values, decode refuses `secrets`-class keys; restore plan with the honest dev-folder skip) + BackupCodecTest 5; app `backup/BackupController.kt` (SAF export with on-the-fly bundle zip; restore through the standard import path, source "backup"; empty secret stores + deleteForProfile) + Settings S26.
- **P11-09 release** — `app/build.gradle.kts` env-based signing (keystore outside the repo; unsigned artifact when absent); `tools/licence_check.py` + `tests/test_licence_check.py` 6 (green at 25 deps; AndroidX family allowlisted as Apache-2.0 with the licensee upgrade path recorded); `.github/workflows/ci.yml`; `docs/RELEASE.md` (signing, registration, hardening checklist, upgrade gates, release order).
- **P11-11 guides** — `docs/USER_GUIDE.md` (install → grants → first task → schedules/triggers → surfaces → PC tooling incl. bridge → resilience/backup → shell tier → plugins/packs → troubleshooting) + `docs/OEM_MATRIX.md` (per-vendor kills/steps, PENDING-ON-HARDWARE cells, always-on recipe).
- **P11-12 port exercise** — `samples/photo-organizer` (media.list/files.shared/media.delete; copy-by-default) + `docs/PORT_EXERCISE.md` (decision table + gap log: 4 issues→proposals, 1 documented limit, 1 timezone decision — zero hacks); PhotoOrganizerSample 2.
- **Docs** — PROGRESS (focus/updated lines, P11-02..12 (WRITTEN) + evidence, G11-d/e, dashboard regen 76%), HANDOFF (session-19 snapshot, next = build epoch, §2 ADR-079..083, §4, §5 counts 415/41 + licence check, §6 first-build notes), DECISIONS (ADR-079..083 full), REPO_LAYOUT (plugin-sdk + engine/devapi P11 additions + app plugins/packs/backup/studio/reverse + CLI modules + samples + 29 modules), GATES (G11-d/e written sides), UI_SPEC (S26/S26a/S27), DEVAPI (reverse transport row + §6 PC tools), CONVENTIONS (packs/plugins conventions), ANDROID_MANIFEST (queries), AGENTS (doc map: RELEASE/USER_GUIDE/OEM_MATRIX/PORT_EXERCISE), README (415 + Phase 11 complete + user-docs row).

**Verification (sandbox-applicable):** progress.py --check ok; schema suite **35 OK** (29 + 6 licence-check); SDK suite **415 OK** (392 + 23); gen_artifacts --check ok; sync_sdk --check ok; mcp_eval PASS×3; licence_check ok. Kotlin/Gradle remains owner-verified.
- Packaged: download/autohost-phase11-part2.zip

**Next (no more code phases):** the owner's Gradle first build (HANDOFF §5 order, §6 failure map), then the device gates 6–11 (docs/GATES.md), then release (docs/RELEASE.md).

## Session 20 — crash-fix pass (CRASH_FIX_PLAN.md, batches 1 to 4)

**Scope delivered:** CrashLog + in-app crash report (Copy/Share); `AutoHostApp` builds the graph only in the main process and records a failed build; `AppGraph` publishes itself first in `init`, `appScope` has a `CoroutineExceptionHandler`, delayed startup steps are isolated; `CapabilityCatalog` named error; ADB key regenerates when corrupt (+test); `FakeProvider.providerId`; `CapabilityRegistry` duplicate guard (+test); Library Run folder and list key; overlay work on the main thread (bubble, task overlays, inspector); `FOREGROUND_SERVICE_MEDIA_PROJECTION`.

**Verification:** Checkpoint A: NOT RUN — owner directed the Gradle run be the plan's very last step on explicit approval (this 10-year-old Windows machine, 3.9 GB RAM; toolchain prepared but idle: local SDK gained `platforms;android-36` + `build-tools;36.0.0` via its own sdkmanager, Gradle 8.13 sha256-verified at `C:\Users\DELL\.cache\autohost\gradle-8.13`). Checkpoint B: pending that build. All batch VERIFY checks (exact text counts) matched and are recorded in CRASH_FIX_PLAN.md §1.3–1.4; `tools/progress.py --check` ok at baseline (2026-10-30). Commits: none — the folder is not a git repository (plan rule 1.1b #5). PRE-EXISTING failures found: none (nothing compiled yet).

**Open:** owner-approved final Gradle build+tests (then update this entry with results and the APK sha256); owner phone test (CRASH_FIX_PLAN.md section 8); owner decisions in its Appendix A.
