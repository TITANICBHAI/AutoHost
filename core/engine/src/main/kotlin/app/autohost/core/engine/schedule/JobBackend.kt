package app.autohost.core.engine.schedule

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import app.autohost.core.common.Clock

/**
 * P4-05: JobScheduler backend (plan §11.3) — the OEM-friendly middle ground some devices treat
 * better than WorkManager. Overdue constraints: jobs run when the system wills; the reconcile
 * worker still enforces the misfire policy, so an overlate job fire is reconciled like any other.
 */
class JobBackend(
    private val context: Context,
    private val serviceComponent: ComponentName,
    private val clock: Clock,
) : SchedulerBackend {

    override val kind: String = "job"

    override fun arm(fireId: String, atMs: Long, allowWhileIdle: Boolean): ArmResult {
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: return ArmResult.Refused("no_job_scheduler")
        val jobId = stableJobId(fireId)
        val delay = (atMs - clock.nowMs()).coerceAtLeast(0)
        val extras = PersistableBundle().apply { putString(EXTRA_FIRE_ID, fireId) }
        val info = JobInfo.Builder(jobId, serviceComponent)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + DEADLINE_SLACK_MS)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        return runCatching {
            scheduler.schedule(info)
            ArmResult.Armed
        }.getOrElse { ArmResult.Refused("job_schedule_failed:${it.message ?: "unknown"}") }
    }

    override fun cancel(fireId: String) {
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(stableJobId(fireId)) }
    }

    override fun canDeliverExact(): Boolean = false

    /** JobScheduler ids are ints; a stable hash keeps re-arms replacing the same job. */
    private fun stableJobId(fireId: String): Int = (JOB_ID_BASE + fireId.hashCode()).let {
        if (it < 0) it and 0x7fffffff else it
    }

    companion object {
        const val EXTRA_FIRE_ID = "fire_id"
        const val JOB_ID_BASE = 41_000
        const val DEADLINE_SLACK_MS = 5 * 60 * 1000L
    }
}
