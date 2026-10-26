package app.autohost.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * P2-06: Room entities per plan §16 (data model). JSON payloads stay as TEXT columns decoded by the
 * repositories (Converters would hide schema drift; explicit JSON keeps migration diffs readable).
 * Every entity names its primary key explicitly; indexes cover the access paths the repos use.
 */

@Entity(tableName = "tasks", indices = [Index(value = ["taskId"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val taskId: String,
    val name: String,
    val activeVersion: String,
    val source: String, // bundle | dev-folder | sample | template
    val enabled: Boolean = true,
    val updatedAt: Long,
)

@Entity(tableName = "task_versions", indices = [Index(value = ["taskId", "version"], unique = true)])
data class TaskVersionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val taskId: String,
    val version: String,
    val manifestJson: String,
    val bundlePath: String,
    val bundleSha256: String,
    val importReportJson: String,
    val installedAt: Long,
)

@Entity(tableName = "grants", indices = [Index(value = ["taskId", "permission"], unique = true)])
data class GrantEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val taskId: String,
    val capability: String,
    val permission: String,
    val scopeJson: String,
    val fingerprint: String,
    val required: Boolean,
    val granted: Boolean,
    val grantedAt: Long,
)

@Entity(tableName = "task_config", indices = [Index(value = ["taskId"], unique = true)])
data class TaskConfigEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val taskId: String,
    val valuesJson: String,
    val schemaVersion: Int,
    val updatedAt: Long,
)

@Entity(tableName = "profiles", indices = [Index(value = ["taskId", "name"])])
data class ProfileEntity(
    @PrimaryKey val profileId: String, // ULID (Ids)
    val taskId: String,
    val name: String,
    val valuesJson: String, // non-secret profile values
    val secretKeys: List<String>,
    val targetJson: String? = null, // instance target (plan §9.2 P3)
    val archived: Boolean = false,
    val createdAt: Long,
    val lastRunAt: Long? = null,
)

@Entity(tableName = "secret_blobs", indices = [Index(value = ["profileId", "key"], unique = true)])
data class SecretBlobEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val profileId: String,
    val key: String,
    val ciphertext: ByteArray, // AEAD-encrypted (ciphertext||tag)
    val iv: ByteArray,
    val keyAlias: String,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is SecretBlobEntity && other.rowId == rowId
    override fun hashCode(): Int = rowId.hashCode()
}

@Entity(tableName = "kv_store", indices = [Index(value = ["scope", "ownerId", "key"], unique = true)])
data class KvEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val scope: String, // "task" | "profile"
    val ownerId: String, // taskId or profileId
    val key: String,
    val valueJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "runs", indices = [Index(value = ["runId"], unique = true), Index(value = ["taskId", "startedAt"])])
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String,
    val taskId: String,
    val profileId: String?,
    val trigger: String, // manual | schedule | event | external | chain | surface | proposed
    val triggerPayloadJson: String? = null,
    val status: String, // RunStatus names
    val reasonCode: String? = null,
    val attempt: Int = 1,
    val startedAt: Long,
    val endedAt: Long? = null,
    val currentStage: String? = null,
    val progress: Float? = null,
    val checkpointJson: String? = null,
    val resultJson: String? = null,
    val workerSlot: Int? = null,
)

@Entity(tableName = "stage_records", indices = [Index(value = ["runId", "name"], unique = true)])
data class StageRecordEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String,
    val name: String,
    val status: String, // pending | running | succeeded | failed | skipped
    val attempts: Int = 0,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val errorJson: String? = null,
    val onResume: String? = null, // verify | redo | manual
)

@Entity(tableName = "run_events", indices = [Index(value = ["runId", "seq"], unique = true)])
data class RunEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String,
    val seq: Long,
    val ts: Long,
    val level: String, // debug | info | warn | error
    val stage: String? = null,
    val message: String,
    val dataJson: String? = null, // already redacted before insert
)

@Entity(tableName = "evidence", indices = [Index(value = ["runId"])])
data class EvidenceEntity(
    @PrimaryKey val evidenceId: String,
    val runId: String,
    val stage: String? = null,
    val type: String, // screenshot | ui-dump | log-bundle | export
    val path: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val sensitivity: String = "normal", // normal | sensitive (never shown inline)
)

@Entity(tableName = "audit_records", indices = [Index(value = ["runId", "ts"])])
data class AuditRecordEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String?,
    val caller: String, // run | devapi | ui
    val ts: Long,
    val capability: String,
    val method: String,
    val outcome: String, // OK | <ErrorCode>
    val durationMs: Long,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String, // retention.days, devapi.enabled, devapi.token, switch.shell, ...
    val valueJson: String,
    val updatedAt: Long,
)

// ---------------------------------------------------------------------------
// P4-04: trigger rules + schedule state (plan §11.2/§11.3/§11.9). The rule JSON
// is stored verbatim (repositories own mapping); counters live in dedicated
// columns so the Schedule UI and reconcile worker read them without parsing.
// ---------------------------------------------------------------------------

@Entity(
    tableName = "trigger_rules",
    indices = [Index(value = ["ruleId"], unique = true), Index(value = ["taskId"])],
)
data class TriggerRuleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val ruleId: String, // ULID (Ids)
    val taskId: String,
    val profileId: String? = null,
    val source: String, // trigger-rule.schema.json source enum
    val paramsJson: String,
    val cooldownSeconds: Long,
    val maxLaunchCount: Int? = null,
    val debounceMs: Long,
    val busyPolicy: String, // queue | drop | replace
    val queueMax: Int,
    val enabled: Boolean,
    /** P4 arms time.* rules only; event sources stay armed=false until the Phase 8 EventHub. */
    val armed: Boolean,
    val lastFireAt: Long? = null,
    val launchCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "schedule_states", indices = [Index(value = ["scheduleId"], unique = true), Index(value = ["taskId"])])
data class ScheduleStateEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val scheduleId: String, // the rule id (sch_…)
    val taskId: String,
    val ruleJson: String, // ScheduleRule wire form (single source of truth for the shape)
    val enabled: Boolean,
    val nextFireAt: Long? = null,
    val armedFireId: String? = null, // the one armed alarm (plan §11.3)
    val backend: String? = null, // alarm | work | job
    val runsTotal: Long = 0,
    val runsToday: Int = 0,
    val runsDayKey: String? = null,
    val consecutiveFailures: Int = 0,
    val lastFireAt: Long? = null,
    val lastResult: String? = null, // SKIPPED:… / SUCCEEDED / FAILED:… summary for the UI
    val lateTiming: Boolean = false, // exact alarms denied → badge (G4-e)
    val proposedNextFireAt: Long? = null, // script-proposed override (P4-11), clamped
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "schedule_events", indices = [Index(value = ["scheduleId", "ts"])])
data class ScheduleEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val scheduleId: String,
    val ts: Long,
    val type: String, // FIRED | ARMED | MISFIRED | RECONCILED | LIMIT_HIT | PROPOSED | DISABLED
    val detailJson: String? = null,
)

// ---------------------------------------------------------------------------
// DAOs — suspend for one-shots, Flow for observation. Repositories own mapping.
// ---------------------------------------------------------------------------

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE enabled = 1 ORDER BY name")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId")
    suspend fun byId(taskId: String): TaskEntity?

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET enabled = :enabled WHERE taskId = :taskId")
    suspend fun setEnabled(taskId: String, enabled: Boolean)

    @Query("DELETE FROM tasks WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}

@Dao
interface TaskVersionDao {
    @Query("SELECT * FROM task_versions WHERE taskId = :taskId ORDER BY installedAt DESC")
    suspend fun versionsOf(taskId: String): List<TaskVersionEntity>

    @Query("SELECT * FROM task_versions WHERE taskId = :taskId AND version = :version")
    suspend fun byVersion(taskId: String, version: String): TaskVersionEntity?

    @Query("SELECT * FROM task_versions WHERE taskId = :taskId ORDER BY installedAt DESC LIMIT 1")
    suspend fun latest(taskId: String): TaskVersionEntity?

    @Upsert
    suspend fun upsert(version: TaskVersionEntity)

    @Query("DELETE FROM task_versions WHERE taskId = :taskId AND version = :version")
    suspend fun delete(taskId: String, version: String)
}

@Dao
interface GrantDao {
    @Query("SELECT * FROM grants WHERE taskId = :taskId")
    suspend fun grantsOf(taskId: String): List<GrantEntity>

    @Query("SELECT * FROM grants WHERE taskId = :taskId")
    fun observeGrantsOf(taskId: String): Flow<List<GrantEntity>>

    @Upsert
    suspend fun upsertAll(grants: List<GrantEntity>)

    @Query("DELETE FROM grants WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM grants WHERE taskId = :taskId AND permission = :permission")
    suspend fun delete(taskId: String, permission: String)
}

@Dao
interface TaskConfigDao {
    @Query("SELECT * FROM task_config WHERE taskId = :taskId")
    suspend fun byTask(taskId: String): TaskConfigEntity?

    @Upsert
    suspend fun upsert(config: TaskConfigEntity)

    @Query("DELETE FROM task_config WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE taskId = :taskId AND archived = 0 ORDER BY createdAt")
    fun observeActive(taskId: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE taskId = :taskId ORDER BY createdAt")
    suspend fun allOf(taskId: String): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE profileId = :profileId")
    suspend fun byId(profileId: String): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profiles SET archived = 1 WHERE profileId = :profileId")
    suspend fun archive(profileId: String)

    @Query("DELETE FROM profiles WHERE profileId = :profileId")
    suspend fun delete(profileId: String)

    @Query("UPDATE profiles SET lastRunAt = :at WHERE profileId = :profileId")
    suspend fun touchLastRun(profileId: String, at: Long)
}

@Dao
interface SecretBlobDao {
    @Query("SELECT * FROM secret_blobs WHERE profileId = :profileId AND `key` = :key")
    suspend fun byKey(profileId: String, key: String): SecretBlobEntity?

    @Query("SELECT * FROM secret_blobs WHERE profileId = :profileId")
    suspend fun allOf(profileId: String): List<SecretBlobEntity>

    @Upsert
    suspend fun upsert(blob: SecretBlobEntity)

    @Query("DELETE FROM secret_blobs WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String)
}

@Dao
interface KvDao {
    @Query("SELECT * FROM kv_store WHERE scope = :scope AND ownerId = :ownerId AND `key` = :key")
    suspend fun get(scope: String, ownerId: String, key: String): KvEntity?

    @Query("SELECT * FROM kv_store WHERE scope = :scope AND ownerId = :ownerId")
    suspend fun all(scope: String, ownerId: String): List<KvEntity>

    @Upsert
    suspend fun upsert(entry: KvEntity)

    @Query("DELETE FROM kv_store WHERE scope = :scope AND ownerId = :ownerId AND `key` = :key")
    suspend fun delete(scope: String, ownerId: String, key: String)

    @Query("DELETE FROM kv_store WHERE scope = :scope AND ownerId = :ownerId")
    suspend fun deleteAll(scope: String, ownerId: String)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs WHERE runId = :runId")
    suspend fun byRunId(runId: String): RunEntity?

    @Query("SELECT * FROM runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentOfTask(taskId: String, limit: Int = 50): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE status = :status")
    suspend fun byStatus(status: String): List<RunEntity>

    @Upsert
    suspend fun upsert(run: RunEntity)

    @Query("UPDATE runs SET status = :status, reasonCode = :reasonCode, endedAt = :endedAt WHERE runId = :runId")
    suspend fun finish(runId: String, status: String, reasonCode: String?, endedAt: Long)

    @Query("DELETE FROM runs WHERE runId = :runId")
    suspend fun deleteRun(runId: String)

    @Query("DELETE FROM runs WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int
}

@Dao
interface StageRecordDao {
    @Query("SELECT * FROM stage_records WHERE runId = :runId ORDER BY rowId")
    suspend fun stagesOf(runId: String): List<StageRecordEntity>

    @Upsert
    suspend fun upsert(stage: StageRecordEntity)

    @Query("DELETE FROM stage_records WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)
}

@Dao
interface RunEventDao {
    @Query("SELECT * FROM run_events WHERE runId = :runId AND seq > :sinceSeq ORDER BY seq LIMIT :limit")
    suspend fun after(runId: String, sinceSeq: Long, limit: Int = 200): List<RunEventEntity>

    @Query("SELECT MAX(seq) FROM run_events WHERE runId = :runId")
    suspend fun maxSeq(runId: String): Long?

    @Upsert
    suspend fun insert(event: RunEventEntity)

    @Query("DELETE FROM run_events WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)

    @Query("DELETE FROM run_events WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface EvidenceDao {
    @Query("SELECT * FROM evidence WHERE runId = :runId ORDER BY createdAt")
    suspend fun ofRun(runId: String): List<EvidenceEntity>

    @Upsert
    suspend fun upsert(evidence: EvidenceEntity)

    @Query("DELETE FROM evidence WHERE evidenceId = :evidenceId")
    suspend fun delete(evidenceId: String)

    @Query("DELETE FROM evidence WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_records WHERE runId = :runId ORDER BY ts")
    suspend fun ofRun(runId: String): List<AuditRecordEntity>

    @Upsert
    suspend fun insert(record: AuditRecordEntity)

    @Query("DELETE FROM audit_records WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun byKey(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings")
    suspend fun all(): List<AppSettingEntity>

    @Upsert
    suspend fun upsert(setting: AppSettingEntity)
}

@Dao
interface TriggerRuleDao {
    @Query("SELECT * FROM trigger_rules ORDER BY createdAt")
    fun observeAll(): Flow<List<TriggerRuleEntity>>

    @Query("SELECT * FROM trigger_rules WHERE taskId = :taskId ORDER BY createdAt")
    suspend fun ofTask(taskId: String): List<TriggerRuleEntity>

    @Query("SELECT * FROM trigger_rules WHERE ruleId = :ruleId")
    suspend fun byRuleId(ruleId: String): TriggerRuleEntity?

    @Query("SELECT * FROM trigger_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<TriggerRuleEntity>

    @Upsert
    suspend fun upsert(rule: TriggerRuleEntity)

    @Query("DELETE FROM trigger_rules WHERE ruleId = :ruleId")
    suspend fun delete(ruleId: String)

    @Query("UPDATE trigger_rules SET lastFireAt = :at, launchCount = launchCount + 1 WHERE ruleId = :ruleId")
    suspend fun touchFired(ruleId: String, at: Long)

    @Query("UPDATE trigger_rules SET armed = :armed, updatedAt = :at WHERE ruleId = :ruleId")
    suspend fun setArmed(ruleId: String, armed: Boolean, at: Long)
}

@Dao
interface ScheduleStateDao {
    @Query("SELECT * FROM schedule_states ORDER BY taskId")
    fun observeAll(): Flow<List<ScheduleStateEntity>>

    @Query("SELECT * FROM schedule_states WHERE scheduleId = :scheduleId")
    suspend fun byScheduleId(scheduleId: String): ScheduleStateEntity?

    @Query("SELECT * FROM schedule_states WHERE taskId = :taskId")
    suspend fun ofTask(taskId: String): List<ScheduleStateEntity>

    @Query("SELECT * FROM schedule_states WHERE enabled = 1")
    suspend fun enabledSchedules(): List<ScheduleStateEntity>

    @Upsert
    suspend fun upsert(state: ScheduleStateEntity)

    @Query("DELETE FROM schedule_states WHERE scheduleId = :scheduleId")
    suspend fun delete(scheduleId: String)

    @Query(
        "UPDATE schedule_states SET nextFireAt = :nextFireAt, armedFireId = :fireId, backend = :backend, " +
            "lateTiming = :lateTiming, updatedAt = :at WHERE scheduleId = :scheduleId",
    )
    suspend fun updateArming(
        scheduleId: String,
        nextFireAt: Long?,
        fireId: String?,
        backend: String?,
        lateTiming: Boolean,
        at: Long,
    )

    @Query(
        "UPDATE schedule_states SET runsTotal = runsTotal + 1, runsToday = :runsToday, runsDayKey = :dayKey, " +
            "lastFireAt = :at, lastResult = :result, consecutiveFailures = :consecutiveFailures, " +
            "proposedNextFireAt = NULL, updatedAt = :at WHERE scheduleId = :scheduleId",
    )
    suspend fun recordFire(
        scheduleId: String,
        runsToday: Int,
        dayKey: String,
        result: String,
        consecutiveFailures: Int,
        at: Long,
    )

    @Query("UPDATE schedule_states SET proposedNextFireAt = :atMs, updatedAt = :at WHERE scheduleId = :scheduleId")
    suspend fun setProposedNextFire(scheduleId: String, atMs: Long?, at: Long)
}

@Dao
interface ScheduleEventDao {
    @Query("SELECT * FROM schedule_events WHERE scheduleId = :scheduleId ORDER BY ts DESC LIMIT :limit")
    suspend fun recentOf(scheduleId: String, limit: Int = 20): List<ScheduleEventEntity>

    @Upsert
    suspend fun insert(event: ScheduleEventEntity)

    @Query("DELETE FROM schedule_events WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM schedule_events WHERE scheduleId = :scheduleId")
    suspend fun deleteForSchedule(scheduleId: String)
}
