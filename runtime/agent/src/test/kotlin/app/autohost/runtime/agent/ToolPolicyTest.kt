package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-11 (AGENT_RUNTIME §6 policy column, §10; RM-B2): the policy table — the RM-G2 port of
 * ClosePaw's `PolicyEngineTest` rewritten to AutoHost types (the approval MODES are gone; the
 * structural rules survive: BLOCKED denies before anything asks, escape allows, non-screen-
 * changing allows, the stricter of current vs destination wins, the session list skips only the
 * sensitivity ask, unknown is CAUTIOUS).
 */
class ToolPolicyTest {

    private val catalog = ToolCatalog.load()

    // test tiers ride the userAdded seam — distinct names so no bundled entry collides
    private val sensitivity = AppSensitivity(
        userAdded = mapOf(
            "com.normal.test" to AppTier.NORMAL,
            "com.bank.test" to AppTier.BLOCKED,
        ),
    )
    private val packages = setOf("com.normal.test", "com.bank.test", "com.unknown.test")

    private fun ctx(packages: Set<String> = this.packages) = ToolPolicy.Context(packages, sensitivity)

    private fun args(json: String = "{}"): JsonObject? = Json.parseToJsonElement(json) as JsonObject

    private fun input(
        toolName: String,
        argsJson: String? = "{}",
        targetPkg: String? = null,
        foregroundPkg: String? = null,
        nodeLabel: String? = null,
        nodeIsPassword: Boolean = false,
        sessionAllowed: Set<String> = emptySet(),
    ) = ToolPolicy.Input(
        tool = catalog.find(toolName)!!,
        args = argsJson?.let { Json.parseToJsonElement(it) as JsonObject },
        targetPkg = targetPkg,
        foregroundPkg = foregroundPkg,
        nodeLabel = nodeLabel,
        nodeIsPassword = nodeIsPassword,
        sessionAllowed = sessionAllowed,
    )

    // --- BLOCKED (the upstream port: denied before anything asks, in every mode — modes are gone) ---

    @Test
    fun `given a blocked foreground app when a click is checked then denied (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.bank.test", foregroundPkg = "com.bank.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("OBSERVATION_BLOCKED", (decision as ToolPolicy.Decision.Deny).reason)
    }

    @Test
    fun `given a blocked app when the escape back is checked then allowed (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("ui_press", argsJson = """{"key":"back"}""", foregroundPkg = "com.bank.test"),
            ctx(),
        )
        assertEquals(ToolPolicy.Decision.Allow, decision)
    }

    @Test
    fun `given a press that is not back when checked then denied (only the escape allows, the upstream port)`() {
        // the catalog enum only offers back; a smuggled key is still refused (defense in depth)
        val decision = ToolPolicy.check(
            input("ui_press", argsJson = """{"key":"home"}""", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("INVALID_ARGS", (decision as ToolPolicy.Decision.Deny).code)
    }

    @Test
    fun `given a click labelled back when checked on a blocked app then denied (only ui_press is escape, the upstream port)`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.bank.test", nodeLabel = "back", foregroundPkg = "com.bank.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
    }

    // --- CAUTIOUS / NORMAL (the upstream port, modes collapsed) ---

    @Test
    fun `given an unknown app when a click is checked then asks (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.unknown.test", foregroundPkg = "com.unknown.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Ask)
        assertEquals("unknown app — action requires approval", (decision as ToolPolicy.Decision.Ask).reason)
    }

    @Test
    fun `given a normal app when a click is checked then allowed (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.normal.test", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertEquals(ToolPolicy.Decision.Allow, decision)
    }

    @Test
    fun `given the session allow-list when a cautious click is checked then allowed (the upstream port)`() {
        val decision = ToolPolicy.check(
            input(
                "ui_click", targetPkg = "com.unknown.test", foregroundPkg = "com.unknown.test",
                sessionAllowed = setOf("com.unknown.test"),
            ),
            ctx(),
        )
        assertEquals(ToolPolicy.Decision.Allow, decision)
    }

    @Test
    fun `given a null foreground when a click is checked then asks (unknown is CAUTIOUS, the upstream port)`() {
        val decision = ToolPolicy.check(input("ui_click"), ctx())
        assertTrue(decision is ToolPolicy.Decision.Ask)
    }

    // --- Destination tier (the upstream port: the stricter of current vs destination) ---

    @Test
    fun `given a normal current and a cautious destination when checked then asks (the upstream port)`() {
        // app_launch is non-act in AutoHost (§6: allow for p in P) — the destination rule shows
        // on the click family, whose target resolves inside the destination app after launch
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.unknown.test", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Ask)
    }

    @Test
    fun `given a blocked destination when launch is checked then denied (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("app_launch", argsJson = """{"package":"com.bank.test"}""", targetPkg = "com.bank.test", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("OBSERVATION_BLOCKED", (decision as ToolPolicy.Decision.Deny).reason)
    }

    @Test
    fun `given a null destination when launch is checked then allowed for a packaged app (the upstream port)`() {
        val decision = ToolPolicy.check(
            input("app_launch", argsJson = """{"package":"com.normal.test"}""", targetPkg = "com.normal.test", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertEquals(ToolPolicy.Decision.Allow, decision)
    }

    // --- Non-screen-changing (the upstream port) ---

    @Test
    fun `given host tools when checked then allowed regardless of tier (the upstream port)`() {
        assertEquals(ToolPolicy.Decision.Allow, ToolPolicy.check(input("todo_write", foregroundPkg = "com.bank.test"), ctx()))
        assertEquals(ToolPolicy.Decision.Allow, ToolPolicy.check(input("wait", foregroundPkg = "com.bank.test"), ctx()))
    }

    @Test
    fun `given observe tools when checked then allowed (the floor already filtered)`() {
        assertEquals(ToolPolicy.Decision.Allow, ToolPolicy.check(input("ui_snapshot", foregroundPkg = "com.normal.test"), ctx()))
        assertEquals(ToolPolicy.Decision.Allow, ToolPolicy.check(input("ui_find", foregroundPkg = "com.normal.test"), ctx()))
    }

    // --- Scope and password (AutoHost's §5.2 (4) defence and §6's set_text rule) ---

    @Test
    fun `given a target outside the run packages when checked then denied CAPABILITY_NOT_GRANTED`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.other.app", foregroundPkg = "com.normal.test"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("CAPABILITY_NOT_GRANTED", (decision as ToolPolicy.Decision.Deny).reason)
    }

    @Test
    fun `given set_text on a password field when checked then denied PASSWORD_FIELD (never type secrets)`() {
        val decision = ToolPolicy.check(
            input(
                "ui_set_text", argsJson = """{"id":"[1]","text":"x"}""",
                targetPkg = "com.normal.test", foregroundPkg = "com.normal.test", nodeIsPassword = true,
            ),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("PASSWORD_FIELD", (decision as ToolPolicy.Decision.Deny).reason)
    }

    @Test
    fun `given malformed arguments when checked then denied before anything else (fail closed)`() {
        val decision = ToolPolicy.check(input("ui_click", argsJson = null), ctx())
        assertTrue(decision is ToolPolicy.Decision.Deny)
        assertEquals("INVALID_ARGS", (decision as ToolPolicy.Decision.Deny).code)
    }

    // --- Label hints (best-effort, ask-only, never the boundary, never skipped by the session list) ---

    @Test
    fun `given a risky label in a normal app when a click is checked then asks (the hint rule)`() {
        val decision = ToolPolicy.check(
            input("ui_click", targetPkg = "com.normal.test", foregroundPkg = "com.normal.test", nodeLabel = "Pay now"),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Ask)
        assertTrue((decision as ToolPolicy.Decision.Ask).reason.contains("Pay now"))
    }

    @Test
    fun `given a risky label when the package is session-allowed then STILL asks (hints are never skipped)`() {
        val decision = ToolPolicy.check(
            input(
                "ui_click", targetPkg = "com.normal.test", foregroundPkg = "com.normal.test",
                nodeLabel = "Delete account", sessionAllowed = setOf("com.normal.test"),
            ),
            ctx(),
        )
        assertTrue(decision is ToolPolicy.Decision.Ask)
    }

    @Test
    fun `given a harmless label that merely contains a hint as a substring when checked then no ask (word matching)`() {
        assertEquals(false, ToolPolicy.labelHintHit("border radius"))
        assertEquals(true, ToolPolicy.labelHintHit("payments"))
        assertEquals(false, ToolPolicy.labelHintHit(null))
    }

    @Test
    fun `given act tools outside the click family when checked then allowed without asking (§6 policy column)`() {
        assertEquals(
            ToolPolicy.Decision.Allow,
            ToolPolicy.check(input("ui_scroll_until", argsJson = """{"selector":"x","direction":"up"}""", foregroundPkg = "com.unknown.test"), ctx()),
        )
        assertEquals(
            ToolPolicy.Decision.Allow,
            ToolPolicy.check(input("ui_hide_keyboard", foregroundPkg = "com.unknown.test"), ctx()),
        )
    }
}
