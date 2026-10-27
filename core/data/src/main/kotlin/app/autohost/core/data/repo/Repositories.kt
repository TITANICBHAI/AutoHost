package app.autohost.core.data.repo

import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.data.db.AppSettingDao
import app.autohost.core.data.db.AppSettingEntity
import app.autohost.core.data.db.AutoHostDatabase
import app.autohost.core.data.db.GrantDao
import app.autohost.core.data.db.GrantEntity
import app.autohost.core.data.db.KvDao
import app.autohost.core.data.db.KvEntity
import app.autohost.core.data.db.ProfileDao
import app.autohost.core.data.db.ProfileEntity
import app.autohost.core.data.db.RunDao
import app.autohost.core.data.db.RunEntity
import app.autohost.core.data.db.ScheduleEventEntity
import app.autohost.core.data.db.ScheduleStateEntity
import app.autohost.core.data.db.TriggerRuleEntity
import app.autohost.core.engine.tasks.GrantService
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/*
 * P2-07: repositories are the only DB API the rest of the app sees. They own JSON (de)serialization so
 * entities stay dumb, and they keep `Ids`/`Clock` usage central (AGENTS.md §4).
 */

class TaskRepository(private val db: AutoHostDatabase, private val clock: Clock) {
    private val tasks = db.taskDao()
    private val versions = db.taskVersionDao()
    private val config = db.taskConfigDao()

    fun observeActive(): Flow<List<app.autohost.core.data.db.TaskEntity>> = tasks.observeActive()

    suspend fun byId(taskId: String) = tasks.byId(taskId)

    suspend fun latestVersion(taskId: String) = versions.latest(taskId)

    suspend fun versionsOf(taskId: String) = versions.versionsOf(taskId)

    suspend fun installVersion(
        taskId: String,
        manifestJson: String,
        version: String,
        bundlePath: String,
        sha256: String,
        importReportJson: String,
        source: String,
        name: String,
    ) {
        versions.upsert(
            app.autohost.core.data.db.TaskVersionEntity(
                taskId = taskId,
                version = version,
                manifestJson = manifestJson,
                bundlePath = bundlePath,
                bundleSha256 = sha256,
                importReportJson = importReportJson,
                installedAt = clock.nowMs(),
            ),
        )
        tasks.upsert(
            app.autohost.core.data.db.TaskEntity(
                taskId = taskId,
                name = name,
                activeVersion = version,
                source = source,
                updatedAt = clock.nowMs(),
            ),
        )
    }

    suspend fun remove(taskId: String) {
        tasks.delete(taskId)
        versions.versionsOf(taskId).forEach { versions.delete(it.taskId, it.version) }
        config.delete(taskId)
    }

    suspend fun setEnabled(taskId: String, enabled: Boolean) = tasks.setEnabled(taskId, enabled)

    suspend fun configOf(taskId: String): JsonObject =
        config.byTask(taskId)?.valuesJson?.let { parseObject(it) } ?: JsonObject(emptyMap())

    suspend fun saveConfig(taskId: String, values: JsonObject, schemaVersion: Int = 1) {
        config.upsert(
            app.autohost.core.data.db.TaskConfigEntity(
                taskId = taskId,
                valuesJson = values.toString(),
                schemaVersion = schemaVersion,
                updatedAt = clock.nowMs(),
            ),
        )
    }

    private fun parseObject(text: String): JsonObject = try {
        kotlinx.serialization.json.Json.decodeFromString(JsonObject.serializer(), text)
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }
}

class ProfileRepository(private val db: AutoHostDatabase, private val clock: Clock) {
    private val dao = db.profileDao()

    fun observeActive(taskId: String): Flow<List<ProfileEntity>> = dao.observeActive(taskId)

    suspend fun byId(profileId: String) = dao.byId(profileId)

    suspend fun allOf(taskId: String) = dao.allOf(taskId)

    suspend fun create(taskId: String, name: String, valuesJson: String, secretKeys: List<String>, targetJson: String? = null): ProfileEntity {
        val profile = ProfileEntity(
            profileId = Ids.ulid(),
            taskId = taskId,
            name = name,
            valuesJson = valuesJson,
            secretKeys = secretKeys,
            targetJson = targetJson,
            createdAt = clock.nowMs(),
        )
        dao.upsert(profile)
        return profile
    }

    suspend fun update(profile: ProfileEntity) = dao.upsert(profile.copy())

    suspend fun archive(profileId: String) = dao.archive(profileId)

    suspend fun delete(profileId: String) = dao.delete(profileId)

    suspend fun touchLastRun(profileId: String) = dao.touchLastRun(profileId, clock.nowMs())
}

class GrantRepository(private val db: AutoHostDatabase, private val clock: Clock, private val grants: GrantService) {

    suspend fun recordsOf(taskId: String): List<GrantService.GrantRecord> =
        db.grantDao().grantsOf(taskId).map { it.toRecord() }

    suspend fun replaceAll(taskId: String, records: List<GrantService.GrantRecord>, granted: Boolean) {
        val dao: GrantDao = db.grantDao()
        dao.deleteForTask(taskId)
        dao.upsertAll(
            records.map { record ->
                GrantEntity(
                    taskId = taskId,
                    capability = record.capability,
                    permission = record.permission,
                    scopeJson = record.scope.toString(),
                    fingerprint = record.fingerprint,
                    required = record.required,
                    granted = granted,
                    grantedAt = clock.nowMs(),
                )
            },
        )
    }

    suspend fun fromEntities(taskId: String): List<GrantService.GrantRecord> =
        recordsOf(taskId)

    private fun GrantEntity.toRecord() = GrantService.GrantRecord(
        permission = permission,
        capability = capability,
        scope = parseObject(scopeJson),
        fingerprint = fingerprint,
        required = required,
    )

    private fun parseObject(text: String): JsonObject = try {
        kotlinx.serialization.json.Json.decodeFromString(JsonObject.serializer(), text)
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }
}

/** P2-09: `ctx.profile_store` / `ctx.task_store` backends (persisted; the engine's KvBackend interface). */
class DbKvBackend(private val dao: KvDao, private val clock: Clock) :
    app.autohost.core.engine.host.KvBackend {

    override fun get(owner: String, key: String): kotlinx.serialization.json.JsonElement? {
        val row = kotlinx.coroutines.runBlocking { dao.get(scopeFor(owner), owner, key) } ?: return null
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(row.valueJson)
        } catch (e: Exception) {
            null
        }
    }

    override fun set(owner: String, key: String, value: kotlinx.serialization.json.JsonElement) {
        kotlinx.coroutines.runBlocking {
            dao.upsert(
                KvEntity(
                    scope = scopeFor(owner),
                    ownerId = owner,
                    key = key,
                    valueJson = value.toString(),
                    updatedAt = clock.nowMs(),
                ),
            )
        }
    }

    override fun delete(owner: String, key: String): Boolean =
        kotlinx.coroutines.runBlocking { dao.get(scopeFor(owner), owner, key) }?.let { existing ->
            kotlinx.coroutines.runBlocking { dao.delete(scopeFor(owner), owner, key) }
            existing.rowId != 0L
        } ?: false

    override fun keys(owner: String, prefix: String): List<String> =
        kotlinx.coroutines.runBlocking { dao.all(scopeFor(owner), owner) }
            .map { it.key }
            .filter { it.startsWith(prefix) }
            .sorted()

    /** Owner ids are prefixed ("task:<id>" / "profile:<id>") in the engine's StoreProvider; mirror that. */
    private fun scopeFor(ownerId: String): String = if (ownerId.startsWith("profile:")) "profile" else "task"
}

class SettingsRepository(private val dao: AppSettingDao, private val clock: Clock) {

    suspend fun bool(key: String, default: Boolean = false): Boolean =
        dao.byKey(key)?.valueJson?.toBooleanStrictOrNull() ?: default

    suspend fun setBool(key: String, value: Boolean) =
        dao.upsert(AppSettingEntity(key, value.toString(), clock.nowMs()))

    suspend fun string(key: String, default: String? = null): String? = dao.byKey(key)?.valueJson ?: default

    suspend fun setString(key: String, value: String) =
        dao.upsert(AppSettingEntity(key, value, clock.nowMs()))

    suspend fun all(): Map<String, String> = dao.all().associate { it.key to it.valueJson }
}

/** Retention sweep (plan §13.1): logs and finished runs older than the window are pruned. */
class Retention(private val db: AutoHostDatabase, private val clock: Clock) {
    suspend fun sweep(daysToKeep: Int = 14) {
        val before = clock.nowMs() - daysToKeep * 24L * 60 * 60 * 1000
        db.runEventDao().deleteOlderThan(before)
        db.auditDao().deleteOlderThan(before)
        db.runDao().deleteFinishedBefore(before)
        db.evidenceDao().deleteExpired(clock.nowMs())
        db.scheduleEventDao().deleteOlderThan(before)
    }
}

/**
 * P4-04: schedule state persistence (plan §11.3) — one row per armed schedule carrying the rule
 * JSON verbatim plus the counters the engine and the reconcile worker need. Repositories own the
 * JSON mapping (see file header), so entities stay dumb.
 */
class ScheduleRepository(private val db: AutoHostDatabase, private val clock: Clock) {
    private val dao = db.scheduleStateDao()
    private val events = db.scheduleEventDao()

    fun observeAll(): Flow<List<ScheduleStateEntity>> = dao.observeAll()

    suspend fun byScheduleId(scheduleId: String) = dao.byScheduleId(scheduleId)

    suspend fun ofTask(taskId: String) = dao.ofTask(taskId)

    suspend fun enabledSchedules() = dao.enabledSchedules()

    suspend fun upsert(entity: ScheduleStateEntity) = dao.upsert(entity)

    suspend fun delete(scheduleId: String) {
        dao.delete(scheduleId)
        events.deleteForSchedule(scheduleId)
    }

    suspend fun updateArming(
        scheduleId: String,
        nextFireAt: Long?,
        fireId: String?,
        backend: String?,
        lateTiming: Boolean,
    ) = dao.updateArming(scheduleId, nextFireAt, fireId, backend, lateTiming, clock.nowMs())

    suspend fun recordFire(
        scheduleId: String,
        runsToday: Int,
        dayKey: String,
        result: String,
        consecutiveFailures: Int,
    ) = dao.recordFire(scheduleId, runsToday, dayKey, result, consecutiveFailures, clock.nowMs())

    suspend fun setProposedNextFire(scheduleId: String, atMs: Long?) =
        dao.setProposedNextFire(scheduleId, atMs, clock.nowMs())

    suspend fun recordEvent(scheduleId: String, type: String, detailJson: String? = null) =
        events.insert(
            ScheduleEventEntity(scheduleId = scheduleId, ts = clock.nowMs(), type = type, detailJson = detailJson),
        )

    suspend fun recentEvents(scheduleId: String, limit: Int = 20) = events.recentOf(scheduleId, limit)
}

/** P4-04: trigger-rule persistence (plan §11.9); event sources stay unarmed until Phase 8. */
class TriggerRepository(private val db: AutoHostDatabase, private val clock: Clock) {
    private val dao = db.triggerRuleDao()
    private val events = db.scheduleEventDao()

    fun observeAll(): Flow<List<TriggerRuleEntity>> = dao.observeAll()

    suspend fun ofTask(taskId: String) = dao.ofTask(taskId)

    suspend fun byRuleId(ruleId: String) = dao.byRuleId(ruleId)

    suspend fun enabledRules() = dao.enabledRules()

    suspend fun upsert(entity: TriggerRuleEntity) = dao.upsert(entity)

    suspend fun delete(ruleId: String) = dao.delete(ruleId)

    suspend fun touchFired(ruleId: String) = dao.touchFired(ruleId, clock.nowMs())

    suspend fun setArmed(ruleId: String, armed: Boolean) = dao.setArmed(ruleId, armed, clock.nowMs())

    /** P8-03: the granted permission ids of a task — the event-delivery grant gate (§11.8). */
    suspend fun grantedPermissionsOf(taskId: String): Set<String> =
        db.grantDao().grantsOf(taskId).filter { it.granted }.map { it.permission }.toSet()

    /**
     * P8-03: trigger-rule events reuse the schedule_events stream (one honest history for both
     * arming models); the rule id is the scheduleId column value.
     */
    suspend fun recordEvent(ruleId: String, type: String, detailJson: String? = null) =
        events.insert(
            ScheduleEventEntity(scheduleId = ruleId, ts = clock.nowMs(), type = type, detailJson = detailJson),
        )

    suspend fun recentEvents(ruleId: String, limit: Int = 20) = events.recentOf(ruleId, limit)
}

/** Convenience handle bundling every repository over one open database. */
class DataGraph(db: AutoHostDatabase, clock: Clock, grants: GrantService) {
    val tasks = TaskRepository(db, clock)
    val profiles = ProfileRepository(db, clock)
    val grants = GrantRepository(db, clock, grants)
    val kv = DbKvBackend(db.kvDao(), clock)
    val settings = SettingsRepository(db.appSettingDao(), clock)
    val runs = RunRepository(db.runDao(), db.stageRecordDao(), db.runEventDao(), db.evidenceDao(), clock)
    val retention = Retention(db, clock)
    val schedules = ScheduleRepository(db, clock)
    val triggers = TriggerRepository(db, clock)
    val runDao: RunDao = db.runDao()

    class RunRepository(
        private val runs: RunDao,
        private val stages: app.autohost.core.data.db.StageRecordDao,
        private val events: app.autohost.core.data.db.RunEventDao,
        private val evidence: app.autohost.core.data.db.EvidenceDao,
        private val clock: Clock,
    ) {
        fun observeRecent(limit: Int = 20): Flow<List<RunEntity>> = runs.observeRecent(limit)

        suspend fun byRunId(runId: String) = runs.byRunId(runId)

        suspend fun recentOfTask(taskId: String, limit: Int = 50) = runs.recentOfTask(taskId, limit)

        suspend fun record(run: RunEntity) = runs.upsert(run)

        suspend fun finish(runId: String, status: String, reasonCode: String?) =
            runs.finish(runId, status, reasonCode, clock.nowMs())

        suspend fun stagesOf(runId: String) = stages.stagesOf(runId)

        suspend fun recordStage(stage: app.autohost.core.data.db.StageRecordEntity) = stages.upsert(stage)

        suspend fun eventsAfter(runId: String, sinceSeq: Long, limit: Int = 200) = events.after(runId, sinceSeq, limit)

        suspend fun appendEvent(event: app.autohost.core.data.db.RunEventEntity) = events.insert(event)

        suspend fun maxSeq(runId: String) = events.maxSeq(runId) ?: 0L

        suspend fun evidenceOf(runId: String) = evidence.ofRun(runId)

        suspend fun recordEvidence(entity: app.autohost.core.data.db.EvidenceEntity) = evidence.upsert(entity)
    }
}
