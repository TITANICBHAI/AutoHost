package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-12 (AGENT_RUNTIME §4.1): the synthesized manifest asserts EVERY §4.1 field. */
class AgentManifestsTest {

    private val manifest = AgentManifests.synthesize(listOf("com.android.settings"), maxWallClockS = 600)

    @Test
    fun `identity fields per §4_1`() {
        assertEquals(1, manifest.schema)
        assertEquals("builtin.agent.adhoc", manifest.id)
        assertEquals("Ask", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals("agent", manifest.runtime.type)
        assertEquals("agent", manifest.runtime.entry) // required but unused
    }

    @Test
    fun `needs carry the four package-scoped capabilities plus ui prompt`() {
        val ids = manifest.needs.capabilities.map { it.id }
        assertTrue(
            ids.containsAll(
                listOf("device.ui.observe", "device.ui.act", "device.app.query", "device.app.launch", "ui.prompt"),
            ),
        )
        for (need in manifest.needs.capabilities) {
            if (need.id == "ui.prompt") {
                assertEquals(0, need.scope!!.size) // `{}` per §4.1
            } else {
                val pkgs = (need.scope!!["packages"] as kotlinx.serialization.json.JsonArray)
                    .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                assertEquals(listOf("com.android.settings"), pkgs)
            }
        }
        assertTrue(manifest.needs.optional.isEmpty())
        assertEquals(listOf("com.android.settings"), manifest.needs.apps.map { it.packageName })
    }

    @Test
    fun `execution fields per §4_1 (uiExclusive, unlocked, pausable, ceil timeout, idle 180, no autoResume)`() {
        val e = manifest.execution
        assertTrue(e.uiExclusive)
        assertTrue(e.needsUnlockedScreen)
        assertTrue(e.pausable)
        assertEquals(10, e.timeoutMinutes) // ceil(600 / 60)
        assertEquals(AgentManifests.IDLE_TIMEOUT_SECONDS, e.idleTimeoutSeconds)
        assertEquals(180, e.idleTimeoutSeconds)
        assertEquals(1, e.maxAttempts)
        assertFalse(e.autoResume) // an interrupted agent run is NEVER silently re-run
        assertNull(e.minIntervalMinutes)
    }

    @Test
    fun `timeout rounds UP for a wall clock that is not a whole minute`() {
        val m = AgentManifests.synthesize(listOf("com.x"), maxWallClockS = 601)
        assertEquals(11, m.execution.timeoutMinutes)
        assertEquals(1L, AgentManifests.ceilDiv(1, 60))
        assertEquals(1L, AgentManifests.ceilDiv(60, 60))
        assertEquals(2L, AgentManifests.ceilDiv(61, 60))
    }

    @Test
    fun `the remaining manifest fields are null or empty per §4_1`() {
        assertNull(manifest.ui)
        assertNull(manifest.configSchema)
        assertNull(manifest.profileSchema)
        assertTrue(manifest.usesPacks.isEmpty())
        assertEquals("", manifest.description)
    }

    @Test
    fun `budget defaults map onto the loop limits one to one`() {
        val limits = AgentManifests.DEFAULT_BUDGETS.toLimits()
        assertEquals(30, limits.maxTurns)
        assertEquals(600_000L, limits.wallClockMs)
        assertEquals(40, limits.maxModelCalls)
        assertEquals(200_000L, limits.tokenBudget)
        assertEquals(60, limits.maxActions)
        assertEquals(1_024, limits.maxOutputTokens)
    }

    @Test
    fun `config agent parses goal, packages, provider and budgets with §4_2 defaults`() {
        val config: JsonObject = buildJsonObject {
            put(
                "agent",
                buildJsonObject {
                    put("goal", "Open Settings")
                    put("packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("com.android.settings"))))
                    put("provider", "p1")
                    put("model", "m1")
                    put("budgets", buildJsonObject { put("max_turns", 5); put("max_wall_clock_s", 120) })
                },
            )
        }
        val agent = AgentConfig.from(config)
        assertEquals("Open Settings", agent.goal)
        assertEquals(listOf("com.android.settings"), agent.packages)
        assertEquals("p1", agent.provider)
        assertEquals("m1", agent.model)
        assertTrue(agent.supervised) // Stage A: always true
        assertEquals(5, agent.budgets.maxTurns)
        assertEquals(120L, agent.budgets.maxWallClockS)
        assertEquals(40, agent.budgets.maxModelCalls) // the tightened keys default to the ceilings
        assertEquals(120, agent.confirmTimeoutS)
    }

    @Test
    fun `a secret pasted into config agent never travels (the X-12 boundary - the parse drops unknown keys)`() {
        val config: JsonObject = buildJsonObject {
            put("agent", buildJsonObject {
                put("goal", "x")
                put("packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("com.android.settings"))))
                put("api_key", "sk-canary-67890") // someone pasted a key into the run config
                put("password", "hunter2")
            })
        }
        val agent = AgentConfig.from(config)
        // only the KNOWN keys parse; nothing the runtime builds (the parsed config, the
        // synthesized manifest) ever carries the pasted material
        assertTrue(!agent.toString().contains("sk-canary-67890"))
        assertTrue(!agent.toString().contains("hunter2"))
        val manifest = AgentManifests.synthesize(agent.packages, agent.budgets.maxWallClockS)
        assertTrue(!manifest.toString().contains("sk-canary-67890"))
        assertTrue(!manifest.toString().contains("hunter2"))
    }

    @Test
    fun `a config without the agent key parses to an empty goal (the provider then refuses to start)`() {
        val agent = AgentConfig.from(Json.parseToJsonElement("{}") as JsonObject)
        assertEquals("", agent.goal)
        assertTrue(agent.packages.isEmpty())
        assertTrue(agent.supervised)
    }
}
