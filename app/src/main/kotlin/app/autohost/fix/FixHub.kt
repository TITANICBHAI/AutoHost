package app.autohost.fix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * P8-14: the one place a fix-action string becomes an Intent (plan §6.2 — "the Fix button
 * launches a small explanatory request flow"). Every provider/health reason maps to exactly one
 * action; unknown actions are reported back instead of guessed (fail closed).
 *
 * Actions that open an app screen return a route name for the in-app navigator; the rest build
 * system Intents. `FixHub.launch` never throws — an unavailable special-access screen returns
 * false and the UI keeps the issue visible (honest).
 */
object FixHub {

    /** In-app routes (navigator names) — everything else is a system screen. */
    val IN_APP_ROUTES = setOf("OPEN_INSECURE_SWITCH", "OPEN_PERMISSION_REVIEW", "OPEN_DEV_API_SETTINGS", "OPEN_SURFACES", "OPEN_SHELL_SETUP")

    /** Build the launch Intent for a fix action; null = unrouteable (caller shows the reason text). */
    fun intent(context: Context, action: String, taskId: String? = null): Intent? = when (action) {
        "OPEN_ACCESSIBILITY_SETTINGS" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "OPEN_NOTIFICATION_LISTENER_SETTINGS" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "OPEN_WRITE_SETTINGS" -> Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        "OPEN_DO_NOT_DISTURB_ACCESS" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "OPEN_EXACT_ALARM", "REQUEST_EXACT_ALARM" ->
            if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            } else null // API 29/30: exact alarms need no special access; the issue cannot exist
        "BATTERY_EXEMPTION" -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        "SELECT_IME" -> null // handled via InputMethodManager.showInputMethodPicker (needs an activity window)
        "OPEN_STORE" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://details/?id=$taskId"))
        "OPEN_DEVELOPER_SETTINGS" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        "PICK_FOLDER" -> Intent(context, PermissionRequestActivity::class.java).apply {
            putExtra(PermissionRequestActivity.EXTRA_MODE, PermissionRequestActivity.MODE_PICK_FOLDER)
            taskId?.let { putExtra(PermissionRequestActivity.EXTRA_TASK_ID, it) }
        }
        else ->
            if (action.startsWith("REQUEST_RUNTIME_PERMISSION")) {
                val permission = action.substringAfter(':')
                if (permission.isBlank()) {
                    null
                } else {
                    Intent(context, PermissionRequestActivity::class.java).apply {
                        putExtra(PermissionRequestActivity.EXTRA_MODE, PermissionRequestActivity.MODE_RUNTIME)
                        putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS, arrayOf(permission))
                        taskId?.let { putExtra(PermissionRequestActivity.EXTRA_TASK_ID, it) }
                    }
                }
            } else {
                null
            }
    }

    /**
     * Launches a fix from an activity. Returns false when the action has no Intent on this API
     * level — the UI then keeps the row visible with the reason (honest, no dead button).
     */
    fun launch(activity: Activity, action: String, taskId: String? = null, requestCode: Int = 4100): Boolean {
        if (action in IN_APP_ROUTES) return true // navigator handles it; nothing to launch here
        if (action == "SELECT_IME") {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showInputMethodPicker()
            return true
        }
        val intent = intent(activity, action, taskId) ?: return false
        return try {
            activity.startActivityForResult(intent, requestCode)
            true
        } catch (e: Exception) {
            false
        }
    }
}
