package app.autohost.surfaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.core.common.AhLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P7-08/P7-09: the trampoline behind pinned shortcuts and widget Run buttons. The user tapped a
 * launcher entry (a genuine interaction), this activity starts the run and finishes — transparent,
 * excluded from recents, nothing to see. Android 12+ background-start rules accept this path;
 * if the run is refused (unknown task) the app opens instead so the user sees why.
 */
class SurfaceRunActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as AutoHostApp).graph
        val taskId = intent?.getStringExtra(Shortcuts.EXTRA_TASK_ID)
        if (taskId.isNullOrEmpty()) {
            startActivity(MainActivity.intent(this))
            finish()
            return
        }
        val profileId = intent?.getStringExtra(Shortcuts.EXTRA_PROFILE_ID)?.takeIf { it.isNotEmpty() }
        val stage = intent?.getStringExtra(Shortcuts.EXTRA_STAGE)?.takeIf { it.isNotEmpty() }
        val source = intent?.getStringExtra(Shortcuts.EXTRA_SOURCE)?.takeIf { it.isNotEmpty() } ?: "shortcut"
        graph.appScope.launch {
            val runId = graph.surfaceRuns.start(
                taskId = taskId,
                profileId = profileId,
                stage = stage,
                source = source,
            )
            if (runId == null) {
                AhLog.w("surface", "trampoline run refused; opening app", buildJsonObject {
                    put("task_id", taskId)
                    put("source", source)
                })
                runCatching { startActivity(MainActivity.intent(this@SurfaceRunActivity)) }
            }
        }
        finish()
    }
}
