package app.autohost.core.llm

/**
 * P13-01 (ADR-090): provider-neutral errors for the model client, with the run reason code each
 * maps to (`docs/MODEL_GATEWAY.md` §6; the codes land in schemas/errors.json by regeneration,
 * P13-02). A [Cancelled] is NEVER converted into an error result — cancellation is rethrown.
 */
sealed interface LlmError {

    /** No profile, no key, egress not accepted, or the URL was rejected → `AI_NOT_CONFIGURED`. */
    data object NotConfigured : LlmError

    /** 401/403 → `MODEL_AUTH`. */
    data object Auth : LlmError

    /** 429 after retries → `MODEL_RATE_LIMITED`. */
    data class RateLimited(val retryAfterMs: Long?) : LlmError

    /** 5xx/408/425 after retries → `MODEL_UNREACHABLE`. */
    data class Transient(val cause: String) : LlmError

    /** DNS/connect/TLS failure → `MODEL_UNREACHABLE`. */
    data class Unreachable(val cause: String) : LlmError

    /** Context-length refusal (provider heuristic or local PromptBudget) → `MODEL_CONTEXT_OVERFLOW`. */
    data object ContextOverflow : LlmError

    /** Other 4xx / a 3xx redirect (never followed) → `MODEL_BAD_RESPONSE`. */
    data class InvalidRequest(val detail: String) : LlmError

    /** `finish_reason = content_filter` → `MODEL_FILTERED`. */
    data object ContentFiltered : LlmError

    /** Not JSON, missing `choices`, tool call without a name, truncated → `MODEL_BAD_RESPONSE`. */
    data class MalformedResponse(val detail: String) : LlmError
}
