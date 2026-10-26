package app.autohost.fixture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.json.JSONObject

/**
 * In-memory + persisted state of the fixture (docs/FIXTURE_APP.md): counters, clicks, last event, text
 * fields. Mirrored into every screen's `fx_state` readout and served by [FixtureStateProvider].
 */
object FixtureState {
    private const val PREFS = "fixture_state"

    val clicks = mutableMapOf<String, Int>()
    var lastEvent: String = ""
    val textFields = mutableMapOf<String, String>()
    var screen: String = "home"

    fun recordClick(id: String) {
        clicks[id] = (clicks[id] ?: 0) + 1
        lastEvent = "click:$id"
    }

    fun recordText(id: String, text: String) {
        textFields[id] = text
        lastEvent = "text:$id"
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("screen", screen)
        json.put("clicks", JSONObject(clicks as Map<*, *>))
        json.put("text_fields", JSONObject(textFields as Map<*, *>))
        json.put("last_event", lastEvent)
        return json
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        screen = prefs.getString("screen", "home") ?: "home"
        lastEvent = prefs.getString("last_event", "") ?: ""
        prefs.getString("clicks", null)?.let { raw ->
            val json = JSONObject(raw)
            for (key in json.keys()) clicks[key] = json.optInt(key, 0)
        }
        prefs.getString("text_fields", null)?.let { raw ->
            val json = JSONObject(raw)
            for (key in json.keys()) textFields[key] = json.optString(key, "")
        }
    }

    fun persist(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("screen", screen)
            .putString("last_event", lastEvent)
            .putString("clicks", JSONObject(clicks as Map<*, *>).toString())
            .putString("text_fields", JSONObject(textFields as Map<*, *>).toString())
            .apply()
    }

    fun reset(context: Context) {
        clicks.clear()
        textFields.clear()
        lastEvent = ""
        screen = "home"
        persist(context)
    }
}

/** Serves the state readout: `content://app.autohost.fixture.state/state` (and `/reset`). */
class FixtureStateProvider : android.content.ContentProvider() {

    override fun onCreate(): Boolean {
        FixtureState.load(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        if (uri.lastPathSegment == "reset") {
            FixtureState.reset(context)
            FixtureState.persist(context)
            return null
        }
        FixtureState.persist(context)
        val json = FixtureState.toJson()
        val matrix = android.database.MatrixCursor(arrayOf("state"))
        matrix.addRow(arrayOf(json.toString()))
        return matrix
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
