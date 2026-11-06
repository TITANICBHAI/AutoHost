package app.autohost.ai

import app.autohost.core.data.secrets.SecretRegistry
import app.autohost.core.data.secrets.SecretStore
import app.autohost.core.llm.ProfileStore
import app.autohost.core.llm.ProviderProfile
import kotlinx.coroutines.runBlocking

/**
 * P14-06 (S32, MODEL_GATEWAY §3): the [ProfileStore] seam over the app's settings repository
 * (the non-secret `ai.providers` array) and the SecretStore (the keys, under [HOST_SCOPE]).
 *
 * Threading: the `:core:llm` seams are synchronous and are called from the gateway's suspend
 * flow on background dispatchers — the suspend SecretStore reads bridge through `runBlocking`
 * here, never on the main thread (the settings screen calls the state holder, not this store).
 */
class AppProfileStore(
    private val settings: app.autohost.core.data.repo.SettingsRepository,
    private val secrets: SecretStore,
    private val registry: SecretRegistry,
) : ProfileStore {

    override fun load(): List<ProviderProfile> = runBlocking {
        settings.string(SETTING_PROVIDERS)?.let { raw ->
            runCatching { ProviderProfile.decodeAll(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    override fun save(profiles: List<ProviderProfile>) = runBlocking {
        settings.setString(SETTING_PROVIDERS, ProviderProfile.encodeAll(profiles))
    }

    override fun readApiKey(profileId: String): String? = runBlocking {
        secrets.get(ProviderProfile.HOST_SCOPE, ProviderProfile.keyName(profileId))
    }

    override fun registerKeyForRedaction(value: String) {
        registry.register(value)
    }

    /** Stores/replaces one profile's key (the settings screen's entry path). */
    suspend fun putApiKey(profileId: String, value: String) {
        secrets.put(ProviderProfile.HOST_SCOPE, ProviderProfile.keyName(profileId), value)
    }

    /** Removes one profile's key — never the whole HOST_SCOPE (§3). */
    suspend fun deleteApiKey(profileId: String) {
        secrets.delete(ProviderProfile.HOST_SCOPE, ProviderProfile.keyName(profileId))
    }

    suspend fun hasApiKey(profileId: String): Boolean =
        secrets.get(ProviderProfile.HOST_SCOPE, ProviderProfile.keyName(profileId)) != null

    companion object {
        const val SETTING_PROVIDERS = "ai.providers"

        // the S32 switches (all default OFF)
        const val SETTING_ENABLED = "ai.enabled"
        const val SETTING_KEEP_TRANSCRIPTS = "ai.keep_transcripts"
        const val SETTING_CLEARTEXT_LOOPBACK = "ai.cleartext_loopback"
        fun settingScreenshots(profileId: String) = "ai.screenshots.$profileId"
    }
}
