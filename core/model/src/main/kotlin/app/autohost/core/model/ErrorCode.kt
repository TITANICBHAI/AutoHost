package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Broker error codes. Source of truth: schemas/errors.json (brokerErrors). A test keeps this enum in sync. */
enum class ErrorCode(val retryable: Boolean) {
    PERMISSION_DENIED(false),
    SCOPE_VIOLATION(false),
    CAPABILITY_UNAVAILABLE(false),
    NEEDS_SETUP(false),
    INSECURE_FEATURE_DISABLED(false),
    TIMEOUT(true),
    NOT_FOUND(false),
    STALE_NODE(true),
    OBSCURED_BY_WINDOW(true),
    NO_ACTIVE_WINDOW(true),
    SCREENSHOT_BLOCKED(false),
    PROJECTION_NOT_GRANTED(true),
    TEXT_INPUT_REJECTED(true),
    TEXT_INPUT_UNVERIFIED(false),
    INVALID_ARGS(false),
    RATE_LIMITED(true),
    CANCELLED(false),
    IDLE_TIMEOUT(false),
    HOST_TOO_OLD(false),
    TRIGGER_RULE_INVALID(false),
    INTERNAL(false),
}

@Serializable
data class AhError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val reason: String? = null,
    val fixAction: String? = null,
    val details: JsonObject? = null,
) {
    companion object {
        fun of(
            code: ErrorCode,
            message: String,
            reason: String? = null,
            fixAction: String? = null,
            details: JsonObject? = null,
        ): AhError = AhError(code.name, message, code.retryable, reason, fixAction, details)
    }
}
