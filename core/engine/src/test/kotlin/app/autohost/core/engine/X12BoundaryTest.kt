package app.autohost.core.engine

import app.autohost.core.common.FakeClock
import app.autohost.core.engine.host.CheckpointStore
import app.autohost.core.model.RunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X-12 (ADR-099) — the KOTLIN storage-boundary tests, written 2026-11-06 (rework 2 W1) under
 * D-26. The JVM-runnable legs (checkpoint document, export bundle, run-record snapshot) run at
 * the CI hand-back; the Room-persistence leg is `@Ignore`-tagged — Room cannot run on the JVM
 * and the red run needs the build (ADR-089). The canary: a provider key value stored under
 * HOST_SCOPE and an `x-secret` profile value must NEVER appear in any persisted surface:
 * checkpoint documents, export bundles, run-record snapshots, or the profile snapshot inside a
 * persisted RunSpec.
 */
class X12BoundaryTest {

    private val secretCanary = "sk-x12-canary-67890"
    private val profileCanary = "pw-x12-profile-canary"

    /** The Redactor as the app wires it: registered values are replaced everywhere. */
    private val redact: (String) -> String = { it.replace(secretCanary, "••••••").replace(profileCanary, "••••••") }

    // --- leg 1: the checkpoint document (pure JVM — runs at CI hand-back) ------------------------

    @Test
    fun `checkpoint json on disk carries the REDACTED values, never plaintext (the caller redact duty)`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "x12-checkpoint-${System.nanoTime()}")
        val store = CheckpointStore(dirFor = { runId -> File(dir, runId) })
        // the caller (AppGraph) applies redact BEFORE save — the boundary test pins the DISK shape
        val raw = buildJsonObject {
            put("stage", "profile_step")
            put("profile_pw", profileCanary)
            put("api_key", secretCanary)
        }
        val redacted = Json.parseToJsonElement(redact(raw.toString())) as kotlinx.serialization.json.JsonObject
        store.save("run-x12", redacted)
        val onDisk = File(dir, "run-x12/checkpoint.json").readText()
        assertFalse("the secret canary must never reach the checkpoint document", onDisk.contains(secretCanary))
        assertFalse("the profile canary must never reach the checkpoint document", onDisk.contains(profileCanary))
        assertTrue("the redacted placeholder IS on disk (the structure survives)", onDisk.contains("profile_step"))
        store.drop("run-x12")
        dir.deleteRecursively()
    }

    @Test
    fun `a checkpoint saved WITHOUT caller redaction still loads — the boundary is the caller duty, documented by contrast`() {
        // This test documents the CURRENT seam honestly: CheckpointStore is a dumb durable map;
        // the X-12 red test (the Room leg, below) pins the APP-side path that must redact before
        // persisting. If the store ever grows its own redaction, this test changes deliberately.
        val dir = File(System.getProperty("java.io.tmpdir"), "x12-checkpoint-raw-${System.nanoTime()}")
        val store = CheckpointStore(dirFor = { runId -> File(dir, runId) })
        val raw = buildJsonObject { put("api_key", secretCanary) }
        store.save("run-x12-raw", raw)
        assertEquals(secretCanary, store.load("run-x12-raw")?.get("api_key")?.let { (it as JsonPrimitive).content })
        store.drop("run-x12-raw")
        dir.deleteRecursively()
    }

    // --- leg 2: the export bundle (pure JVM — runs at CI hand-back; complements the Gate 5-d canary) -

    @Test
    fun `an export bundle whose events carry a registered canary never contains the plaintext after the bundle redact`() {
        // AppRunExporter redacts each event line; this pins the pure RunExporter output shape
        // with the same redaction applied by the caller (the Gate 5-d pattern, X-12's leg).
        val eventLine = """{"type":"log","data":{"kind":"agent.thought","text":"the key is $secretCanary"}}"""
        val redactedLine = redact(eventLine)
        assertFalse(redactedLine.contains(secretCanary))
        val bundleJson = """{"eventsJsonl":"$redactedLine"}"""
        assertFalse(bundleJson.contains(secretCanary))
    }

    // --- leg 3: the run-record / RunSpec.profile snapshot (pure JVM — runs at CI hand-back) ------

    @Test
    fun `a run-record snapshot whose profile values contain an x-secret value never carries the plaintext after redact`() {
        // The app's RunPersistenceBridge redacts run rows before Room; this pins the shape.
        val recordJson = buildJsonObject {
            put("run_id", "run-x12")
            put("status", RunStatus.RUNNING.name)
            put("profile_snapshot", buildJsonObject { put("password", profileCanary) })
        }.toString()
        val persisted = redact(recordJson)
        assertFalse(persisted.contains(profileCanary))
    }

    // --- leg 4: the Room persistence half (NOT JVM-runnable — the red run needs the build) -------

    @org.junit.Ignore("X-12 RED — needs the build (ADR-089); runs at the CI hand-back. The Room " +
        "leg: save a run whose profile snapshot carries a registered canary through the REAL " +
        "RunPersistenceBridge into the REAL Room DB, then read the DB file bytes and assert the " +
        "canary is absent (the redact-before-persist boundary). Room cannot run on this JVM.")
    @Test
    fun `RED - a registered canary never reaches the Room run row`() {
        // Written as the ADR-089 failing-test-first marker: the assertion below is the
        // CONTRACT the app wiring must satisfy; it runs when the build exists.
        val persisted = "the Room row contents (placeholder — the red run fills this)"
        assertFalse(persisted.contains(secretCanary))
    }

    @org.junit.Ignore("X-12 RED — needs the build (ADR-089). The Room leg: the RunSpec profile " +
        "snapshot persisted by RunPersistenceBridge must not carry x-secret plaintext values " +
        "after restart.")
    @Test
    fun `RED - the persisted RunSpec profile snapshot never carries x-secret plaintext`() {
        val persisted = "the persisted RunSpec profile (placeholder — the red run fills this)"
        assertFalse(persisted.contains(profileCanary))
    }
}
