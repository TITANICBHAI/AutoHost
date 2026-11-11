package app.autohost.runtime.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P13-14 (AGENT_RUNTIME §9): the §9 completion protocol — Stage A (supervised). On `finish`:
 *
 *  1. a FINAL observation (the loop's own observer seam: settle + snapshot, floor-filtered) is
 *     written to the evidence dir and announced with an `evidence` event (`data.path`, `data.kind`
 *     — the contract RunPersistenceBridge persists);
 *  2. `ui.prompt.ask` `kind:"confirm"` with `timeout_ms = confirm_timeout_s · 1000` and the
 *     message template "The assistant says it is done: <summary ≤ 120 chars>. Is it?";
 *  3. while waiting, a `log` event every 15 s keeps the idle watchdog fed (the confirm window is
 *     120 s; the default idle budget is 180 s; heartbeats are NOT activity);
 *  4. `confirmed:true` → [AgentLoop.FinishResult.Confirmed] (→ SUCCEEDED);
 *     `confirmed:false` → Rejected (→ COMPLETION_REJECTED);
 *     timeout / dismissed / kill-switch → Unverified (→ COMPLETION_UNVERIFIED).
 *
 * The model's claimed status is stored as EVIDENCE (the summary rides the finished event's
 * message) and is NEVER the verdict — only the user's answer is.
 */
class CompletionVerifier(
    private val observer: AgentLoop.Observer,
    private val evidence: EvidenceWriter,
    private val emit: (event: JsonObject) -> Unit,
    private val transport: BrokerTransport,
    private val config: Config = Config(),
    private val clockMs: () -> Long,
) : AgentLoop.FinishVerifier {

    data class Config(
        val confirmTimeoutS: Int = 120,
        val keepAliveIntervalMs: Long = 15_000,
        val title: String = "AutoHost",
    )

    /** Writes the final observation into the run's evidence dir; returns the path. */
    fun interface EvidenceWriter {
        suspend fun write(snapshot: JsonObject): String
    }

    override suspend fun verify(summary: String?): AgentLoop.FinishResult {
        // 1: the final observation — host-initiated, floor-filtered
        val snapshot = try {
            observer.observe()
        } catch (e: CancellationException) {
            throw e
        }
        val path = try {
            evidence.write(snapshot)
        } catch (e: CancellationException) {
            throw e
        }
        emit(
            buildJsonObject {
                put("type", "evidence")
                put("data", buildJsonObject {
                    put("path", path)
                    put("kind", "ui_snapshot") // the RunPersistenceBridge evidence type
                })
            },
        )
        // 2: the supervised confirm (the ask honours its timeout_ms — unlike `dialog`)
        val message = "The assistant says it is done: ${clip(summary ?: "")}. Is it?"
        val startedMs = clockMs()
        val answer = coroutineScope {
            // 3: keep-alive while the ask is in flight (every non-heartbeat event IS idle activity)
            val keepAlive = launch {
                while (true) {
                    delay(config.keepAliveIntervalMs)
                    emit(
                        buildJsonObject {
                            put("type", "log")
                            put("data", buildJsonObject {
                                put("kind", "agent.waiting_confirm")
                                put("waited_ms", clockMs() - startedMs)
                            })
                        },
                    )
                }
            }
            val result = try {
                transport.call(
                    "ui.prompt", "ask",
                    buildJsonObject {
                        put("kind", "confirm")
                        put("title", config.title)
                        put("message", message)
                        put("timeout_ms", config.confirmTimeoutS * 1_000L)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                keepAlive.cancel()
            }
            result
        }
        // 4: the verdict is the USER's answer — the claim is only evidence
        val answered = answer.str("answered")?.toBooleanStrictOrNull() == true
        val confirmed = answer.str("confirmed")?.toBooleanStrictOrNull()
        return when {
            answered && confirmed == true -> AgentLoop.FinishResult.Confirmed
            answered && confirmed == false -> AgentLoop.FinishResult.Rejected // an explicit "no" from the user
            // timeout / dismissed / kill-switch cancelled / an answer without a readable `confirmed` field:
            // never confirmed, and not claimed to be a rejection either (fail closed)
            else -> AgentLoop.FinishResult.Unverified
        }
    }

    companion object {
        /** §9: the summary is clipped to 120 chars in the confirm template. */
        fun clip(summary: String): String =
            if (summary.length <= 120) summary else summary.take(119) + "…"

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    }
}
