package app.autohost.capabilities.a11y

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.autohost.core.broker.CallContext
import app.autohost.core.common.AhJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject

/**
 * Instrumented-test harness (androidTest only): wires the real provider stack against the live
 * accessibility service and exposes small helpers the Gate-3 suites use. The granted scope covers the
 * fixture package; the DeepLinkActivity drives the fixture to the requested screen.
 */
class A11yUiProviderTestHarness(private val context: Context) {

    private val clock = object : app.autohost.core.common.Clock {
        override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
        override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
    }

    private val registry = NodeRegistry(clock)
    private val reader = SimpleA11yReader({ A11yBridge.service.value }, clock)
    private val provider = A11yUiProvider(
        reader = reader,
        registry = registry,
        actions = ServiceUiActions({ A11yBridge.service.value }),
        windowChanges = { A11yBridge.lastWindowChangeMs },
        clock = clock,
        screenWidth = context.resources.displayMetrics.widthPixels,
        screenHeight = context.resources.displayMetrics.heightPixels,
    )

    private val ctx = CallContext(
        runId = "instrumented",
        taskId = "gate3",
        profileId = null,
        grants = mapOf(
            "device.ui.observe" to JsonObject(emptyMap()), // unrestricted in the harness; scoping has its own suite
            "device.ui.act" to JsonObject(emptyMap()),
        ),
        nowMs = 0,
    )

    fun launchDeepLink(screen: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("fixture://screen/$screen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        A11yBridge.awaitConnected(2_000)
        Thread.sleep(600) // let the window settle; suites use wait_for for anything stricter
    }

    private fun call(method: String, args: JsonObject) = runBlocking { provider.invoke(ctx, method, args) }

    fun findRef(id: String): String {
        val envelope = call("find", buildJsonObject { put("selector", idSelector(id)) })
        check(envelope.ok) { "find($id) failed: ${envelope.error}" }
        return (envelope.result!!.jsonObject["ref"] as JsonPrimitive).content
    }

    fun clickSelector(id: String): ClickView {
        val envelope = call("click", buildJsonObject { put("selector", idSelector(id)) })
        check(envelope.ok) { "click($id) failed: ${envelope.error}" }
        val result = envelope.result!!.jsonObject
        return ClickView((result["method"] as JsonPrimitive).content, (result["verified"] as JsonPrimitive).content.toBooleanStrict())
    }

    fun clickRef(ref: String): EnvelopeView {
        val envelope = call("click", buildJsonObject { put("ref", ref) })
        return EnvelopeView(envelope.ok, envelope.error?.code)
    }

    fun setText(selectorId: String, text: String, secret: Boolean = false): TextView {
        val envelope = call("set_text", buildJsonObject {
            put("selector", idSelector(selectorId))
            put("text", text)
            put("secret", secret)
        })
        val result = envelope.result?.jsonObject
        return TextView(
            result?.get("outcome")?.let { (it as JsonPrimitive).content } ?: envelope.error?.reason ?: "?",
            result?.get("strategy")?.let { (it as JsonPrimitive).content } ?: "-",
        )
    }

    fun clearRegistry() = registry.clear()

    private fun idSelector(id: String): JsonObject = AhJson.json.parseToJsonElement(
        """{"terms":[{"id":{"labels":["$id"],"mode":"eq","case_sensitive":true}}]}""",
    ).jsonObject

    data class ClickView(val method: String, val verified: Boolean)
    data class TextView(val outcome: String, val strategy: String)
    data class EnvelopeView(val ok: Boolean, val errorCode: String?)
}
