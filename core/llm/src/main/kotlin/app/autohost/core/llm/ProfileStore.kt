package app.autohost.core.llm

/**
 * P13-05: the profile-persistence seam. The profiles are one JSON array under the app's
 * `app_settings` key `ai.providers`; `:app` implements this over the settings repository
 * (`:core:llm` must not see Room). Writes go through the same seam so the Settings screen
 * (P14-06) and the gateway read one source of truth.
 */
interface ProfileStore {
    fun load(): List<ProviderProfile>

    fun save(profiles: List<ProviderProfile>)

    /** The configured key for a profile at call time (SecretStore under [ProviderProfile.HOST_SCOPE]). */
    fun readApiKey(profileId: String): String?

    /** Registers a live key value with the redaction registry (once, at load). */
    fun registerKeyForRedaction(value: String)
}
