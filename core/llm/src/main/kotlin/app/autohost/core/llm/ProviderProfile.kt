package app.autohost.core.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * P13-05 (ADR-090, `docs/MODEL_GATEWAY.md` §3): the NON-SECRET part of a provider profile. One
 * JSON array of these lives in the app's `app_settings` under `ai.providers` (a key/value table —
 * no Room migration). The API key is NEVER here: it lives in `SecretStore` under the reserved
 * [HOST_SCOPE] profile id and reaches `:core:llm` only through the [SecretReader] seam at call
 * time (`:core:llm` must not see `:core:data`).
 */
@Serializable
data class ProviderProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    /** `max_tokens` (default) or `max_completion_tokens` — the endpoint's output-limit field. */
    val maxTokensField: String = "max_tokens",
    /** Send `parallel_tool_calls:false` (endpoint support UNVERIFIED — default off). */
    val parallelToolCallsParam: Boolean = false,
    /** User-declared context window for the local pre-flight budget; null = unknown. */
    val contextWindowTokens: Long? = null,
    /** Non-secret extra headers; names matching credential words are rejected at write time. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** When the user accepted the data-egress disclosure for this profile; null ⇒ cannot run. */
    val egressAcceptedAtMs: Long? = null,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 60_000,
) {
    init {
        require(ID_REGEX.matches(id)) { "profile id must match $ID_REGEX" }
        require(model.isNotBlank()) { "model id is required" }
        require(maxTokensField == "max_tokens" || maxTokensField == "max_completion_tokens") {
            "unknown output-limit field $maxTokensField"
        }
        require(extraHeaders.keys.all { !CREDENTIAL_WORD.containsMatchIn(it) }) {
            "extra header names must not match credential words"
        }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "timeouts must be positive" }
    }

    /** The storage JSON: one array under the `ai.providers` settings key. */
    fun encode(): String = Json.encodeToString(ProviderProfile.serializer(), this)

    companion object {
        const val ID_REGEX_STR: String = "[a-z0-9-]{1,32}"
        val ID_REGEX = Regex(ID_REGEX_STR)

        /** Reserved profile scope for host-held keys; user profiles can never use it. */
        const val HOST_SCOPE: String = "__host__"

        fun keyName(profileId: String): String = "ai.provider.$profileId.api_key"

        private val CREDENTIAL_WORD = Regex("(?i)api_key|apikey|authorization|token|secret|password|credential")

        fun isReservedProfileId(id: String): Boolean = id == HOST_SCOPE || id.startsWith("__")

        fun decode(text: String): ProviderProfile = Json.decodeFromString(serializer(), text)

        fun encodeAll(profiles: List<ProviderProfile>): String =
            Json.encodeToString(ListSerializer(serializer()), profiles)

        fun decodeAll(text: String): List<ProviderProfile> =
            Json.decodeFromString(ListSerializer(serializer()), text)
    }
}
