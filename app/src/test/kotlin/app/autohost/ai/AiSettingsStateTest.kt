package app.autohost.ai

import app.autohost.core.llm.ProviderProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-06 (S32): the AI settings state — the master switch gates on the disclosure, profiles CRUD
 * refuses the reserved scope, the key is presence-only (never stored on the state), and the
 * screenshot / keep-transcripts / loopback switches default OFF.
 */
class AiSettingsStateTest {

    private class FakeKeys : AiSettingsState.KeyStore {
        val values = mutableMapOf<String, String>()
        override suspend fun put(profileId: String, value: String) {
            values[profileId] = value
        }

        override suspend fun delete(profileId: String) {
            values.remove(profileId)
        }

        override suspend fun has(profileId: String): Boolean = profileId in values
    }

    private fun tester(message: String = "reachable in 5 ms", ok: Boolean = true) =
        AiSettingsState.ConnectionTester { AiSettingsState.ConnectionTester.Result(ok, message) }

    private fun profile(id: String = "p1") = ProviderProfile(
        id = id, label = "P1", baseUrl = "https://api.example.com/v1", model = "m1",
    )

    private fun hydrated(keys: FakeKeys = FakeKeys(), tester: AiSettingsState.ConnectionTester = this.tester()): AiSettingsState =
        AiSettingsState(keys, tester).apply {
            hydrate(
                enabled = false,
                profiles = listOf(profile()),
                keepTranscripts = false,
                cleartextLoopback = false,
                screenshots = emptyMap(),
                keyPresence = emptyMap(),
            )
        }

    @Test
    fun `given the master switch off when turned on then the disclosure is PENDING and nothing is enabled`() {
        val s = hydrated()
        s.setEnabled(true)
        assertTrue(s.model.disclosurePending)
        assertFalse(s.model.enabled)
    }

    @Test
    fun `given the disclosure accepted when accepted then enabled and the profile is stamped (§3 egressAcceptedAt)`() {
        val s = hydrated()
        s.setEnabled(true)
        s.acceptDisclosure(1_234_567L)
        assertTrue(s.model.enabled)
        assertFalse(s.model.disclosurePending)
        assertEquals(1_234_567L, s.model.profiles[0].egressAcceptedAtMs)
        // toggling off and on again is instant — the disclosure was already given once
        s.setEnabled(false)
        s.setEnabled(true)
        assertTrue(s.model.enabled)
        assertFalse(s.model.disclosurePending)
    }

    @Test
    fun `given the disclosure refused when declined then nothing turns on`() {
        val s = hydrated()
        s.setEnabled(true)
        s.declineDisclosure()
        assertFalse(s.model.enabled)
        assertFalse(s.model.disclosurePending)
    }

    @Test
    fun `given the disclosure accepted when a NEW profile is added then it inherits the acceptance`() {
        val s = hydrated()
        s.setEnabled(true)
        s.acceptDisclosure(1L)
        s.upsertProfile(profile(id = "p2"))
        assertEquals(1L, s.model.profiles.first { it.id == "p2" }.egressAcceptedAtMs)
    }

    @Test
    fun `given no acceptance yet when a profile is added then egress is null (cannot run)`() {
        val s = hydrated()
        s.upsertProfile(profile(id = "p2"))
        assertNull(s.model.profiles.first { it.id == "p2" }.egressAcceptedAtMs)
    }

    @Test
    fun `given the reserved host scope when upserted then refused (users cannot create __host__)`() {
        val s = hydrated()
        var threw = false
        try {
            s.upsertProfile(profile(id = ProviderProfile.HOST_SCOPE))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `given a key saved when inspected then only PRESENCE is recorded — the value never touches the state`() = runBlocking {
        val keys = FakeKeys()
        val s = hydrated(keys)
        s.saveKey("p1", "sk-super-secret-value")
        assertTrue(s.model.keyPresent["p1"] == true)
        assertEquals("sk-super-secret-value", keys.values["p1"]) // in the KEY STORE, not the state
        assertTrue(!s.model.toString().contains("sk-super-secret-value"))
        // the canary: the state can never leak what it never held
        assertTrue(!s.model.profiles.toString().contains("sk-super-secret-value"))
    }

    @Test
    fun `given a deleted profile when deleted then the key is deleted with it`() = runBlocking {
        val keys = FakeKeys()
        val s = hydrated(keys)
        s.saveKey("p1", "k1")
        s.deleteProfile("p1")
        assertTrue("p1" !in keys.values)
        assertTrue(s.model.keyPresent["p1"] == null)
        assertTrue(s.model.profiles.none { it.id == "p1" })
    }

    @Test
    fun `given the switches when flipped then screenshots and keep-transcripts and loopback default OFF but settable`() {
        val s = hydrated()
        assertFalse(s.model.keepTranscripts)
        assertFalse(s.model.cleartextLoopback)
        s.setScreenshots("p1", true)
        s.setKeepTranscripts(true)
        s.setCleartextLoopback(true)
        assertTrue(s.model.screenshots["p1"] == true)
        assertTrue(s.model.keepTranscripts)
        assertTrue(s.model.cleartextLoopback)
    }

    @Test
    fun `given test connection when run then only the reachability verdict is stored (no key, no prompt)`() = runBlocking {
        val s = hydrated(tester = tester(message = "reachable in 5 ms"))
        s.testConnection("p1")
        val result = s.model.lastTest["p1"]!!
        assertTrue(result.ok)
        assertEquals("reachable in 5 ms", result.message)
        assertTrue(!s.model.toString().contains("sk-"))
    }

    @Test
    fun `given a failing connection when tested then the mapped reason shows`() = runBlocking {
        val s = hydrated(tester = tester(message = "the provider rejected the key (401/403)", ok = false))
        s.testConnection("p1")
        assertFalse(s.model.lastTest["p1"]!!.ok)
        assertTrue(s.model.lastTest["p1"]!!.message.contains("401"))
    }
}
