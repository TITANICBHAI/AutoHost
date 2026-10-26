package app.autohost.fix

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import app.autohost.core.common.AhLog

/**
 * P8-14: the small explanatory request flow behind runtime-permission and SAF-folder fixes
 * (plan §6.2). Transparent, asks exactly the named permissions (or opens the folder picker),
 * records the outcome for the Setup & Health / preflight re-check, and finishes immediately.
 *
 * Outcomes land in [PermissionOutcomes] (process-wide, refreshed per request) so the health
 * center can show "requested, granted/denied at <time>" without touching the DB hot path.
 */
class PermissionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RUNTIME
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        when (mode) {
            MODE_PICK_FOLDER -> {
                AhLog.i("fix", "SAF folder pick started", null)
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                        ),
                        REQUEST_PICK,
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "folder picker unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            else -> {
                val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray()
                if (permissions.isEmpty()) {
                    finish()
                    return
                }
                AhLog.i("fix", "lazy permission request", null)
                try {
                    requestPermissions(permissions, REQUEST_RUNTIME)
                } catch (e: Exception) {
                    Toast.makeText(this, "permission request failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        taskId?.let { PermissionOutcomes.lastTaskId = it }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = permissions.filterIndexed { index, _ ->
            grantResults.getOrNull(index) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        PermissionOutcomes.record(this, permissions.toList(), granted)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                PermissionOutcomes.recordTree(this, uri.toString())
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PERMISSIONS = "permissions"
        const val EXTRA_TASK_ID = "task_id"
        const val MODE_RUNTIME = "runtime"
        const val MODE_PICK_FOLDER = "pick_folder"
        const val REQUEST_RUNTIME = 4101
        const val REQUEST_PICK = 4102
    }
}

/** Process-wide record of the last lazy-permission outcomes (Setup & Health shows them). */
object PermissionOutcomes {
    @Volatile var lastTaskId: String? = null
    @Volatile var lastGranted: List<String> = emptyList()
    @Volatile var lastDenied: List<String> = emptyList()
    @Volatile var lastTreeUri: String? = null
    @Volatile var lastAtMs: Long = 0

    fun record(context: android.content.Context, requested: List<String>, granted: List<String>) {
        lastGranted = granted
        lastDenied = requested - granted.toSet()
        lastAtMs = System.currentTimeMillis()
        AhLog.i(
            "fix", "permission outcomes",
            kotlinx.serialization.buildJsonObject {
                put("granted", granted.size)
                put("denied", lastDenied.size)
            },
        )
    }

    fun recordTree(context: android.content.Context, uri: String) {
        lastTreeUri = uri
        lastAtMs = System.currentTimeMillis()
        // Persist the picked tree so Permission Review can grant files.shared scope from it.
        runCatching {
            val graph = app.autohost.AutoHostApp.of(context).graph
            kotlinx.coroutines.runBlocking { graph.data.settings.setString("saf.last_tree", uri) }
        }.onFailure {
            AhLog.w("fix", "tree persist failed (app not ready): $it")
        }
    }
}
