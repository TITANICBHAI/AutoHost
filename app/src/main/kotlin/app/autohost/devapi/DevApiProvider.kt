package app.autohost.devapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.autohost.AutoHostApp

/**
 * P2-21: ContentProvider adapter (docs/DEVAPI.md §2) — the `adb shell content …` bootstrap path.
 * Exposes only non-secret state: `ping`, `auth_token` (for the PC to fetch once, over adb), and
 * `state` (devapi on/off + port). RPC execution goes through the HTTP server, not this provider.
 */
class DevApiProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val graph = (context?.applicationContext as? AutoHostApp)?.graph ?: return null
        val cursor = MatrixCursor(arrayOf("value"))
        when (uri.lastPathSegment) {
            "ping" -> cursor.addRow(arrayOf("""{"status":"ok"}"""))
            "auth_token" -> graph.devApiTokens.currentOrNull()?.let { cursor.addRow(arrayOf(it)) }
            "state" -> cursor.addRow(
                arrayOf("""{"app":"${context!!.packageName}","devapi":${graph.devApiEnabled},"port":8080}"""),
            )
            else -> return null
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
