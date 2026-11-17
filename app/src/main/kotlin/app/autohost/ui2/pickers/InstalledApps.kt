package app.autohost.ui2.pickers

import android.content.Context
import android.content.Intent

/**
 * The apps that have a launcher icon, as the app picker lists them. The manifest declares `QUERY_ALL_PACKAGES`, so
 * Android 11+ shows every one. An app without a launcher icon is not listed (a known gap, docs/INTEGRATION_V3.md §5 C2).
 * Reads the package manager: call it off the main thread.
 */
object InstalledApps {
    fun launchable(context: Context): List<AppChoice> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return found.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
            if (pkg.isNullOrBlank() || label.isNullOrBlank()) null else AppChoice(pkg, label)
        }
    }
}
