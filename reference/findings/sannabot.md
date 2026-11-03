# sannabotapp — study notes (v4 planning pass)
> **Status:** working notes from *static reading* of `7a4aeb67-sannabotapp-main.zip` by the planning agent (2026-10/11). Nothing was built or run. Line counts and paths refer to that archive. Statements tagged UNVERIFIED were not confirmed. **Licence:** README says "MIT", no LICENSE file; proprietary wake-word SDK — ideas only
> These notes are the evidence behind `docs/REUSE_MATRIX.md` rows; they are not instructions and contain no copied source.

# sannabotapp — findings (read for AutoHost planning)

Source: /home/claude/work/src/sannabot/sannabotapp-main/ (React Native 0.84 + Kotlin native modules).
Read in full: accessibility-sub-agent.ts (639), accessibility-headless-task.ts (461), accessibility-hint-store.ts (130),
tool-loop.ts (129), token-store.ts (225), context-manager.ts (182), SannaAccessibilityService.kt (524),
skill-loader.ts lines 1-150, README/FEATURES/DEVELOP head, AndroidManifest application block.
NOT read: conversation-pipeline, system-prompt, llm providers, scheduler/notification sub-agents, speech/wake-word modules.

## Size / stack (measured)
- 235 files; TS 14,726 lines, TSX 7,958, Kotlin 5,019 (29 files). No C/C++/Java.
- Tests: 1 file (`__tests__/App.test.tsx`). Effectively untested.
- Licence: README says "MIT"; there is NO LICENSE/COPYING file. => UNVERIFIED rights. Reference-only. No copying.
- Ships a Picovoice Porcupine wake-word model (.ppn) + params (.pv): proprietary SDK, AccessKey required. Not adoptable.
- Manifest: allowBackup="true" (+ backup_rules), a very wide permission set (SMS read/send, call log, contacts, CALL_PHONE, mic, location).

## What it does that matters for AutoHost
1. Accessibility sub-agent loop (accessibility-sub-agent.ts)
   - Rule: UI tree is STATE and goes in the first USER message, never the system prompt (a stale copy in the system prompt would
     conflict with later fetches). Good principle. AutoHost already treats the tree as data.
   - `finish_task(status, message)` is the only exit; loop hard-stops via shouldExit. maxIterations default 12. A "timeout" result when the
     model never calls finish_task. Model-claimed success is NOT verified by host state => AutoHost must verify completion itself.
   - Tool set: accessibility_action (click/long_click/type/clear/focus/scroll_forward/scroll_backward/home/back/recents/screenshot/
     clipboard_set/clipboard_get/paste/swipe/wait), get_accessibility_tree (800 ms settle sleep, "at most 3 times" only in the prompt text,
     NOT enforced in code), remember_task_info (≤200 chars, in-run scratchpad), finish_task.
   - Node ids "node_N" are ephemeral per capture; stale id => error string telling the model to re-fetch. Same idea as AutoHost NodeRegistry.
   - ActionHistoryTracker + AccessibilityTaskMemory injected into the system prompt (rebuilt only once, before the loop; the code comments
     admit the dynamic update does not happen).
   - The system prompt is built twice in the file (copy-paste duplicate; second overwrites the first).
2. "Learned hints" (accessibility-headless-task.ts + accessibility-hint-store.ts)
   - After each run (not on timeout) the FULL interaction history, including every accessibility tree, is sent to the LLM to condense
     into "3-4 paragraphs" of per-app hints; stored as plain text in AsyncStorage under accessibility_hint_<pkg>; injected into the
     SYSTEM prompt of every future run for that app. User-provided hints have "higher priority".
   - Risks (my analysis, not measured):
     a. Persistent prompt injection: any untrusted on-screen text (a chat message, a web page) in a tree can steer the condenser, and the
        output then sits in the highest-authority prompt slot for all later runs. No provenance, no review step, no expiry.
     b. Privacy: whole screens (names, messages) are sent to the model a second time purely for hint-writing, and the result is stored
        unencrypted (AsyncStorage) and included by allowBackup.
     c. Failed runs are also learned from ("❌ X did NOT work"), which can encode a transient failure as permanent.
     d. One free-text blob per app: unversioned, not diffable, cannot be validated.
   - Useful idea worth keeping in a SAFER form: per-app "playbooks". For AutoHost: store them as structured, reviewable, user-approved
     entries (selector + outcome + app version + timestamp + evidence run id), injected as DATA in the user turn with a "reference, not
     instructions" label, never auto-promoted into the system prompt. Optional, behind a flag; default OFF.
3. Accessibility service mechanics (SannaAccessibilityService.kt) — mostly what NOT to copy
   - Click is GESTURE-FIRST: computes node centre, then looks for a "better" clickable at those coordinates with a hard-coded
     "play"/"Play" heuristic (an app-specific hack in generic code), then dispatchGesture tap (100 ms). Never tries ACTION_CLICK for
     "click" (only long_click uses performAction first). ClosePaw's measured experiment says dispatchGesture "false success" 0/4 vs node
     click 8/8 on one surface; so node-click-first is the better default.
   - "completed" is detected with `result.contains("completed")` on a string — fragile string-typed result.
   - Gesture sync uses CountDownLatch with a 3 s timeout, awaited (good: ClosePaw/AutoHost-main fire-and-forget would benefit).
   - Tree capture: `Thread.sleep` retry loop (50..500 ms) on the calling thread; copies every node with `AccessibilityNodeInfo.obtain`
     (deprecated since API 33) into a ConcurrentHashMap; recycle() calls (no-ops on API 33+, still harmless).
   - serviceInfo: eventTypes = TYPES_ALL_MASK (costly; AutoHost should subscribe to the minimum), FLAG_REPORT_VIEW_IDS,
     FLAG_RETRIEVE_INTERACTIVE_WINDOWS. Tree is traversed from rootInActiveWindow only (no multi-window).
   - Filters its own overlay nodes by package == "com.sannabot" (good idea; AutoHost's RootResolver/PopupGuard already handle this).
   - clipboard_get returns clipboard text into the LLM context (privacy: AutoHost must gate clipboard reads behind a grant and redact).
   - `type` does a node ACTION_CLICK first and then ACTION_SET_TEXT with no verification.
4. Loop (tool-loop.ts)
   - Plain for-loop over maxIterations; no wall-clock budget, no token budget, no cancellation check, no per-tool timeout, no retry
     classification. Tool calls in a batch execute sequentially. A SILENT_REPLY_TOKEN in a tool result exits the loop silently.
   - Context manager (context-manager.ts): when history >= 20 messages, summarise all but the last 6 into one summary. The "assistant: I have
     noted the summary" filler message is a known token-wasting pattern. Summary failure falls back to the first 100 words.
   - => Compared with ClosePaw's compactor and android-agent's block-eliding Compactor, this is the weakest of the three.
5. Credentials (token-store.ts)
   - react-native-keychain (Android Keystore). A biometric "vault" entry gates an in-memory `unlocked` flag; the real secrets are stored
     WITHOUT biometric access control; `unlockForHeadless()` flips the flag with no prompt. So the biometric gate is a UI guard only
     (the file says so itself). Not a pattern to copy; AutoHost's SecretStore should rely on Keystore-wrapped keys + explicit grants.
6. Skills
   - SKILL.md frontmatter (name, description, category, test_prompt, android_package, exclusive_tool, permissions[], credentials[]).
     Hand-written line parser (not YAML-compliant). Skills can be uploaded by the user and are validated by skill-validator.ts (not read).
   - Idea of declaring required permissions/credentials in the skill header maps cleanly onto AutoHost's `.aatask` manifest — which already
     has `capabilities` + `config_schema` + `x-secret`. No port needed.
7. Scheduler/notification sub-agents (names only, not read): each scheduled task is an LLM sub-agent run via AlarmManager -> HeadlessJS.
   AutoHost already has a ScheduleEngine + TriggerEngine + RunOrchestrator; the lesson is only that LLM-driven schedules need hard budgets.

## Verdict for the plan
- Licence: UNVERIFIED (README-only MIT). Reference-only; clean-room. Nothing copied.
- Take (ideas only): tree-as-user-message; `finish_task` as an explicit terminal tool BUT host-verified; per-app playbooks in a
  structured, reviewed, default-off form; declared permission/credential requirements per skill (already in .aatask).
- Do NOT take: LLM-condensed free-text hints in the system prompt; gesture-first click; string-typed results; TYPES_ALL_MASK;
  biometric-as-flag credential gate; unbounded loops; allowBackup=true; heavy permission set.
- Relevance weight: LOW-MEDIUM. It confirms the shape of an agent loop but contributes fewer reusable mechanisms than ClosePaw,
  android-agent or opendroid, and its main distinctive feature (learned hints) is a security liability as built.
