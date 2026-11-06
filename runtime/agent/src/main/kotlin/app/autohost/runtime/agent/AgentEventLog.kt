package app.autohost.runtime.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P13-15 (AGENT_RUNTIME §11/§13): the agent's event log — the §11 table as code. Implements
 * [AgentLoop.Sink]; the provider wires `emit` to the broker's event stream:
 *
 * | Loop sink event       | §11 event                                   |
 * |-----------------------|---------------------------------------------|
 * | stage_begin/stage_end | `stage_begin`/`stage_end` (observe/think/act — RunRecord.stage) |
 * | progress (turn)       | `progress` with `data.progress = turn/max`  |
 * | agent.thought         | `log` kind=agent.thought (redacted; the capsule line) |
 * | agent.action          | `log` kind=agent.action (tool + redacted args summary) |
 * | agent.result          | `log` kind=agent.result (tool, ok)          |
 * | agent.usage           | `log` kind=agent.usage (+ 80 %-of-budget crossings) |
 * | agent.budget          | `log` kind=agent.budget (at a stop; the crossings here) |
 *
 * Trace bounds (ADR-091 (13)): the in-memory trace keeps the last [traceCap] events; every
 * evicted entry bumps a CUMULATIVE, VISIBLE counter emitted as
 * `log level=warn kind=agent.dropped data.dropped=N` — a reader can always see that entries
 * were dropped and how many.
 *
 * Redaction (§13): `redact` is a seam (:app wires the shared Redactor); it applies to every
 * free-text field (thoughts, args summaries) before the event leaves the runtime. The default
 * is documented identity — the production factory must wire the real redactor.
 */
class AgentEventLog(
    private val goal: String,
    private val packages: List<String>,
    private val providerProfileId: String?,
    private val budgets: RunBudget.Limits,
    private val emit: (event: JsonObject) -> Unit,
    private val redact: (String) -> String = { it },
    private val keepTranscript: Boolean = false,
    private val traceCap: Int = 500,
) : AgentLoop.Sink {

    private val trace = ArrayDeque<JsonObject>()
    private var droppedEvents = 0

    // 80 %-crossing latches (§11: agent.budget at 80% of any budget and at stop) — emitted ONCE
    private val warned80 = mutableSetOf<String>()
    private var tokensSeen = 0L
    private var modelCallsSeen = 0
    private var turnsSeen = 0
    private var actionsSeen = 0

    /** The finished/stop outcome, recorded by the provider for [agentSection]. */
    @Volatile
    var stop: StopInfo? = null

    data class StopInfo(val status: String, val reason: String?, val message: String?)

    override fun on(kind: String, data: JsonObject) {
        when (kind) {
            "stage_begin", "stage_end" -> emit(
                buildJsonObject {
                    put("type", kind)
                    put("stage", data.str("stage") ?: "")
                },
            )
            "progress" -> {
                turnsSeen = data.str("turn")?.toIntOrNull() ?: turnsSeen
                val max = budgets.maxTurns.coerceAtLeast(1)
                emit(
                    buildJsonObject {
                        put("type", "progress")
                        put("data", buildJsonObject {
                            put("progress", (turnsSeen.toFloat() / max))
                            put("turn", turnsSeen)
                            put("max", max)
                        })
                    },
                )
                crossing("turns", turnsSeen.toLong(), budgets.maxTurns.toLong())
            }
            "agent.thought" -> emitLog(
                "agent.thought",
                buildJsonObject { put("text", redact(data.str("text").orEmpty().take(2_000))) },
            )
            "agent.action" -> emitLog(
                "agent.action",
                buildJsonObject {
                    put("tool", data.str("tool") ?: "")
                    put("args", redact(data.str("args").orEmpty().take(200)))
                    data.str("generation")?.let { put("generation", it) }
                },
            )
            "agent.result" -> {
                actionsSeen++
                emitLog(
                    "agent.result",
                    buildJsonObject {
                        put("tool", data.str("tool") ?: "")
                        put("ok", data.str("ok")?.toBooleanStrictOrNull() ?: false)
                    },
                )
                crossing("actions", actionsSeen.toLong(), budgets.maxActions.toLong())
            }
            "agent.usage" -> {
                modelCallsSeen++
                val prompt = data.str("prompt_tokens")?.toLongOrNull() ?: 0L
                val completion = data.str("completion_tokens")?.toLongOrNull() ?: 0L
                tokensSeen += prompt + completion
                emitLog(
                    "agent.usage",
                    buildJsonObject {
                        put("prompt_tokens", prompt)
                        put("completion_tokens", completion)
                        put("estimated", data.str("estimated")?.toBooleanStrictOrNull() ?: true)
                        put("latency_ms", data.str("latency_ms")?.toLongOrNull() ?: 0L)
                    },
                )
                crossing("tokens", tokensSeen, budgets.tokenBudget)
                crossing("model_calls", modelCallsSeen.toLong(), budgets.maxModelCalls.toLong())
            }
            "agent.budget" -> emitLog(
                "agent.budget",
                buildJsonObject { put("budget", data.str("budget") ?: "") },
                level = "warn",
            )
            // P14-04/R4: the completion verifier's emit delivers an ALREADY-SHAPED §11 event
            // ({"type":"evidence","data":{path,kind}}) — pass it through AS an evidence event,
            // never wrapped into a log line (RunPersistenceBridge persists on type=="evidence")
            "evidence_event" -> emit(data)
            else -> emitLog(kind, data) // unknown kinds pass through honestly
        }
    }

    private fun crossing(name: String, used: Long, limit: Long) {
        if (limit <= 0) return
        if (used * 10 >= limit * 8 && warned80.add(name)) {
            emitLog(
                "agent.budget",
                buildJsonObject {
                    put("budget", name)
                    put("fraction", 0.8)
                    put("used", used)
                    put("limit", limit)
                },
            )
        }
    }

    private fun emitLog(kind: String, fields: JsonObject, level: String = "info") {
        val event = buildJsonObject {
            put("type", "log")
            if (level != "info") put("level", level)
            put("data", buildJsonObject {
                put("kind", kind)
                fields.forEach { (key, value) -> put(key, value) }
            })
        }
        emit(event)
        // the trace bound: the newest entries survive; evictions are counted VISIBLY (ADR-091 (13))
        trace.addLast(event)
        if (trace.size > traceCap) {
            trace.removeFirst()
            droppedEvents++
            emit(
                buildJsonObject {
                    put("type", "log")
                    put("level", "warn")
                    put("data", buildJsonObject {
                        put("kind", "agent.dropped")
                        put("dropped", droppedEvents)
                    })
                },
            )
        }
    }

    /**
     * The `.aarun` agent section (RunExporter's `agent.json`): the summary + counters always;
     * the transcript only when `keep_transcript` (§13 — an explicit user choice), and then
     * bounded and redacted. Never a key, token or secret.
     */
    fun agentSection(): JsonObject = buildJsonObject {
        put("goal", goal)
        put("packages", kotlinx.serialization.json.JsonArray(packages.map { JsonPrimitive(it) }))
        put("provider_profile", providerProfileId ?: "") // the id only — never a key
        put("budgets", buildJsonObject {
            put("max_turns", budgets.maxTurns)
            put("wall_clock_ms", budgets.wallClockMs)
            put("max_model_calls", budgets.maxModelCalls)
            put("token_budget", budgets.tokenBudget)
            put("max_actions", budgets.maxActions)
            put("max_output_tokens", budgets.maxOutputTokens)
        })
        put("usage", buildJsonObject {
            put("turns", turnsSeen)
            put("model_calls", modelCallsSeen)
            put("actions", actionsSeen)
            put("tokens", tokensSeen)
        })
        put("trace", buildJsonObject {
            put("kept", trace.size)
            put("dropped", droppedEvents) // the VISIBLE drop counter
            put("keep_transcript", keepTranscript)
        })
        stop?.let { s ->
            put("stop", buildJsonObject {
                put("status", s.status)
                s.reason?.let { put("reason", it) }
                s.message?.let { put("message", it) }
            })
        }
        if (keepTranscript) {
            put("transcript", kotlinx.serialization.json.JsonArray(trace.toList()))
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
