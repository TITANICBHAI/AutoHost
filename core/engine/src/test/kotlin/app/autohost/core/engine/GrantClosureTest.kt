package app.autohost.core.engine

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.engine.tasks.GrantBuilder
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.Needs
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-15 (ADR-098): grant closure — with dev mode OFF (the default), a run holds ONLY its
 * declared needs + the implicit host grants + the persisted user-approved overlay. No
 * grant-all, no shell/raw/sms leakage.
 */
class GrantClosureTest {

    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"device.shell","permissions":[{"id":"device.shell"}]},
          {"id":"device.sms","permissions":[{"id":"device.sms.send"}]},
          {"id":"store","permissions":[{"id":"store.profile"},{"id":"store.task"}]}
        ]}
        """.trimIndent(),
    )

    private fun manifest() = TaskManifest(
        id = "app.autohost.sample.press-button",
        name = "Press Button",
        runtime = RuntimeSpec(type = "python", entry = "main.py"),
        needs = Needs(
            capabilities = listOf(
                CapabilityNeed(
                    "device.ui.act",
                    buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("app.autohost.fixture")))) },
                ),
            ),
        ),
    )

    @Test
    fun `given dev mode off when built then the run holds ONLY declared and implicit permissions`() {
        val grants = GrantBuilder(index, devMode = false).build(manifest(), JsonObject(emptyMap()), JsonObject(emptyMap()))
        val expected = setOf(
            "device.ui.act", // declared
            "store.profile", "store.task", "state.checkpoint", "secrets.read", "system.info", // implicit
        )
        assertEquals(expected, grants.keys)
        assertFalse("shell must not leak", "device.shell" in grants)
        assertFalse("sms must not leak", "device.sms.send" in grants)
    }

    @Test
    fun `given dev mode defaults when constructed then it is OFF`() {
        // the default constructor is the closed model — reflection-free pin of the parameter default
        val grants = GrantBuilder(index).build(manifest(), JsonObject(emptyMap()), JsonObject(emptyMap()))
        assertFalse("dev-mode grant-all must be opt-in", "device.shell" in grants)
    }

    @Test
    fun `given the approved overlay when dev mode is off then approved permissions apply without widening declared scopes`() {
        val approved = mapOf(
            "device.ui.observe" to buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("app.autohost.fixture")))) },
            "device.shell" to buildJsonObject { }, // an approved shell grant applies — but only if persisted-approved
        )
        val grants = GrantBuilder(index, devMode = false, approvedGrants = approved).build(
            manifest(), JsonObject(emptyMap()), JsonObject(emptyMap()),
        )
        assertTrue("approved observe grant applies", "device.ui.observe" in grants)
        assertTrue("approved shell grant applies (it was user-approved)", "device.shell" in grants)
        // the declared scope is never narrowed by the overlay (putIfAbsent)
        assertEquals(
            "app.autohost.fixture",
            ((grants["device.ui.act"]!!["packages"] as JsonArray)[0] as JsonPrimitive).content,
        )
    }

    @Test
    fun `given dev mode on when built then grant-all still works (developer opt-in)`() {
        val grants = GrantBuilder(index, devMode = true).build(manifest(), JsonObject(emptyMap()), JsonObject(emptyMap()))
        assertTrue("dev opt-in keeps its legacy behavior", "device.shell" in grants)
        assertTrue("device.sms.send" in grants)
    }

    @Test
    fun `given declared scopes when built then they survive the overlay (never widened by an empty approved scope)`() {
        val approved = mapOf("device.ui.act" to JsonObject(emptyMap())) // an empty approved scope = unrestricted
        val grants = GrantBuilder(index, devMode = false, approvedGrants = approved).build(
            manifest(), JsonObject(emptyMap()), JsonObject(emptyMap()),
        )
        // declared needs win (putIfAbsent): the overlay must not widen an explicitly scoped grant
        assertEquals(
            "app.autohost.fixture",
            ((grants["device.ui.act"]!!["packages"] as JsonArray)[0] as JsonPrimitive).content,
        )
    }
}

