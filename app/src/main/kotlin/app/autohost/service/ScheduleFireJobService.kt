package app.autohost.service

import android.app.job.JobParameters
import android.app.job.JobService
import app.autohost.AutoHostApp
import app.autohost.core.engine.schedule.JobBackend

/**
 * P4-05: the JobScheduler fire target. The job fires inside its window; the controller applies
 * the same fire path as alarms. Honesty note: an overlate job fire is reconciled like any other
 * (plan §11.3 — the misfire policy stays the single source of truth).
 */
class ScheduleFireJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val fireId = params?.extras?.getString(JobBackend.EXTRA_FIRE_ID) ?: return false
        val graph = (application as? AutoHostApp)?.graph ?: return false
        graph.scheduleController.onFire(fireId)
        jobFinished(params, false)
        return false // work handed off asynchronously
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
