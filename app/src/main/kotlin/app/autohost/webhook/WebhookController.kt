package app.autohost.webhook

import android.content.Context
import app.autohost.capabilities.net.WebhookServer
import app.autohost.core.common.AhLog
import app.autohost.core.common.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-08: app-side webhook controller — owns the [WebhookServer] lifecycle and its settings
 * (Settings → Webhooks, UI_SPEC S23), and dispatches every accepted hook to the matching
 * `external` trigger rule (§11.9) through the trigger runtime.
 *
 * Settings keys (app_settings): `webhook.enabled`, `webhook.port`, `webhook.token`, `webhook.lan`.
 * The token is generated once and shown in Settings; a hook call without it is a 401 (audited).
 */
class WebhookController(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val onHook: (name: String, payload: JsonObject) -> Boolean,
) {
    @Volatile
    private var server: WebhookServer? = null

    private val data get() = app.autohost.AutoHostApp.of(context).graph.data

    fun enabled(): Boolean = runBlocking { data.settings.bool(SETTING_ENABLED) }

    fun port(): Int = runBlocking { data.settings.string(SETTING_PORT)?.toIntOrNull() ?: DEFAULT_PORT }

    fun lan(): Boolean = runBlocking { data.settings.bool(SETTING_LAN) }

    fun token(): String = runBlocking {
        data.settings.string(SETTING_TOKEN) ?: Ids.ulid(System.currentTimeMillis()).also { issued ->
            kotlinx.coroutines.runBlocking { data.settings.setString(SETTING_TOKEN, issued) }
        }
    }

    /** Applies the settings; starts/stops the server so it matches them exactly. */
    fun reconcile(reason: String) {
        appScope.launch {
            try {
                apply(enabled(), port(), token(), lan(), reason)
            } catch (e: Exception) {
                AhLog.w("webhook", "reconcile failed: ${e.message}")
            }
        }
    }

    suspend fun apply(enabled: Boolean, port: Int, token: String, lan: Boolean, reason: String) {
        server?.stop()
        server = null
        if (!enabled) {
            AhLog.i("webhook", "webhook endpoint off ($reason)", null)
            return
        }
        val next = WebhookServer(
            WebhookServer.Policy(enabled = true, port = port, token = token, lan = lan),
        ) { name, payload -> onHook(name, payload) }
        val bound = try {
            next.start()
        } catch (e: Exception) {
            AhLog.w("webhook", "webhook start failed ($reason): ${e.message}")
            null
        }
        if (bound != null) {
            server = next
            AhLog.i(
                "webhook", "webhook endpoint up ($reason)",
                buildJsonObject { put("port", bound); put("lan", lan) },
            )
        }
    }

    fun status(): Triple<Boolean, Int, Boolean> = Triple(server?.isRunning() ?: false, port(), lan())

    companion object {
        const val SETTING_ENABLED = "webhook.enabled"
        const val SETTING_PORT = "webhook.port"
        const val SETTING_TOKEN = "webhook.token"
        const val SETTING_LAN = "webhook.lan"
        const val DEFAULT_PORT = 8082
    }
}
