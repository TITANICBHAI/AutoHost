package app.autohost.capabilities.shell

/**
 * P9-04 (plan §6.1 `device.shell`: "DANGEROUS; allowlist default empty"): the pure command
 * allowlist. A run may only execute commands matching the grant scope
 * `{"commands": ["settings get *", "dumpsys battery"]}` — a missing grant or an empty list allows
 * nothing (fail closed), and a set of shell metacharacters is refused **always**, even when a
 * pattern would otherwise match, because the `shell:` service runs commands through `/bin/sh`
 * (ADR-066). Wildcards: `*` matches any run of characters within one pattern; `?` matches exactly
 * one non-space character; everything else is literal.
 */
object CommandAllowlist {

    const val DEFAULT_MAX_LENGTH = 4_000

    /** Shell metacharacters that are never permitted (ADR-066: v1 hardening). */
    private val FORBIDDEN = charArrayOf(';', '&', '|', '`', '(', ')', '<', '>', '\n', '\r')

    sealed class Verdict {
        data object Allowed : Verdict()
        data class Denied(val detail: String) : Verdict()
    }

    /**
     * @param grant the `device.shell` grant object from the run context (null = no grant at all).
     * @param command the raw command string the task passed.
     */
    fun verdict(grant: kotlinx.serialization.json.JsonObject?, command: String): Verdict {
        if (command.isBlank()) return Verdict.Denied("command is empty")
        if (command.length > DEFAULT_MAX_LENGTH) return Verdict.Denied("command exceeds $DEFAULT_MAX_LENGTH characters")
        for (character in FORBIDDEN) {
            if (command.indexOf(character) >= 0) {
                return Verdict.Denied("command contains the forbidden shell metacharacter '${character}'")
            }
        }
        if (command.contains("$")) return Verdict.Denied("command contains '$' (parameter expansion is not permitted)")
        val patterns = (grant?.get("commands") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return Verdict.Denied("the run has no device.shell grant")
        if (patterns.isEmpty()) return Verdict.Denied("the device.shell grant has an empty allowlist")
        return if (patterns.any { matches(it, command) }) {
            Verdict.Allowed
        } else {
            Verdict.Denied("command is not in the device.shell allowlist")
        }
    }

    /** `*` = any run of characters; `?` = exactly one non-space character; case-sensitive. */
    fun matches(pattern: String, command: String): Boolean {
        if (pattern.isBlank()) return false
        return matchAt(pattern, 0, command, 0)
    }

    private fun matchAt(pattern: String, p: Int, value: String, v: Int): Boolean {
        if (p == pattern.length) return v == value.length
        return when (pattern[p]) {
            '*' -> {
                for (skip in v..value.length) {
                    if (matchAt(pattern, p + 1, value, skip)) return true
                }
                false
            }
            '?' -> v < value.length && value[v] != ' ' && matchAt(pattern, p + 1, value, v + 1)
            else -> v < value.length && pattern[p] == value[v] && matchAt(pattern, p + 1, value, v + 1)
        }
    }
}
