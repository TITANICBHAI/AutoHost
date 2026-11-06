package app.autohost.ai

import app.autohost.core.llm.ProviderProfile

/**
 * P14-06 (S32, UI_SPEC §10): the AI settings state — pure and synchronous so JVM tests pin
 * every rule:
 *
 *  - the master switch is OFF by default and can only turn ON through the DATA-Egress
 *    DISCLOSURE (the pending gate; refusing the disclosure leaves everything off);
 *  - provider profiles CRUD: the reserved `__host__` scope can never be created (P13-05);
 *  - the API key is entered once and NEVER shown again — the state carries only PRESENCE;
 *  - the per-provider screenshot switch is OFF by default; keep-transcripts OFF; the
 *    cleartext-loopback switch sits under advanced (off by default);
 *  - "test connection" goes through the [ConnectionTester] seam (the production impl builds the
 *    real gateway; the tests script it) and never stores the key value in the result.
 */
class AiSettingsState(private val keys: KeyStore, private val tester: ConnectionTester) {

    /** The key seam (:app implements over AppProfileStore / SecretStore). */
    interface KeyStore {
        suspend fun put(profileId: String, value: String)
        suspend fun delete(profileId: String)
        suspend fun has(profileId: String): Boolean
    }

    fun interface ConnectionTester {
        data class Result(val ok: Boolean, val message: String)

        suspend fun test(profile: ProviderProfile): Result
    }

    data class Model(
        val enabled: Boolean = false,
        val profiles: List<ProviderProfile> = emptyList(),
        val keyPresent: Map<String, Boolean> = emptyMap(), // presence only — NEVER the value
        val screenshots: Map<String, Boolean> = emptyMap(), // per profile, default OFF
        val keepTranscripts: Boolean = false,
        val cleartextLoopback: Boolean = false,
        val disclosurePending: Boolean = false,
        val lastTest: Map<String, ConnectionTester.Result> = emptyMap(),
        val error: String? = null,
    )

    @Volatile var model: Model = Model()
        private set

    /** When the user accepted the §13 disclosure — profiles created after inherit it (§3). */
    private var disclosureAcceptedAtMs: Long? = null

    fun hydrate(
        enabled: Boolean,
        profiles: List<ProviderProfile>,
        keepTranscripts: Boolean,
        cleartextLoopback: Boolean,
        screenshots: Map<String, Boolean>,
        keyPresence: Map<String, Boolean>,
    ) {
        model = Model(
            enabled = enabled,
            profiles = profiles,
            keyPresent = keyPresence,
            screenshots = screenshots,
            keepTranscripts = keepTranscripts,
            cleartextLoopback = cleartextLoopback,
            disclosurePending = false,
        )
    }

    /** Turning the master switch ON requires the disclosure ONCE (S32: on FIRST enable); after
     *  the acceptance, toggling off and on is instant — the disclosure was already given. */
    fun setEnabled(on: Boolean) {
        if (!on) {
            model = model.copy(enabled = false, disclosurePending = false)
            return
        }
        if (disclosureAcceptedAtMs == null) {
            model = model.copy(disclosurePending = true) // the UI shows the §13 disclosure
        } else {
            model = model.copy(enabled = true, disclosurePending = false)
        }
    }

    /** The user accepted the disclosure — every existing profile is stamped (§3 egressAcceptedAt). */
    fun acceptDisclosure(nowMs: Long) {
        disclosureAcceptedAtMs = nowMs
        val stamped = model.profiles.map {
            if (it.egressAcceptedAtMs == null) it.copy(egressAcceptedAtMs = nowMs) else it
        }
        model = model.copy(enabled = true, disclosurePending = false, profiles = stamped)
    }

    /** The user refused the disclosure — nothing turns on. */
    fun declineDisclosure() {
        model = model.copy(disclosurePending = false, enabled = false)
    }

    fun upsertProfile(profile: ProviderProfile) {
        require(!ProviderProfile.isReservedProfileId(profile.id)) {
            "the reserved ${ProviderProfile.HOST_SCOPE} scope can never be a user profile"
        }
        // a profile created after the disclosure inherits the acceptance; before it, null ⇒ cannot run
        val existing = model.profiles.firstOrNull { it.id == profile.id }
        val stamped = profile.copy(
            egressAcceptedAtMs = profile.egressAcceptedAtMs ?: existing?.egressAcceptedAtMs ?: disclosureAcceptedAtMs,
        )
        val profiles = model.profiles.filterNot { it.id == profile.id } + stamped
        model = model.copy(profiles = profiles.sortedBy { it.id }, error = null)
    }

    /** A validation failure surfaces on the model (the screen shows it inline). */
    fun reportError(message: String) {
        model = model.copy(error = message)
    }

    suspend fun deleteProfile(profileId: String) {
        keys.delete(profileId)
        model = model.copy(
            profiles = model.profiles.filterNot { it.id == profileId },
            keyPresent = model.keyPresent - profileId,
            screenshots = model.screenshots - profileId,
            lastTest = model.lastTest - profileId,
        )
    }

    /** The key is stored through the seam and never kept here; only PRESENCE is recorded. */
    suspend fun saveKey(profileId: String, value: String) {
        require(model.profiles.any { it.id == profileId }) { "no profile '$profileId'" }
        keys.put(profileId, value)
        model = model.copy(keyPresent = model.keyPresent + (profileId to true))
    }

    fun setScreenshots(profileId: String, on: Boolean) {
        model = model.copy(screenshots = model.screenshots + (profileId to on))
    }

    fun setKeepTranscripts(on: Boolean) {
        model = model.copy(keepTranscripts = on)
    }

    fun setCleartextLoopback(on: Boolean) {
        model = model.copy(cleartextLoopback = on)
    }

    suspend fun testConnection(profileId: String) {
        val profile = model.profiles.firstOrNull { it.id == profileId } ?: return
        val result = tester.test(profile)
        model = model.copy(lastTest = model.lastTest + (profileId to result))
    }
}
