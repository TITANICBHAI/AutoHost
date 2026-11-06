package app.autohost.agent

import app.autohost.core.common.AhJson
import app.autohost.core.common.AhResult
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.Clock
import app.autohost.core.llm.BaseUrlValidator
import app.autohost.core.llm.ChatRequest
import app.autohost.core.llm.ChatResponse
import app.autohost.core.llm.HttpUrlConnectionEngine
import app.autohost.core.llm.LlmGateway
import app.autohost.core.llm.LlmResult
import app.autohost.core.model.Envelope
import app.autohost.core.model.RunSpec
import app.autohost.runtime.agent.AgentConfig
import app.autohost.runtime.agent.AgentEventLog
import app.autohost.runtime.agent.AgentLoop
import app.autohost.runtime.agent.AgentRuntimeProvider
import app.autohost.runtime.agent.AgentView
import app.autohost.runtime.agent.AppSensitivity
import app.autohost.runtime.agent.BrokerTransport
import app.autohost.runtime.agent.CompletionVerifier
import app.autohost.runtime.agent.ObservationFilter
import app.autohost.runtime.agent.RunBudget
import app.autohost.runtime.agent.ToolCatalog
import app.autohost.runtime.agent.ToolPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * P14-07 (closing P13-12's placeholder): the PRODUCTION loop factory — builds the real chain
 * from a RunSpec:
 *
 *  - the token-bound [BinderAgentTransport] over the app's broker binder (capability.method);
 *  - the ModelClient adapter over the real [LlmGateway] (the wall-clock deadline rides the
 *    budget's remaining time);
 *  - the host-initiated observer: `device.ui.wait_for_settle` (≤ 3 s) then `device.ui.snapshot`,
 *    run through the §8 observation floor — a withheld observation reaches the loop as an
 *    honest empty screen with its reason, never as blocked content;
 *  - the [ToolPipeline] (validate → policy → approval → TOCTOU → budget reserve → broker → cap)
 *    with the live foreground source;
 *  - the [CompletionVerifier] with an evidence writer into the run's evidence dir;
 *  - the preflight seam is a pass-through: the §12 gate already ran at START
 *    ([AgentRunStarter]); the loop re-checks budgets every turn regardless.
 */
class AgentLoopFactory(
    private val binder: app.autohost.broker.ICapabilityBroker,
    private val profileStore: app.autohost.core.llm.ProfileStore,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val sensitivity: AppSensitivity,
    private val urlEnv: () -> BaseUrlValidator.UrlEnv,
    private val screenMetrics: () -> Pair<Int, Int>,
) : AgentRuntimeProvider.LoopFactory {

    override fun create(spec: RunSpec, sink: AgentLoop.Sink, pauseGate: AgentLoop.PauseGate): AgentLoop {
        val agentConfig = AgentConfig.from(spec.config)
        val transport = BinderAgentTransport(spec.runToken, binder, dispatchers.io)
        val budget = RunBudget(agentConfig.budgets.toLimits()) { clock.nowMs() }
        val (screenW, screenH) = screenMetrics()
        val view = AgentView(screenW, screenH)

        val gateway = LlmGateway(
            profiles = profileStore,
            engine = HttpUrlConnectionEngine(),
            audit = { }, // sizes-only audit; P13-15's event log carries the run-side usage events
            nowMs = { clock.nowMs() },
            urlEnv = urlEnv,
        )
        val modelClient = AgentLoop.ModelClient { request: ChatRequest ->
            gateway.chat(agentConfig.provider ?: "", request, budget.remainingWallClockMs())
        }

        val catalog = ToolCatalog.load()
        val pipeline = ToolPipeline(
            catalog = catalog,
            transport = transport,
            budget = budget,
            packages = agentConfig.packages.toSet(),
            sensitivity = sensitivity,
            foregroundSource = { foregroundOf(transport) },
            revalidator = { _, target ->
                // the TOCTOU legs (§10): the REAL foreground now; and for an id-resolved target,
                // whether the node id still resolves on a FRESH floor-filtered observation
                val fresh = floorFilteredSnapshot(transport, agentConfig)
                val stillResolvable = when {
                    target?.viaId != true -> true
                    else -> {
                        val index = view.render(fresh, generation = -1).index
                        val entry = target.node?.let { index[it] }
                        entry != null && (target.pkg == null || entry.pkg == target.pkg)
                    }
                }
                ToolPipeline.Revalidator.Revalidation(
                    foregroundPkg = foregroundOf(transport),
                    targetPkg = target?.pkg,
                    stillResolvable = stillResolvable,
                )
            },
        )

        val evidenceDir = File(File(spec.taskPath), "evidence").apply { mkdirs() }
        val verifier = CompletionVerifier(
            observer = { floorFilteredSnapshot(transport, agentConfig) },
            evidence = { snapshot ->
                val file = File(evidenceDir, "final-${clock.nowMs()}.json")
                file.writeText(snapshot.toString())
                file.absolutePath
            },
            emit = { event -> sink.on("evidence_event", event) },
            transport = transport,
            config = CompletionVerifier.Config(confirmTimeoutS = agentConfig.confirmTimeoutS),
            clockMs = { clock.nowMs() },
        )

        return AgentLoop(
            client = modelClient,
            catalog = catalog,
            pipeline = pipeline,
            observer = { floorFilteredSnapshot(transport, agentConfig) },
            preflight = { AhResult.ok(Unit) }, // §12 ran at start (AgentRunStarter); budgets re-check per turn
            finishVerifier = verifier,
            budget = budget,
            view = view,
            pauseGate = pauseGate,
        )
    }

    /** The REAL foreground package — for POLICY (deny decisions); the model sees the filtered view (§5.2 (4)). */
    private suspend fun foregroundOf(transport: BinderAgentTransport): String? {
        val env = transport.call("device.app", "foreground", JsonObject(emptyMap()))
        return (env["result"] as? JsonObject)?.get("package")?.let { (it as? JsonPrimitive)?.content }
    }

    /** Snapshot → §8 floor. A withheld observation becomes an honest EMPTY screen with its reason. */
    private fun floorFilteredSnapshot(transport: BinderAgentTransport, agentConfig: AgentConfig): JsonObject {
        val snapshot = transport.call(
            "device.ui", "snapshot",
            buildJsonObject { put("compact", false) },
        ).let { envelope -> (envelope["result"] as? JsonObject) ?: JsonObject(emptyMap()) }
        val filtered = ObservationFilter.filterSnapshot(snapshot, agentConfig.packages.toSet(), sensitivity)
        return if (!filtered.withheld) {
            filtered.observation ?: snapshot
        } else {
            buildJsonObject {
                put("keyboard", "hidden")
                put("node_count", 0)
                put("empty", true)
                put("empty_reason", "OBSERVATION_WITHHELD")
                put("withheld_reason", filtered.reason ?: "UNKNOWN")
                put("nodes", kotlinx.serialization.json.JsonArray(emptyList()))
            }
        }
    }
}

/**
 * The token-bound transport over the broker binder — the ONE action path for everything the
 * agent runtime does (model tools, approvals, observations). Envelope errors surface as an
 * error-shaped JsonObject (ok=false), matching what the pipeline/model expect; a refused call
 * never throws except for cancellation.
 */
class BinderAgentTransport(
    private val runToken: String,
    private val binder: app.autohost.broker.ICapabilityBroker,
    private val io: CoroutineDispatcher,
) : BrokerTransport {

    override suspend fun call(permission: String, method: String, args: JsonObject): JsonObject {
        val argsJson = AhJson.json.encodeToString(JsonObject.serializer(), args)
        val raw = try {
            withContext(io) { binder.call(runToken, permission, method, argsJson) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return errorEnvelope("INTERNAL", "the broker call failed: ${e.javaClass.simpleName}")
        }
        val envelope = runCatching {
            AhJson.json.decodeFromString(Envelope.serializer(), raw)
        }.getOrNull() ?: return errorEnvelope("INTERNAL", "unparsable broker envelope")
        return if (envelope.ok) {
            when (val result = envelope.result) {
                is JsonObject -> result
                null -> JsonObject(emptyMap())
                else -> buildJsonObject { put("value", result) }
            }
        } else {
            buildJsonObject {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", envelope.error?.code ?: "INTERNAL")
                    envelope.error?.reason?.let { put("reason", it) }
                    put("message", envelope.error?.message ?: "")
                })
            }
        }
    }

    private fun errorEnvelope(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }
}
