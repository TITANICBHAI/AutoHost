package app.autohost.core.model

/** Honest availability: a capability is never silently missing. Reason and fix codes come from schemas/errors.json. */
sealed interface Availability {
    data object Available : Availability
    data class NeedsSetup(val reason: String, val fixAction: String) : Availability
    data class Unsupported(val reason: String) : Availability
}
