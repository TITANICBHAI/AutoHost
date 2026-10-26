package app.autohost.surfaces

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autohost.AutoHostApp
import app.autohost.core.common.AhLog
import app.autohost.core.engine.surfaces.ExternalRunRequest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P7-10: the token-guarded external trigger (plan §10.3 "External triggers"; §14 minimal exported
 * components). This receiver is exported so other apps (Tasker, another device via an automator)
 * can start a task — guarded by the per-install token (Surfaces settings shows + rotates it):
 *
 *     am broadcast -n <pkg>/app.autohost.surfaces.RunTaskReceiver -a app.autohost.RUN_TASK \
 *         --es token TOKEN --es task TASK [--es profile P] [--es stage S] [--es payload '{"k":1}']
 *
 * Wrong/missing token: the broadcast is dropped, logged, and the result code says BAD_TOKEN —
 * it never starts anything. A valid request starts the run with the `external` trigger.
 */
class RunTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExternalRunRequest.ACTION) {
            resultCode = ExternalRunRequest.RESULT_REJECTED
            return
        }
        val graph = AutoHostApp.of(context).graph
        val expected = graph.surfacesPrefs.externalToken()
        val outcome = ExternalRunRequest.parse(
            token = intent.getStringExtra(ExternalRunRequest.EXTRA_TOKEN),
            task = intent.getStringExtra(ExternalRunRequest.EXTRA_TASK),
            profile = intent.getStringExtra(ExternalRunRequest.EXTRA_PROFILE),
            stage = intent.getStringExtra(ExternalRunRequest.EXTRA_STAGE),
            payloadJson = intent.getStringExtra(ExternalRunRequest.EXTRA_PAYLOAD),
            expectedToken = expected,
            triggerName = intent.getStringExtra(ExternalRunRequest.EXTRA_TRIGGER),
        )
        when (outcome) {
            is ExternalRunRequest.Outcome.Rejected -> {
                AhLog.w(TAG, "RUN_TASK rejected", buildJsonObject {
                    put("reason", outcome.reason)
                })
                resultCode = if (outcome.reason == "BAD_TOKEN" || outcome.reason == "MISSING_TOKEN" || outcome.reason == "NO_TOKEN_CONFIGURED") {
                    ExternalRunRequest.RESULT_BAD_TOKEN
                } else {
                    ExternalRunRequest.RESULT_REJECTED
                }
            }
            is ExternalRunRequest.Outcome.Accepted -> {
                val pending = goAsync()
                graph.appScope.launch {
                    try {
                        // P8-03: the trigger form fires every enabled `external` rule with that name.
                        if (outcome.triggerName != null) {
                            val matched = graph.triggerController.externalFire(
                                outcome.triggerName,
                                outcome.payload,
                            )
                            resultCode = if (matched) {
                                ExternalRunRequest.RESULT_STARTED
                            } else {
                                ExternalRunRequest.RESULT_TASK_NOT_RUNNABLE
                            }
                        } else {
                            val runId = graph.surfaceRuns.start(
                                taskId = outcome.taskId,
                                profileId = outcome.profileId,
                                stage = outcome.stage,
                                triggerType = SurfaceRuns.TRIGGER_EXTERNAL,
                                source = "intent",
                            )
                            resultCode = if (runId != null) ExternalRunRequest.RESULT_STARTED else ExternalRunRequest.RESULT_TASK_NOT_RUNNABLE
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "run-task"
    }
}
