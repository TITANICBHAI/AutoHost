package app.autohost.surfaces

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.autohost.AutoHostApp
import kotlinx.coroutines.flow.first

/**
 * P7-09: the home-screen widget (plan §10.3 — "Run button + last status for chosen tasks").
 * Jetpack Glance, one instance showing up to [MAX_ROWS] tasks: task name, last run status and a
 * Run button that taps through [SurfaceRunActivity] (the same trampoline as the pinned shortcuts,
 * so the FGS start rides a real user interaction). The task set is every installed task, narrowed
 * by the Surfaces widget picker (`SurfacesPrefs.widgetTaskIds`, empty = all); run-status changes
 * refresh the widgets from the AppGraph run collector (throttled in [SurfaceRuns]).
 *
 * Widget metadata: `res/xml/task_runs_widget_info.xml`; receiver declared in the manifest.
 */
class TaskRunsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as AutoHostApp).graph
        val wanted = graph.surfacesPrefs.widgetTaskIds()
        val tasks = graph.data.tasks.observeActive().first()
            .filter { wanted.isEmpty() || it.taskId in wanted }
            .take(MAX_ROWS)
        val rows = tasks.map { entity ->
            val last = runCatching { graph.database.runDao().recentOfTask(entity.taskId, 1).firstOrNull() }.getOrNull()
            RowData(
                taskId = entity.taskId,
                name = entity.name,
                lastStatus = last?.status ?: "",
                stage = last?.currentStage,
                runIntent = Shortcuts.runIntent(context, entity.taskId, source = "widget"),
            )
        }
        provideContent { Content(rows) }
    }

    data class RowData(
        val taskId: String,
        val name: String,
        val lastStatus: String,
        val stage: String?,
        val runIntent: Intent,
    )

    @Composable
    private fun Content(rows: List<RowData>) {
        GlanceTheme {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(8.dp)
                    .background(GlanceTheme.colors.background).cornerRadius(12.dp),
            ) {
                Text(
                    "AutoHost",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
                )
                if (rows.isEmpty()) {
                    Text(
                        "No tasks yet — push a task, then refresh.",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.padding(top = 4.dp),
                    )
                } else {
                    rows.forEach { row -> TaskRow(row) }
                }
            }
        }
    }

    @Composable
    private fun TaskRow(row: RowData) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    row.name,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    statusLabel(row),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            androidx.glance.appwidget.Button(
                text = "Run",
                onClick = actionStartActivity(row.runIntent),
            )
        }
    }

    private fun statusLabel(row: RowData): String = when (row.lastStatus) {
        "" -> "never run"
        "SUCCEEDED" -> "✓ last run ok"
        "FAILED" -> "✕ failed"
        "CANCELLED" -> "∅ cancelled"
        "INTERRUPTED" -> "! interrupted"
        "RUNNING", "QUEUED", "STARTING" -> "● ${row.stage ?: row.lastStatus.lowercase()}"
        "WAITING_USER" -> "waiting for you"
        else -> row.lastStatus.lowercase()
    }

    private companion object {
        const val MAX_ROWS = 5
    }
}

/** Manifest receiver; forwards system widget lifecycle to [TaskRunsWidget]. */
class TaskRunsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TaskRunsWidget()
}
