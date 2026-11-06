package app.autohost.runtime.agent

import java.security.MessageDigest

/**
 * P13-10 (AGENT_RUNTIME §7.2): the versioned system prompt — an eval-tuned asset whose hash is
 * recorded with the run (P13-15) and whose changes require new baselines (P13-16). Nothing from
 * a run is ever promoted into it (ADR-091 (4)).
 */
object SystemPrompt {

    const val RESOURCE = "prompts/system_v1.txt"
    const val VERSION = "v1"

    @Volatile
    private var cached: Pair<String, String>? = null

    /** The prompt text. */
    fun load(): String = pair().first

    /** The sha-256 of the prompt text (hex) — recorded in the run's evidence (P13-15). */
    fun sha256(): String = pair().second

    private fun pair(): Pair<String, String> {
        cached?.let { return it }
        val text = javaClass.classLoader!!.getResourceAsStream(RESOURCE)!!
            .readBytes().decodeToString()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val p = text to hash
        cached = p
        return p
    }
}
