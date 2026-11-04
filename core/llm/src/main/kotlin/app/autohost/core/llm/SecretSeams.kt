package app.autohost.core.llm

/**
 * P13-05 (ADR-090): the two seams `:core:llm` sees for host-held keys. `:core:data` owns the
 * real `SecretStore` (Android/Room) and `SecretRegistry` (in-memory redaction) — `:core:llm`
 * must not depend on it, so `:app` implements these over them (`docs/MODEL_GATEWAY.md` §3).
 */
fun interface SecretReader {
    /** Reads a key at call time; null = not configured. Implementations must not log the value. */
    fun read(profileId: String, keyName: String): String?
}

fun interface SecretRegistrar {
    /**
     * Registers a live value with the redaction registry (in-memory only) so a key that leaks
     * into any captured text is masked. Called once at gateway load — the gateway keeps only
     * this seam, never the value.
     */
    fun register(value: String)
}
