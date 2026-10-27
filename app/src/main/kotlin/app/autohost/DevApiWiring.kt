package app.autohost

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.autohost.core.data.db.AutoHostDatabase
import app.autohost.core.data.repo.DataGraph
import app.autohost.core.data.secrets.AndroidKeystoreAead
import app.autohost.core.data.secrets.Redactor
import app.autohost.core.data.secrets.SecretRegistry
import app.autohost.core.data.secrets.SecretStore
import app.autohost.core.devapi.DevApiBackend
import app.autohost.core.devapi.DevApiPolicy
import app.autohost.core.devapi.DevApiServer
import app.autohost.core.devapi.TokenStore
import app.autohost.core.engine.health.HealthEnv
import app.autohost.core.engine.health.HealthRegistry
import app.autohost.core.engine.health.InitialChecks
import app.autohost.core.engine.tasks.BundleExtractor
import app.autohost.core.engine.tasks.CapabilityCatalog
import app.autohost.core.engine.tasks.CapabilityResolver
import app.autohost.core.engine.tasks.DeviceProbe
import app.autohost.core.engine.tasks.GrantService
import app.autohost.core.engine.tasks.ImportPipeline
import app.autohost.core.model.Availability
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-12: the Android half of the device probe — PackageManager for apps, live availability from the
 * registered providers, and insecure-feature switches from settings.
 */
class AndroidDeviceProbe(
    private val context: Context,
    private val availability: (capabilityId: String) -> Availability?,
    private val switchLookup: (family: String) -> Boolean?,
) : DeviceProbe {
    override val sdkInt: Int = Build.VERSION.SDK_INT

    override fun appInfo(packageName: String): DeviceProbe.InstalledApp? {
        if (packageName.startsWith("\${")) return null // unresolved ${config.*} → cannot know yet
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            DeviceProbe.InstalledApp(
                packageName = packageName,
                versionCode = info.longVersionCode,
                versionName = info.versionName,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun availabilityOf(capabilityId: String): Availability? = availability(capabilityId)

    override fun switchEnabled(family: String): Boolean? = switchLookup(family)
}

/**
 * P2-14: snapshot of device state for the health registry. Reads only what exists on this API level;
 * missing pieces default to their pessimistic value so rows stay honest.
 */
object AndroidHealthEnv {
    fun build(context: Context, a11yConnected: Boolean, workerAlive: Boolean, devApiEnabled: Boolean): HealthEnv {
        val packageManager = context.packageManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val a11yEnabled = enabledServices.contains("${context.packageName}/")
        val notifications = if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-33: no runtime permission; channels are opt-out
        }
        val exactAlarms = if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true // API 29-30: exact alarms need no permission
        }
        val batteryExempt = try {
            val powerManager = context.getSystemService(android.os.PowerManager::class.java)
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            false
        }
        val secureSettings = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return HealthEnv(
            sdkInt = Build.VERSION.SDK_INT,
            accessibilityEnabled = a11yEnabled,
            accessibilityConnected = a11yConnected,
            notificationsPermission = notifications,
            // P8-01: live notification-listener connection (device.notifications availability).
            notificationListenerConnected = app.autohost.capabilities.notifications.NotificationBridge.connected,
            exactAlarmsAllowed = exactAlarms,
            batteryExemption = batteryExempt,
            writeSecureSettings = secureSettings,
            secureSettingsCanSelfHeal = secureSettings, // self-heal wiring lands with Phase 4 boot re-arm
            devApiEnabled = devApiEnabled,
            workerAlive = workerAlive,
        )
    }
}

/**
 * P2-22: the real DevApiBackend — routes JSON-RPC methods onto the broker (device.*), the repositories
 * (tasks.*) and the orchestrator's store (runs.*). Wire-up only; logic stays in the engine.
 */
class AppDevApiBackend(
    private val graph: AppGraph,
) : DevApiBackend {
    override fun systemInfo(): JsonObject = buildJsonObject {
        put("app", graph.application.packageName)
        put("flavor", app.autohost.BuildConfig.FLAVOR)
        put("version", BuildConfig.VERSION_NAME)
        put("api", Build.VERSION.SDK_INT)
        put("devapi", 1)
        put("providers", "a11y,app") // live provider list grows with capabilities
    }

    override fun capabilities(): JsonObject = graph.capabilityCatalog.let { catalog ->
        buildJsonObject {
            put(
                "capabilities",
                kotlinx.serialization.json.JsonArray(
                    catalog.all().map { spec ->
                        buildJsonObject {
                            put("id", spec.id)
                            put("summary", spec.summary)
                            put("availability", graph.probe.availabilityOf(spec.id)?.javaClass?.simpleName ?: "UNSUPPORTED")
                        }
                    },
                ),
            )
        }
    }

    override fun call(capability: String, method: String, args: JsonObject): JsonObject =
        graph.callAsDevApi(capability, method, args)

    override fun tasksList(): kotlinx.serialization.json.JsonArray = kotlinx.coroutines.runBlocking {
        kotlinx.serialization.json.JsonArray(
            graph.data.tasks.let { tasks ->
                tasks.observeActive().first().map { task ->
                    buildJsonObject {
                        put("id", task.taskId)
                        put("name", task.name)
                        put("version", task.activeVersion)
                        put("enabled", task.enabled)
                    }
                }
            },
        )
    }

    override fun tasksGet(taskId: String): JsonObject? = kotlinx.coroutines.runBlocking {
        val task = graph.data.tasks.byId(taskId) ?: return@runBlocking null
        val latest = graph.data.tasks.latestVersion(taskId)
        buildJsonObject {
            put("id", task.taskId)
            put("name", task.name)
            put("version", task.activeVersion)
            put("enabled", task.enabled)
            put("source", task.source)
            put("sha256", latest?.bundleSha256 ?: "")
        }
    }

    override fun tasksRemove(taskId: String, keepHistory: Boolean): JsonObject = kotlinx.coroutines.runBlocking {
        graph.taskRemover.remove(taskId, keepHistory)
        buildJsonObject { put("removed", true); put("keepHistory", keepHistory) }
    }

    override fun tasksSetConfig(taskId: String, values: JsonObject): JsonObject = kotlinx.coroutines.runBlocking {
        graph.data.tasks.saveConfig(taskId, values)
        buildJsonObject { put("saved", true) }
    }

    override fun runsStart(taskId: String, profileId: String?, args: JsonObject?): JsonObject =
        kotlinx.coroutines.runBlocking {
            val runId = graph.orchestrator.startDevApiRun(taskId, profileId, args)
            buildJsonObject { put("runId", runId) }
        }

    override fun runsList(): kotlinx.serialization.json.JsonArray = kotlinx.coroutines.runBlocking {
        kotlinx.serialization.json.JsonArray(
            graph.data.runs.observeRecent(50).first().map { run ->
                buildJsonObject {
                    put("id", run.runId)
                    put("task", run.taskId)
                    put("status", run.status)
                    put("startedAt", run.startedAt)
                }
            },
        )
    }

    override fun runsStatus(runId: String): JsonObject? = kotlinx.coroutines.runBlocking {
        val run = graph.data.runs.byRunId(runId) ?: return@runBlocking null
        buildJsonObject {
            put("id", run.runId)
            put("task", run.taskId)
            put("status", run.status)
            put("reasonCode", run.reasonCode)
            put("stage", run.currentStage)
        }
    }

    override fun runsTail(runId: String, sinceSeq: Long): kotlinx.serialization.json.JsonArray =
        kotlinx.coroutines.runBlocking {
            val events = graph.data.runs.eventsAfter(runId, sinceSeq)
            kotlinx.serialization.json.JsonArray(events.map { event ->
                buildJsonObject {
                    put("seq", event.seq)
                    put("ts", event.ts)
                    put("level", event.level)
                    put("stage", event.stage)
                    put("message", event.message)
                }
            })
        }

    override fun runsPause(runId: String): JsonObject {
        graph.orchestrator.requestPause(runId)
        return buildJsonObject { put("paused", true) }
    }

    override fun runsResume(runId: String): JsonObject {
        graph.orchestrator.requestResume(runId)
        return buildJsonObject { put("resumed", true) }
    }

    override fun runsCancel(runId: String): JsonObject {
        graph.orchestrator.cancel(runId)
        return buildJsonObject { put("cancelled", true) }
    }

    override fun healthRun(only: List<String>?): JsonObject {
        val env = AndroidHealthEnv.build(graph.application, graph.a11yConnected(), true, graph.devApiEnabled)
        val grouped = graph.healthRegistry.runGrouped(env)
        fun rows(list: List<app.autohost.core.engine.health.HealthCheck.Outcome>) = kotlinx.serialization.json.JsonArray(
            list.map { outcome ->
                buildJsonObject {
                    put("id", outcome.check.id)
                    put("title", outcome.check.title)
                    put("required", outcome.check.required)
                    when (val result = outcome.result) {
                        is app.autohost.core.engine.health.CheckResult.Ok -> put("status", "ok")
                        is app.autohost.core.engine.health.CheckResult.Issue -> {
                            put("status", "issue")
                            put("reasonCode", result.reasonCode)
                            put("message", result.message)
                            result.fixAction?.let { put("fixAction", it) }
                        }
                    }
                }
            },
        )
        return buildJsonObject {
            put("needs_fix", rows(grouped.needsFix))
            put("autofixable", rows(grouped.autofixable))
            put("optional", rows(grouped.optional))
        }
    }

    // ---- P5-11: the full Dev API surface (docs/DEVAPI.md §3) ----------------------------

    override fun triggersList(): kotlinx.serialization.json.JsonArray = kotlinx.coroutines.runBlocking {
        kotlinx.serialization.json.JsonArray(
            graph.data.triggers.observeAll().first().map { entity -> triggerJson(entity) },
        )
    }

    override fun triggersStatus(): kotlinx.serialization.json.JsonArray = kotlinx.coroutines.runBlocking {
        // P8-03: live status via the trigger runtime — guards, counters, cooldown remaining,
        // busy-queue depth and the §11.8 grant gate, per rule.
        kotlinx.serialization.json.JsonArray(
            graph.data.triggers.observeAll().first().map { entity ->
                graph.triggerController.statusOf(entity)
            },
        )
    }

    /** P8-03: the event-source catalog (§11.9 operations) — one honest row per source kind. */
    override fun triggersCatalog(): JsonObject = buildJsonObject {
        put("sources", kotlinx.serialization.json.JsonArray(
            app.autohost.core.engine.events.EventSourceCatalog.ALL.map { source ->
                buildJsonObject {
                    put("kind", source.kind)
                    put("access", source.access.name.lowercase())
                    source.requiredGrant?.let { put("required_grant", it) }
                    put("params", JsonObject(source.paramsDoc.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
                    if (source.note.isNotEmpty()) put("note", source.note)
                }
            },
        ))
        put("timed_sources", kotlinx.serialization.json.JsonArray(
            app.autohost.core.model.TriggerSource.TIMED.map { kotlinx.serialization.json.JsonPrimitive(it) },
        ))
    }

    override fun triggerUpsert(rule: JsonObject): JsonObject {
        val text = rule.toString()
        val parsed = app.autohost.core.model.TriggerRule.fromJson(text)
            ?: return buildJsonObject { put("__error", buildJsonObject { put("code", "INVALID_ARGS"); put("message", "rule does not match trigger-rule.schema.json") }) }
        val problems = parsed.validate()
        if (problems.isNotEmpty()) {
            return buildJsonObject {
                put("__error", buildJsonObject {
                    put("code", "TRIGGER_RULE_INVALID")
                    put("message", problems.joinToString(",") { "TRIGGER_RULE_INVALID($it)" })
                })
            }
        }
        return kotlinx.coroutines.runBlocking {
            val existing = parsed.id.takeIf { it.isNotBlank() }?.let { graph.data.triggers.byRuleId(it) }
            val entity = app.autohost.core.data.db.TriggerRuleEntity(
                rowId = existing?.rowId ?: 0,
                ruleId = parsed.id.ifBlank { app.autohost.core.common.Ids.ulid(graph.clock.nowMs()) },
                taskId = parsed.task,
                profileId = parsed.profile,
                source = parsed.source,
                paramsJson = parsed.params.toString(),
                cooldownSeconds = parsed.cooldownSeconds,
                maxLaunchCount = parsed.maxLaunchCount,
                debounceMs = parsed.debounceMs,
                busyPolicy = parsed.busyPolicy,
                queueMax = parsed.queueMax,
                enabled = parsed.enabled,
                armed = parsed.source in app.autohost.core.model.TriggerSource.TIMED,
            )
            graph.data.triggers.upsert(entity)
            // P8-03: the runtime reloads its cache and re-arms (time sources) / fixes the FGS need.
            graph.triggerController.refresh()
            buildJsonObject { put("id", entity.ruleId); put("armed", entity.armed) }
        }
    }

    override fun triggerDelete(ruleId: String): JsonObject = kotlinx.coroutines.runBlocking {
        val existing = graph.data.triggers.byRuleId(ruleId)
            ?: return@runBlocking buildJsonObject { put("__error", buildJsonObject { put("code", "NOT_FOUND"); put("message", "no rule $ruleId") }) }
        graph.data.triggers.delete(existing.ruleId)
        graph.triggerController.refresh()
        buildJsonObject { put("deleted", true) }
    }

    /** Dry test (default) or a live synthetic fire (`fire: true`, P8-03). */
    override fun triggerTest(rule: JsonObject, count: Int, fire: Boolean): kotlinx.serialization.json.JsonArray {
        val parsed = app.autohost.core.model.TriggerRule.fromJson(rule.toString())
            ?: return kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("error", "rule does not parse"); put("valid", false) },
            ))
        val problems = parsed.validate()
        val rows = mutableListOf<JsonObject>(
            buildJsonObject {
                put("valid", problems.isEmpty())
                put("problems", kotlinx.serialization.json.JsonArray(problems.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("source", parsed.source)
            },
        )
        val scheduleRule = parsed.toScheduleRule()
        if (scheduleRule != null && problems.isEmpty()) {
            val engine = graph.scheduleEngine
            val next = engine.nextFires(scheduleRule, graph.clock.nowMs(), n = count.coerceIn(1, 20))
            rows += next.map { atMs ->
                buildJsonObject { put("fire_at_ms", atMs); put("fire_at", java.time.Instant.ofEpochMilli(atMs).toString()) }
            }
        } else if (scheduleRule == null && fire && problems.isEmpty()) {
            // P8-03: dispatch a synthetic event for this rule through the live runtime.
            val stored = graph.data.triggers.byRuleId(parsed.id)
            if (stored != null && stored.enabled) {
                rows += graph.triggerController.testFire(stored.ruleId)
            } else {
                rows += buildJsonObject {
                    put("fired", false)
                    put("note", "rule is not installed/enabled — upsert it first (triggers.upsert)")
                }
            }
        } else if (scheduleRule == null) {
            rows += buildJsonObject {
                put("note", "event source: dry test validates params only; pass fire=true to dispatch a synthetic event")
            }
        }
        return kotlinx.serialization.json.JsonArray(rows)
    }

    override fun healthChecks(): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(
        graph.healthRegistry.all().map { check ->
            buildJsonObject {
                put("id", check.id)
                put("title", check.title)
                put("required", check.required)
                put("hasAutofix", check.hasAutofix())
            }
        },
    )

    /** P5-09: build the .aarun bundle (redacted) and return it base64-encoded. */
    override fun runsExport(runId: String): JsonObject? = kotlinx.coroutines.runBlocking {
        graph.runExporter.export(runId)?.let { bundle ->
            buildJsonObject {
                put("runId", runId)
                put("format", "aarun")
                put("bytes", bundle.size)
                put("bundle_b64", android.util.Base64.encodeToString(bundle, android.util.Base64.NO_WRAP))
            }
        }
    }

    // P6-09: recorder over the Dev API — an agent can arm the recorder, let a human perform the
    // task, then collect the flow document + sidecar JSON (plan §7.9 authoring loop). The screen
    // flow uses the same controller; the overlay is optional here (agents record headlessly).
    override fun recorderStart(): JsonObject {
        val started = graph.recorder.start()
        return if (started) buildJsonObject {
            put("recording", true)
            put("message", "recorder armed: perform the task on the device, then call recorder.stop")
        } else buildJsonObject {
            put("__error", buildJsonObject {
                put("code", "RECORDING_BLOCKED")
                put("message", graph.recorder.state.value.lastError ?: "could not arm the recorder")
            })
        }
    }

    override fun recorderStop(): JsonObject {
        val state = graph.recorder.stop()
        if (!state.recording && state.result == null) return buildJsonObject {
            put("__error", buildJsonObject {
                put("code", "NOT_RECORDING")
                put("message", "recorder.stop without recorder.start (nothing was armed)")
            })
        }
        return buildJsonObject {
            put("recording", false)
            put("steps", state.result?.steps?.size ?: 0)
            state.result?.warnings?.takeIf { it.isNotEmpty() }?.let { put("warnings", kotlinx.serialization.json.JsonArray(it.map { w -> kotlinx.serialization.json.JsonPrimitive(w) })) }
            state.flowJson?.let { put("flow", it) }
            state.result?.sidecar("devapi")?.let { put("sidecar", it.toJson()) }
            put("note", "save via the review screen, or write the flow + sidecar into the dev folder")
        }
    }

    private fun triggerJson(entity: app.autohost.core.data.db.TriggerRuleEntity): JsonObject = buildJsonObject {
        put("id", entity.ruleId)
        put("task", entity.taskId)
        entity.profileId?.let { put("profile", it) }
        put("source", entity.source)
        put("enabled", entity.enabled)
        put("armed", entity.armed)
        put("busy_policy", entity.busyPolicy)
        put("cooldown_seconds", entity.cooldownSeconds)
    }
}
