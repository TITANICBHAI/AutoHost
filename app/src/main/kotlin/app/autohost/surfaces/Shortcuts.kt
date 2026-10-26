package app.autohost.surfaces

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import app.autohost.MainActivity
import app.autohost.R

/**
 * P7-08: pinned shortcuts (plan §10.3 — "one-tap launch of a task/profile from the launcher").
 * The launcher icon taps through a trampoline activity ([SurfaceRunActivity]) which starts the run
 * with the `surface` trigger, so Android 12+ background FGS rules see a real user interaction.
 * Pinning goes through [ShortcutManager.requestPinShortcut] (API 26+; minSdk is 29).
 */
object Shortcuts {

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_PROFILE_ID = "profile_id"
    const val EXTRA_STAGE = "stage"
    const val EXTRA_SOURCE = "source"

    fun runIntent(context: Context, taskId: String, profileId: String? = null, stage: String? = null, source: String = "shortcut"): Intent =
        Intent(context, SurfaceRunActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_STAGE, stage)
            .putExtra(EXTRA_SOURCE, source)

    /** Requests the launcher pin dialog. `true` when the request was accepted (the user still confirms). */
    fun requestPin(context: Context, taskId: String, taskName: String, profileId: String? = null): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(context, shortcutId(taskId, profileId))
            .setShortLabel(taskName.take(20))
            .setLongLabel(taskName)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_autohost))
            .setIntent(runIntent(context, taskId, profileId).setAction(Intent.ACTION_VIEW))
            .build()
        return try {
            manager.requestPinShortcut(shortcut, null)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Keeps a dynamic shortcut per task while the task exists (launcher long-press menu). Called
     * from the task list refresh; max 4 dynamic shortcuts (the launcher's long-press budget).
     */
    fun syncDynamicShortcuts(context: Context, tasks: List<Pair<String, String>>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        try {
            manager.removeAllDynamicShortcuts()
            manager.dynamicShortcuts = tasks.take(MAX_DYNAMIC).map { (taskId, taskName) ->
                ShortcutInfo.Builder(context, shortcutId(taskId, null))
                    .setShortLabel(taskName.take(20))
                    .setLongLabel(taskName)
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_autohost))
                    .setIntent(runIntent(context, taskId).setAction(Intent.ACTION_VIEW))
                    .build()
            }
        } catch (e: IllegalStateException) {
            // rate-limited or launcher refused — pinned shortcuts still work
        }
    }

    fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        },
    )

    fun shortcutId(taskId: String, profileId: String?): String =
        if (profileId.isNullOrEmpty()) "run-$taskId" else "run-$taskId-$profileId"

    private const val MAX_DYNAMIC = 4
}
