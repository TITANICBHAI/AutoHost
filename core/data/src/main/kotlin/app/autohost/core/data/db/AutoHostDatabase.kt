package app.autohost.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * P2-06: the one database (plan §16). Schema is exported to `core/data/schemas/` (KSP arg in build.gradle.kts);
 * migrations ship as pure `Migration` objects in [Migrations] with migration tests (written now, run by the owner).
 * v2 (P4-04): trigger rules + schedule state/events (plan §11).
 */
@Database(
    entities = [
        TaskEntity::class, TaskVersionEntity::class, GrantEntity::class, TaskConfigEntity::class,
        ProfileEntity::class, SecretBlobEntity::class, KvEntity::class, RunEntity::class,
        StageRecordEntity::class, RunEventEntity::class, EvidenceEntity::class, AuditRecordEntity::class,
        AppSettingEntity::class, TriggerRuleEntity::class, ScheduleStateEntity::class, ScheduleEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AutoHostDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun taskVersionDao(): TaskVersionDao
    abstract fun grantDao(): GrantDao
    abstract fun taskConfigDao(): TaskConfigDao
    abstract fun profileDao(): ProfileDao
    abstract fun secretBlobDao(): SecretBlobDao
    abstract fun kvDao(): KvDao
    abstract fun runDao(): RunDao
    abstract fun stageRecordDao(): StageRecordDao
    abstract fun runEventDao(): RunEventDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun auditDao(): AuditDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun triggerRuleDao(): TriggerRuleDao
    abstract fun scheduleStateDao(): ScheduleStateDao
    abstract fun scheduleEventDao(): ScheduleEventDao

    companion object {
        const val NAME = "autohost.db"
    }
}

/** Room knows nothing about List<String>; the only non-primitive column type. */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString("\u001f")

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\u001f")
}

/** Migration chain. v1 is the first schema; future migrations append here (pure, tested, never edited). */
object Migrations {
    /** v1 → v2 (P4-04): trigger rules, schedule states, schedule events. */
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trigger_rules` (" +
                    "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`ruleId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `profileId` TEXT, " +
                    "`source` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `cooldownSeconds` INTEGER NOT NULL, " +
                    "`maxLaunchCount` INTEGER, `debounceMs` INTEGER NOT NULL, `busyPolicy` TEXT NOT NULL, " +
                    "`queueMax` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `armed` INTEGER NOT NULL, " +
                    "`lastFireAt` INTEGER, `launchCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trigger_rules_ruleId` ON `trigger_rules` (`ruleId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_rules_taskId` ON `trigger_rules` (`taskId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedule_states` (" +
                    "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`scheduleId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `ruleJson` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `nextFireAt` INTEGER, `armedFireId` TEXT, `backend` TEXT, " +
                    "`runsTotal` INTEGER NOT NULL, `runsToday` INTEGER NOT NULL, `runsDayKey` TEXT, " +
                    "`consecutiveFailures` INTEGER NOT NULL, `lastFireAt` INTEGER, `lastResult` TEXT, " +
                    "`lateTiming` INTEGER NOT NULL, `proposedNextFireAt` INTEGER, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_schedule_states_scheduleId` ON `schedule_states` (`scheduleId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_states_taskId` ON `schedule_states` (`taskId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedule_events` (" +
                    "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`scheduleId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                    "`detailJson` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_schedule_events_scheduleId_ts` " +
                    "ON `schedule_events` (`scheduleId`, `ts`)",
            )
        }
    }

    val ALL: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2)
}
