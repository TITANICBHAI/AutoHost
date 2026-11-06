package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-13 (AGENT_RUNTIME §12): the preflight TABLE — each §12 failure produces its typed reason
 * (+ fix action where one exists) and the happy path passes. The order matters: the first
 * failing §12 row wins.
 */
class AgentPreflightTest {

    private val sensitivity = AppSensitivity(userAdded = mapOf("com.bank.test" to AppTier.BLOCKED))
    private val packages = listOf("com.android.settings")
    private val manifest: TaskManifest = AgentManifests.synthesize(packages)

    /** The grants as GrantBuilder builds them from the synthesized manifest (explicit scopes win). */
    private val scopedGrants: Map<String, JsonObject> = manifest.needs.capabilities.associate {
        it.id to (it.scope ?: buildJsonObject { })
    }

    private val catalog = ToolCatalog.load().names()

    private fun facts(
        aiEnabled: Boolean = true,
        profileReady: Boolean = true,
        urlOk: Boolean = true,
        a11yConnected: Boolean = true,
        screenOk: Boolean = true,
        installedPackages: Set<String> = packages.toSet(),
        uiRunActive: Boolean = false,
        grants: Map<String, JsonObject> = scopedGrants,
        config: AgentConfig = AgentConfig.from(agentObject(packages)),
        manifest: TaskManifest = this.manifest,
    ) = AgentPreflight.Facts(
        config = config,
        manifest = manifest,
        grants = grants,
        sensitivity = sensitivity,
        catalogTools = catalog,
        aiEnabled = aiEnabled,
        profileReady = profileReady,
        urlOk = urlOk,
        a11yConnected = a11yConnected,
        screenOk = screenOk,
        installedPackages = installedPackages,
        uiRunActive = uiRunActive,
    )

    private fun agentObject(pkgs: List<String>, extra: String = ""): JsonObject =
        Json.parseToJsonElement(
            """{"agent":{"goal":"open settings","packages":[${pkgs.joinToString(",") { "\"$it\"" }}]$extra}}""",
        ) as JsonObject

    private fun verdict(f: AgentPreflight.Facts): Pair<String?, String?> {
        val result = AgentPreflight(f).check()
        result as AhResult.Err
        return result.error.reason to result.error.fixAction
    }

    private fun ok(f: AgentPreflight.Facts) {
        val result = AgentPreflight(f).check()
        assertTrue("expected Ok, got ${result.errorOrNull()}", result is AhResult.Ok)
    }

    // --- each §12 row fails with its own reason + fix action ---------------------------------

    @Test
    fun `AI disabled → AI_DISABLED with OPEN_AI_SETTINGS`() {
        val (reason, fix) = verdict(facts(aiEnabled = false))
        assertEquals("AI_DISABLED", reason)
        assertEquals("OPEN_AI_SETTINGS", fix)
    }

    @Test
    fun `no profile or key → AI_NOT_CONFIGURED with OPEN_AI_SETTINGS`() {
        val (reason, fix) = verdict(facts(profileReady = false))
        assertEquals("AI_NOT_CONFIGURED", reason)
        assertEquals("OPEN_AI_SETTINGS", fix)
    }

    @Test
    fun `rejected URL → AI_NOT_CONFIGURED with OPEN_AI_SETTINGS`() {
        val (reason, fix) = verdict(facts(urlOk = false))
        assertEquals("AI_NOT_CONFIGURED", reason)
        assertEquals("OPEN_AI_SETTINGS", fix)
    }

    @Test
    fun `a11y disconnected → A11Y_SERVICE_DISABLED with OPEN_ACCESSIBILITY_SETTINGS`() {
        val (reason, fix) = verdict(facts(a11yConnected = false))
        assertEquals("A11Y_SERVICE_DISABLED", reason)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", fix)
    }

    @Test
    fun `locked screen → SCREEN_LOCKED`() {
        val (reason, fix) = verdict(facts(screenOk = false))
        assertEquals("SCREEN_LOCKED", reason)
        assertNull(fix)
    }

    @Test
    fun `target not installed → APP_NOT_INSTALLED`() {
        val (reason, fix) = verdict(facts(installedPackages = emptySet()))
        assertEquals("APP_NOT_INSTALLED", reason)
        assertNull(fix)
    }

    @Test
    fun `target BLOCKED → OBSERVATION_BLOCKED`() {
        val (reason, fix) = verdict(facts(installedPackages = setOf("com.bank.test"), config = AgentConfig.from(agentObject(listOf("com.bank.test")))))
        assertEquals("OBSERVATION_BLOCKED", reason)
        assertNull(fix)
    }

    @Test
    fun `an unrestricted device grant → AI_GRANTS_TOO_BROAD with OPEN_PERMISSION_REVIEW`() {
        val broad = scopedGrants + mapOf("device.ui.act" to buildJsonObject { })
        val (reason, fix) = verdict(facts(grants = broad))
        assertEquals("AI_GRANTS_TOO_BROAD", reason)
        assertEquals("OPEN_PERMISSION_REVIEW", fix)
    }

    @Test
    fun `a missing device grant → AI_GRANTS_TOO_BROAD (the §5_2 (2) refusal)`() {
        val (reason, _) = verdict(facts(grants = scopedGrants - "device.app.query"))
        assertEquals("AI_GRANTS_TOO_BROAD", reason)
    }

    @Test
    fun `a star-scoped grant → AI_GRANTS_TOO_BROAD (the literal * is unrestricted, §5_1)`() {
        val star = scopedGrants + mapOf("device.ui.observe" to Json.parseToJsonElement("""{"packages":"*"}""") as JsonObject)
        val (reason, _) = verdict(facts(grants = star))
        assertEquals("AI_GRANTS_TOO_BROAD", reason)
    }

    @Test
    fun `an empty packages array IS package-scoped (the empty set — useless but not too broad)`() {
        val empty = scopedGrants + mapOf("device.ui.observe" to Json.parseToJsonElement("""{"packages":[]}""") as JsonObject)
        ok(facts(grants = empty))
    }

    @Test
    fun `a zero budget → BUDGET_INVALID (a run with no effective budget refuses to start)`() {
        val zeroed = AgentConfig.from(agentObject(packages, ""","budgets":{"max_turns":0}"""))
        val (reason, fix) = verdict(facts(config = zeroed))
        assertEquals("BUDGET_INVALID", reason)
        assertNull(fix)
    }

    @Test
    fun `a blank goal → INVALID_ARGS`() {
        val blank = AgentConfig.from(Json.parseToJsonElement("""{"agent":{"goal":"  ","packages":["com.android.settings"]}}""") as JsonObject)
        val (reason, _) = verdict(facts(config = blank))
        assertNull(reason)
        // INVALID_ARGS carries no reasonCode — the typed verdict is the broker code itself
    }

    @Test
    fun `no packages → INVALID_ARGS (targets are explicit, ≥ 1)`() {
        val none = AgentConfig.from(agentObject(emptyList()))
        val (reason, _) = verdict(facts(config = none))
        assertNull(reason)
    }

    @Test
    fun `unsupervised → INVALID_ARGS (Stage A is always supervised)`() {
        val unsupervised = AgentConfig.from(
            Json.parseToJsonElement(
                """{"agent":{"goal":"x","packages":["com.android.settings"],"supervised":false}}""",
            ) as JsonObject,
        )
        val (reason, _) = verdict(facts(config = unsupervised))
        assertNull(reason)
    }

    @Test
    fun `a tool outside the catalog → TOOL_NOT_ALLOWED (narrowing only, §4_2)`() {
        val narrowed = AgentConfig.from(
            Json.parseToJsonElement(
                """{"agent":{"goal":"x","packages":["com.android.settings"],"tools":["ui_click","shell_run"]}}""",
            ) as JsonObject,
        )
        val (reason, _) = verdict(facts(config = narrowed))
        assertEquals("TOOL_NOT_ALLOWED", reason)
    }

    @Test
    fun `another UI run active → UI_RUN_ACTIVE`() {
        val (reason, _) = verdict(facts(uiRunActive = true))
        assertEquals("UI_RUN_ACTIVE", reason)
    }

    // --- the happy path ------------------------------------------------------------------------

    @Test
    fun `given every §12 row satisfied when checked then the run may start`() {
        ok(facts())
    }

    @Test
    fun `given the first row fails when checked then AI_DISABLED wins (the §12 order)`() {
        // a11y AND ai disabled: the §12 order puts the AI stack first
        val (reason, _) = verdict(facts(aiEnabled = false, a11yConnected = false))
        assertEquals("AI_DISABLED", reason)
    }

    // --- the scope semantics (the §5_1 CallContext rule, replicated exactly) -------------------

    @Test
    fun `isPackageScoped semantics`() {
        assertTrue(AgentPreflight.isPackageScoped(Json.parseToJsonElement("""{"packages":["a"]}""") as JsonObject))
        assertTrue(AgentPreflight.isPackageScoped(Json.parseToJsonElement("""{"packages":[]}""") as JsonObject))
        assertTrue(AgentPreflight.isPackageScoped(Json.parseToJsonElement("""{"packages":"a"}""") as JsonObject))
        assertTrue(!AgentPreflight.isPackageScoped(Json.parseToJsonElement("""{"packages":"*"}""") as JsonObject))
        assertTrue(!AgentPreflight.isPackageScoped(buildJsonObject { })) // `{}` is unrestricted (§5_1)
        assertTrue(!AgentPreflight.isPackageScoped(null)) // no grant at all
    }
}
