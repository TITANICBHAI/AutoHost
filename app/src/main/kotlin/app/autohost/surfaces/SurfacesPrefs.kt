package app.autohost.surfaces

import android.content.Context
import android.content.SharedPreferences
import app.autohost.core.engine.surfaces.BubblePolicy
import app.autohost.core.engine.surfaces.TileBindings

/**
 * P7-06..12: the one prefs store for every system surface (bubble mode, tile bindings, the
 * external-trigger token, the volume-key stop, widget task choice). SharedPreferences-backed —
 * these values are read from services/receivers that run while the app has no activity, so the
 * Room/settings store (suspend-only) is the wrong tool; secrets never live here (the external
 * token authorises *starting* runs and nothing else, and rotates from the UI).
 */
class SurfacesPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("surfaces", Context.MODE_PRIVATE)

    // ---- bubble (P7-06) ----

    fun bubbleMode(): String = prefs.getString(KEY_BUBBLE_MODE, BubblePolicy.MODE_AUTO) ?: BubblePolicy.MODE_AUTO

    fun setBubbleMode(mode: String) {
        prefs.edit().putString(KEY_BUBBLE_MODE, if (mode in BubblePolicy.MODES) mode else BubblePolicy.MODE_AUTO).apply()
    }

    // ---- tiles (P7-07) ----

    fun tileBinding(slot: Int): TileBindings.Binding? = TileBindings.parse(slot, prefs.getString(TileBindings.prefKey(slot), null)).binding

    fun setTileBinding(slot: Int, binding: TileBindings.Binding?) {
        val editor = prefs.edit()
        if (binding == null) editor.remove(TileBindings.prefKey(slot)) else editor.putString(TileBindings.prefKey(slot), TileBindings.format(binding))
        editor.apply()
    }

    fun allTileBindings(): Map<Int, TileBindings.Binding?> =
        (1..TileBindings.TILE_COUNT).associateWith { tileBinding(it) }

    // ---- external trigger token (P7-10) ----

    /**
     * The per-install token guarding the exported RUN_TASK intent. Generated on first use;
     * [regenerateToken] invalidates every previously configured automation (documented cost).
     */
    @Synchronized
    fun externalToken(): String {
        prefs.getString(KEY_EXTERNAL_TOKEN, null)?.let { return it }
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_EXTERNAL_TOKEN, token).apply()
        return token
    }

    @Synchronized
    fun regenerateToken(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_EXTERNAL_TOKEN, token).apply()
        return token
    }

    // ---- volume-key stop (P7-12) ----

    fun keyFilterEnabled(): Boolean = prefs.getBoolean(KEY_KEYFILTER_ENABLED, false)

    fun setKeyFilterEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_KEYFILTER_ENABLED, enabled).apply()

    /** `KEYCODE_VOLUME_DOWN` (25) by default; `KEYCODE_VOLUME_UP` (24) is the alternative. */
    fun keyFilterKeyCode(): Int = prefs.getInt(KEY_KEYFILTER_CODE, DEFAULT_KEY_CODE)

    fun setKeyFilterKeyCode(code: Int) = prefs.edit().putInt(KEY_KEYFILTER_CODE, code).apply()

    fun keyFilterHoldMs(): Long {
        val value = prefs.getLong(KEY_KEYFILTER_HOLD, DEFAULT_HOLD_MS)
        return value.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
    }

    fun setKeyFilterHoldMs(holdMs: Long) =
        prefs.edit().putLong(KEY_KEYFILTER_HOLD, holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)).apply()

    /** The a11y service consumes this on every key event; null = the filter is off. */
    fun keyFilterSpec(): app.autohost.capabilities.a11y.A11yBridge.KeyFilterSpec? {
        if (!keyFilterEnabled()) return null
        return app.autohost.capabilities.a11y.A11yBridge.KeyFilterSpec(keyFilterKeyCode(), keyFilterHoldMs())
    }

    // ---- widget (P7-09) ----

    /** Task ids the widget shows; empty = all installed tasks (bounded by the widget renderer). */
    fun widgetTaskIds(): List<String> =
        prefs.getString(KEY_WIDGET_TASKS, null)?.let { json ->
            runCatching {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
                (element as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?.filter { it.isNotBlank() }
            }.getOrNull() ?: emptyList()
        } ?: emptyList()

    fun setWidgetTaskIds(ids: List<String>) {
        val json = kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        prefs.edit().putString(KEY_WIDGET_TASKS, json).apply()
    }

    companion object {
        private const val KEY_BUBBLE_MODE = "bubble.mode"
        private const val KEY_EXTERNAL_TOKEN = "external.token"
        private const val KEY_KEYFILTER_ENABLED = "keyfilter.enabled"
        private const val KEY_KEYFILTER_CODE = "keyfilter.keycode"
        private const val KEY_KEYFILTER_HOLD = "keyfilter.hold_ms"
        private const val KEY_WIDGET_TASKS = "widget.task_ids"

        const val DEFAULT_KEY_CODE = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        const val DEFAULT_HOLD_MS = 1_500L
        const val MIN_HOLD_MS = 600L
        const val MAX_HOLD_MS = 3_000L
    }
}
