package app.autohost.ui.agent

import app.autohost.core.common.Clock
import app.autohost.core.model.BrokerEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/**
 * P14-04 (S30, UI_SPEC §10): the agent run-detail state — a pure, synchronous holder fed by the
 * run's [BrokerEvent] stream (the §11 event table P13-15 emits). JVM-testable like
 * [RunCapsuleState's holder][app.autohost.ui.capsule.RunCapsuleState]:
 *
 *  - the THOUGHT → ACTION timeline from `log data.kind = agent.thought / agent.action`, with the
 *    action's status icon (ok) paired from the following `agent.result`;
 *  - the BUDGETS/USAGE panel — turns (`progress data.turn`), model calls and tokens
 *    (`agent.usage`), device actions (`agent.result`), wall clock (the injected Clock);
 *  - the HOST VERDICT — confirmed / rejected / unverified, derived ONLY from the host's own
 *    terminal transition (SUCCEEDED, or FAILED with a COMPLETION_* reason) — the model's claimed
 *    status is evidence, never the verdict (AGENT_RUNTIME §9);
 *  - no secrets: the events are already redacted at the source (P13-15's redact seam); this
 *    holder never receives raw args beyond what the event log chose to carry.
 */
class AgentRunDetailState(private val clock: Clock) {

    sealed interface Entry {
        /** The model's reasoning line (redacted at the source). */
        data class Thought(val text: String) : Entry

        /**
         * One proposed action; [ok] is the paired `agent.result`'s verdict — null while the
         * result has not arrived (the icon is pending, never guessed).
         */
        data class Action(val tool: String, val argsSummary: String, val ok: Boolean?) : Entry
    }

    data class Usage(val turns: Int, val modelCalls: Int, val tokens: Long, val actions: Int)

    /** The host's verdict (S30) — never derived from anything the model said. */
    sealed interface Verdict {
        data object Confirmed : Verdict
        data object Rejected : Verdict
        data object Unverified : Verdict
    }

    data class Snapshot(
        val entries: List<Entry>,
        val usage: Usage,
        val budgetWarnings: List<String>,
        val verdict: Verdict?,
        val status: String?,
        val wallClockMs: Long,
    )

    private val timeline = mutableListOf<Entry>()
    private var turns = 0
    private var modelCalls = 0
    private var tokens = 0L
    private var actions = 0
    private val budgetWarnings = mutableListOf<String>()
    private var verdict: Verdict? = null
    private var status: String? = null
    private var startedAtMs: Long = -1

    /** Feed one event; the RunScreen event collector calls this for agent runs. */
    fun onEvent(event: BrokerEvent) {
        if (startedAtMs < 0) startedAtMs = event.ts
        when (event.type) {
            "progress" -> turns = event.data.int("turn") ?: turns
            "finished" -> onFinished(event)
            "log" -> onLog(event)
        }
    }

    private fun onLog(event: BrokerEvent) {
        val data = event.data ?: return
        when (data.str("kind")) {
            "agent.thought" -> timeline.add(Entry.Thought(data.str("text").orEmpty()))
            "agent.action" -> timeline.add(
                Entry.Action(tool = data.str("tool").orEmpty(), argsSummary = data.str("args").orEmpty(), ok = null),
            )
            "agent.result" -> {
                actions++
                val tool = data.str("tool").orEmpty()
                val ok = data.str("ok")?.toBooleanStrictOrNull()
                // one action per turn and results arrive in order — pair the FIRST pending
                // action for the same tool
                for (i in timeline.indices) {
                    val e = timeline[i]
                    if (e is Entry.Action && e.ok == null && e.tool == tool) {
                        timeline[i] = e.copy(ok = ok)
                        break
                    }
                }
            }
            "agent.usage" -> {
                modelCalls++
                tokens += (data.long("prompt_tokens") ?: 0L) + (data.long("completion_tokens") ?: 0L)
            }
            "agent.budget" -> data.str("budget")?.let { budget: String ->
                if (budget !in budgetWarnings) budgetWarnings.add(budget)
            }
        }
    }

    private fun onFinished(event: BrokerEvent) {
        val data = event.data ?: return
        val statusText = data.str("status") ?: "FAILED"
        val reason = data.str("reason")
        status = if (reason != null) "$statusText — $reason" else statusText
        verdict = when {
            statusText == "SUCCEEDED" -> Verdict.Confirmed
            reason == "COMPLETION_REJECTED" -> Verdict.Rejected
            reason == "COMPLETION_UNVERIFIED" -> Verdict.Unverified
            else -> null // a non-completion failure has no host verdict to show
        }
    }

    /** The immutable view the composable renders. */
    fun snapshot(): Snapshot = Snapshot(
        entries = timeline.toList(),
        usage = Usage(turns, modelCalls, tokens, actions),
        budgetWarnings = budgetWarnings.toList(),
        verdict = verdict,
        status = status,
        wallClockMs = if (startedAtMs >= 0) (clock.nowMs() - startedAtMs).coerceAtLeast(0) else 0,
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
}
