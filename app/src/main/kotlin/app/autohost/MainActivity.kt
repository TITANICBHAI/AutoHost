package app.autohost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.autohost.core.designsystem.theme.AutoHostTheme
import app.autohost.diag.CrashLog
import app.autohost.nav.NavStack
import app.autohost.ui.AppNavHost
import app.autohost.ui.CrashReportScreen

/**
 * Single activity. Navigation is hand-rolled state (ADR-026: no navigation library until the feature
 * modules need deep links) with three wave-1 screens: Tasks, Run (live log), Setup.
 */
class MainActivity : ComponentActivity() {

    /** U-02: the screens walked through, so Back returns to the previous screen instead of leaving the app. */
    private var stack by mutableStateOf(NavStack())

    internal val route: Routes get() = Routes.valueOf(stack.top.route)

    internal val runIdArg: String? get() = stack.top.arg

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_STACK, ArrayList(stack.encode()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stack = NavStack.decode(savedInstanceState?.getStringArrayList(STATE_STACK)) { name ->
            Routes.entries.any { it.name == name }
        }
        // Crash-fix 1.7: if the object graph could not be built, show why instead of crashing on the first screen.
        val startupFailure = (application as AutoHostApp).startupFailure
        if (startupFailure != null) {
            val failureReport = CrashLog.pending(this) ?: CrashLog.describe(startupFailure)
            setContent {
                AutoHostTheme {
                    CrashReportScreen(title = "AutoHost could not start", report = failureReport, onDismiss = null)
                }
            }
            return
        }
        // Crash-fix 1.7: a report left by the previous run (a crash, or a background failure we survived) shows first.
        val previousReport = CrashLog.pending(this)
        // P7-07: a tile (or a notification deep link) can ask for a specific screen on cold start.
        if (savedInstanceState == null) {
            when (intent?.getStringExtra(EXTRA_ROUTE)) {
                "surfaces" -> stack = stack.navigate(Routes.SURFACES.name)
            }
        }
        setContent {
            AutoHostTheme {
                var crashReport by remember { mutableStateOf(previousReport) }
                val shownReport = crashReport
                if (shownReport != null) {
                    CrashReportScreen(
                        title = "AutoHost recorded a problem on its previous run",
                        report = shownReport,
                        onDismiss = {
                            CrashLog.clearPending(applicationContext)
                            crashReport = null
                        },
                    )
                } else {
                    BackHandler(enabled = stack.canPop) { stack = stack.pop() }
                    AppNavHost(
                        activity = this,
                        route = route,
                        runId = runIdArg,
                        onNavigate = { target, runId -> stack = stack.navigate(target.name, runId) },
                    )
                }
            }
        }
    }

    /** Used by the surfaces trampoline/tile to open the app (optionally on a specific screen). */
    fun openOn(routeName: String) {
        stack = when (routeName) {
            "surfaces" -> stack.navigate(Routes.SURFACES.name)
            else -> NavStack()
        }
    }

    companion object {
        /** Intent extra naming the screen to open (`"surfaces"`); used by tiles and trampolines. */
        const val EXTRA_ROUTE = "route"

        private const val STATE_STACK = "nav_stack"

        fun intent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
    }
}

enum class Routes(val arg: String? = null) {
    LIBRARY,
    RUN,
    RUNS_HISTORY,
    SETUP,
    SCHEDULES,
    TASK_DETAIL,
    HEALTH,
    SETTINGS,
    AI_SETTINGS, // P14-06 (S32): Settings → AI features
    ASK, // P14-07 (S33): the Ask bar/sheet
    SURFACES,
    RECORDER_REVIEW,
    STUDIO, // P11-05 (ADR-083): the in-app Task Studio
}
