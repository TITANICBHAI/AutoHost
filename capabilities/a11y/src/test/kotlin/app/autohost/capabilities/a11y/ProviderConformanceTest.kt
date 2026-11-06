package app.autohost.capabilities.a11y

import app.autohost.core.broker.ReturnShape
import app.autohost.core.common.FakeClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-01 (ADR-089): the provider can never return a shape the frozen spec does not promise.
 * The harness reads `schemas/capability-specs.w1.json` (its bundled broker resource copy —
 * generated, never hand-edited), parses every `device.ui` `returns` string with [ReturnShape],
 * and runs the provider over a table of calls with the existing fakes. First red case was A6
 * (`click.method` drift: NODE|PARENT|NONE vs the spec's NODE_ACTION|ANCESTOR_ACTION|COORDINATE);
 * the provider now maps at the boundary and a null route is a typed error, not a method value.
 *
 * Keys the later phases add on purpose ride `additiveAllowed` until the regeneration lands (P12-02):
 * `effect`/`warnings` on action results.
 */
class ProviderConformanceTest {

    /** The frozen spec's `device.ui` method → parsed returns shape. */
    private fun deviceUiShapes(): Map<String, Map<String, ReturnShape.FieldSpec>> {
        val text = javaClass.classLoader!!
            .getResourceAsStream("autohost/capability-specs-w1.json")!!
            .readBytes().decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val deviceUi = root["capabilities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == "device.ui" }
        return deviceUi["methods"]!!.jsonArray
            .map { it.jsonObject }
            .mapNotNull { m ->
                val name = m["name"]!!.jsonPrimitive.content
                val returns = m["returns"]?.jsonPrimitive?.content ?: return@mapNotNull null
                name to ReturnShape.parse(returns)
            }
            .toMap()
    }

    /** P12-02 landed: effect/warnings/was_visible/verified are declared — the additive list is EMPTY. */
    private val additive = emptySet<String>()

    private fun ctx() = app.autohost.core.broker.CallContext(
        runId = "run-conf",
        taskId = "task-conf",
        profileId = null,
        grants = mapOf(
            "device.ui.observe" to Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject,
            // P12-16: the flags ride so this table tests RESULT SHAPES; the flag REFUSALS are
            // ScopeClosureTest's subject
            "device.ui.act" to Json.parseToJsonElement(
                """{"packages":["app.autohost.fixture"],"allow_coordinates":true,"allow_global":true}""",
            ).jsonObject,
        ),
        nowMs = 0L,
    )

    private fun newProvider(): Pair<A11yUiProvider, FakeUiActions> {
        val reader = FakeReader(snapshotOf(*conformanceTree().toTypedArray()))
        val actions = FakeUiActions()
        val provider = A11yUiProvider(
            reader = reader,
            registry = NodeRegistry(FakeClock()),
            actions = actions,
            windowChanges = { 999_999L }, // last change 1 ms before the clock start: quiet grows
            // autoAdvance: wait_for_settle genuinely waits — every clock READING advances 1 ms
            // so the settle loop's elapsed grows past quiet_ms despite the fake clock.
            clock = FakeClock(startMs = 1_000_000L, autoAdvancePerReadMs = 1),
            connected = { true },
            pollIntervalMs = 1,
        )
        return provider to actions
    }

    /** A tiny tree that lets every act method find a target. */
    private fun conformanceTree() = listOf(
        node(0, -1, 0, "fx_title", text = "Fixture", cls = "android.widget.TextView"),
        node(1, 0, 1, "fx_btn_continue", text = "Continue", clickable = true),
        node(2, 0, 1, "fx_text", text = "", cls = "android.widget.EditText", editable = true),
    )

    private suspend fun invoke(provider: A11yUiProvider, method: String, args: String): JsonObject? =
        withTimeout(15_000) {
            val envelope = provider.invoke(ctx(), method, Json.parseToJsonElement(args).jsonObject)
            envelope.result as? JsonObject
        }

    private suspend fun invokeEnvelope(provider: A11yUiProvider, method: String, args: String) =
        withTimeout(15_000) {
            provider.invoke(ctx(), method, Json.parseToJsonElement(args).jsonObject)
        }

    private fun assertConformant(
        failures: MutableList<String>,
        shapes: Map<String, Map<String, ReturnShape.FieldSpec>>,
        method: String,
        result: JsonObject?,
    ) {
        if (result == null) {
            failures.add("$method: call produced no result object")
            return
        }
        val shape = shapes[method]
            ?: run { failures.add("$method: no returns declared in the frozen spec"); return }
        if (shape.isEmpty()) return // a top-level named type (e.g. 'UiSnapshot') is wire-opaque here
        val violations = ReturnShape.validate(result, shape, additive)
        if (violations.isNotEmpty()) failures.add("$method: ${violations.joinToString("; ")}")
    }

    @Test
    fun `given the act methods when run against the fakes then results match the frozen spec shapes`() = runBlocking {
        val shapes = deviceUiShapes()
        val (provider, _) = newProvider()

        val table: List<Pair<String, String>> = listOf(
            "click" to """{"selector": ${selectorFor(idTerm("fx_btn_continue"))}}""",
            "long_click" to """{"selector": ${selectorFor(idTerm("fx_btn_continue"))}}""",
            "set_text" to """{"selector": ${selectorFor(idTerm("fx_text"))}, "text": "hello"}""",
            "swipe" to """{"x1": 10, "y1": 10, "x2": 10, "y2": 100}""",
            "tap" to """{"x": 10, "y": 10}""",
            "press" to """{"key": "back"}""",
            "hide_keyboard" to """{}""",
        )

        val failures = ArrayList<String>()
        for ((method, args) in table) {
            try {
                val result = invoke(provider, method, args)
                assertConformant(failures, shapes, method, result)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                failures.add("$method: TIMED OUT after 15 s (hang)")
            }
        }
        assertTrue("provider↔spec drift: " + failures.joinToString(" | "), failures.isEmpty())
        Unit
    }

    @Test
    fun `given the read methods when run against the fakes then results match the frozen spec shapes`() = runBlocking {
        val shapes = deviceUiShapes()
        val (provider, _) = newProvider()

        val table: List<Pair<String, String>> = listOf(
            "snapshot" to """{}""",
            "find" to """{"timeout_ms": 0, "selector": ${selectorFor(idTerm("fx_btn_continue"))}}""",
            // `find` is pinned separately below: its Kotlin shape ({ref,node}, first match)
            // diverges from the spec+sim shape ({nodes:[...]}) — recorded for the P12-02 rework.
            "wait_for" to """{"timeout_ms": 50, "selector": ${selectorFor(idTerm("fx_btn_continue"))}}""",
            "wait_for_settle" to """{"quiet_ms": 10, "timeout_ms": 500}""",
            "is_window_updating" to """{}""",
            "dump" to """{"compact": true}""",
            "keyboard_visible" to """{}""",
        )

        val failures = ArrayList<String>()
        for ((method, args) in table) {
            try {
                val result = invoke(provider, method, args)
                assertConformant(failures, shapes, method, result)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                failures.add("$method: TIMED OUT after 15 s (hang)")
            }
        }
        assertTrue("provider↔spec drift: " + failures.joinToString(" | "), failures.isEmpty())
        Unit
    }


}
