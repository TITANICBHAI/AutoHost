package app.autohost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.autohost.core.designsystem.theme.AutoHostTheme
import app.autohost.ui.AppNavHost

/**
 * Single activity. Navigation is hand-rolled state (ADR-026: no navigation library until the feature
 * modules need deep links) with three wave-1 screens: Tasks, Run (live log), Setup.
 */
class MainActivity : ComponentActivity() {

    internal var route by mutableStateOf(Routes.LIBRARY)
        private set

    internal var runIdArg by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P7-07: a tile (or a notification deep link) can ask for a specific screen on cold start.
        when (intent?.getStringExtra(EXTRA_ROUTE)) {
            "surfaces" -> route = Routes.SURFACES
        }
        setContent {
            AutoHostTheme {
                AppNavHost(
                    activity = this,
                    route = route,
                    runId = runIdArg,
                    onNavigate = { target, runId ->
                        route = target
                        runIdArg = runId
                    },
                )
            }
        }
    }

    /** Used by the surfaces trampoline/tile to open the app (optionally on a specific screen). */
    fun openOn(routeName: String) {
        route = when (routeName) {
            "surfaces" -> Routes.SURFACES
            else -> Routes.LIBRARY
        }
        runIdArg = null
    }

    companion object {
        /** Intent extra naming the screen to open (`"surfaces"`); used by tiles and trampolines. */
        const val EXTRA_ROUTE = "route"

        fun intent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
    }
}

internal enum class Routes(val arg: String? = null) {
    LIBRARY,
    RUN,
    RUNS_HISTORY,
    SETUP,
    SCHEDULES,
    TASK_DETAIL,
    HEALTH,
    SETTINGS,
    SURFACES,
    RECORDER_REVIEW,
    STUDIO, // P11-05 (ADR-083): the in-app Task Studio
}
