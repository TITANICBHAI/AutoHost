# ClosePaw Visual Debugging Guide

> **Prerequisites:** Understand the agent architecture in [doc/main/README.md](../main/README.md) (start with `agent/loop.md` and `agent/overview.md`).

Visual debugging approach for ClosePaw's ReAct loop using screenshots + logs.

## Issue Categories

| Category | Symptoms |
|----------|----------|
| **Perception** | Agent doesn't see visible elements, wrong element indices |
| **Reasoning** | LLM chooses wrong action despite correct perception |
| **Execution** | Action fails or targets wrong element |
| **Observation** | Post-action state not captured correctly |

## Debugging Workflow

### Step 1: Run with Debug Script

```bash
./scripts/debug-run.sh "Open Chrome"
```

Output in `debug-output/run_<timestamp>/`:
```
debug-output/run_<timestamp>/
├── turn_NNN_n<turn>.png     # Screenshot at each captured turn-start
├── turn_NNN_n<turn>_log.txt # Log excerpt around that turn
├── logcat_full.log          # Raw logcat stream
├── agent.log                # Filtered: Agent|Turn|LLMClient|ToolRouter|SessionServices
├── system.log               # Filtered: AgentService|AccessibilityPlatform|AgentSession
└── trace/                   # JSONL trace + replay artifacts (compiled by replay_compiler.py)
```

### Step 2: Turn-by-Turn Analysis

For each turn, compare:

| Check | Source | Look For |
|-------|--------|----------|
| Actual screen | `turn_NNN_n<turn>.png` | What's visible, is target there? |
| Perceived elements | `turn_NNN_n<turn>_log.txt` | Element indices, missing elements |
| Action chosen | Log: `ACTION` | Does action match goal? |
| Result | Log: `ActionResult` | Success/failure, observation |

### Step 3: Common Issues

#### A. Agent Stuck in Loop

**Symptoms:** Same action repeating, no progress

**Debug:**
1. Compare consecutive screenshots - is screen actually changing?
2. Check observation after action - did agent see the change?
3. Check LLM reasoning - is history providing correct context?

#### B. Wrong Action Chosen

**Symptoms:** Target visible but agent does something else

**Debug:**
1. Check `Perceptor` output - is target element in the list?
2. Check element index - does it match what agent referenced?
3. Check LLM prompt - does system prompt have enough guidance?

**Example:**
```
Screen shows Chrome at element_index=10
Agent action: {"tool": "back"}  ← WRONG
Should be: {"tool": "click", "element_index": 10}
```

#### C. Action Fails

**Symptoms:** Tool returns error or no effect

**Debug:**
1. Check element bounds - is target actually clickable?
2. Check timing - did UI change during action?
3. Check `ActionResult` - what error was returned?

### Step 4: Add Targeted Logging

In `Agent.kt` or `Turn.kt`:

```kotlin
// Log perception before LLM call
private fun logPerception(snapshot: ScreenSnapshot) {
    Log.d(TAG, "=== PERCEPTION ===")
    Log.d(TAG, Perceptor.toPromptJson(snapshot))
}

// Log LLM input/output
private fun logTurn(input: String, result: TurnResult) {
    Log.d(TAG, "=== LLM INPUT ===\n$input")
    Log.d(TAG, "=== LLM OUTPUT ===\n${result.content}")
    Log.d(TAG, "=== TOOL CALLS ===\n${result.toolCalls}")
}
```

### Step 5: Manual Verification

```bash
# Capture current screen
adb exec-out screencap -p > /tmp/check.png
open /tmp/check.png

# Compare with what Perceptor saw
```

## Quick Diagnostics

```bash
# Pick the latest run dir
RUN=$(ls -1dt debug-output/run_* | head -n 1)

# Actions taken
grep -E "click|type|scroll|swipe|back|home" "$RUN/agent.log"

# Tool results
grep "ActionResult\|ToolCallResult" "$RUN/agent.log"

# Errors
grep "ERROR\|Exception" "$RUN/agent.log" "$RUN/system.log"

# Turn markers
grep "=== TURN" "$RUN/agent.log"
```

## Example Session

**Problem:** Agent keeps pressing back instead of clicking Chrome

```bash
# 1. Run debug
./scripts/debug-run.sh "Open Chrome"

# 2. Check turn_002_n2.png - Chrome icon visible

# 3. Check turn_002_n2_log.txt
# PERCEPTION: element_index=10, text="Chrome", clickable=true
# ACTION: {"tool": "back"}  ← BUG

# 4. Issue: LLM reasoning wrong despite correct perception
# Fix: Update system prompt in agent/definition/DefaultAgentDef.kt

# 5. Verify
./scripts/debug-run.sh "Open Chrome"
```

## Key Files

> See [doc/main/README.md](../main/README.md) for full architecture (entry to `agent/`, `infra/`, `protocol/`, `ui/` docs).

| File | Purpose |
|------|---------|
| `app/src/main/kotlin/ai/closepaw/agent/Agent.kt` | ReAct loop (Perceive → Think → Act → Observe) |
| `app/src/main/kotlin/ai/closepaw/agent/Turn.kt` | Single LLM call with streaming |
| `app/src/main/kotlin/ai/closepaw/perception/Perceptor.kt` | Accessibility tree → ScreenSnapshot |
| `app/src/main/kotlin/ai/closepaw/tool/ToolRouter.kt` | Tool execution state machine |
| `app/src/main/kotlin/ai/closepaw/platform/AccessibilityPlatform.kt` | Screen capture and actions |
