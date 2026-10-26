package app.autohost.core.broker.security

/**
 * P8-13: insecure-feature switches (plan §14.5) — the pure half of the two-level gate.
 *
 * High-risk capabilities require BOTH the per-task grant (the normal Grant link) AND a global
 * switch in Settings → Developer & security. Switches are **off by default**; a "relaxed mode"
 * enables every known feature except explicit denies, and a deny always wins over the relaxed
 * mode and over the enabled set. Names are `feature` ids from the KNOWN list — the plan spells
 * them `scope:feature` where scope is a capability family or `*`; we keep the flat feature id
 * because the family mapping lives in [featureFor] and the UI shows the mapped capability ids.
 *
 * Enforcement is [app.autohost.core.broker.interceptor.InsecureFeatureInterceptor]; persistence
 * (app_settings keys) and the audit trail of every enable live in the app layer. Low-risk
 * capabilities are not in the map and always pass — the switch layer must never become an extra
 * gate for ordinary work.
 */
object InsecureFeatures {

    /** The known switch ids (plan §14.5). Everything here defaults to off. */
    val KNOWN = listOf(
        "shell", // device.shell (W4)
        "raw_api", // android.api.direct
        "app_manage", // device.app.manage methods (W4)
        "all_files", // files.all (W3)
        "sms", // device.sms (W3)
        "telephony", // device.telephony (W3)
        "logcat", // device.logcat (W4)
        "mock_location", // device.location.mock (W4)
        "dev_api_remote", // Dev API on the LAN, not just localhost/adb-forward
        "net_reverse", // net.reverse: device-initiated WebSocket to a host (P10 W3 rider)
    )

    const val SETTING_RELAXED = "insecure.relaxed"
    const val SETTING_ENABLED = "insecure.enabled" // JSON array of feature ids
    const val SETTING_DENIED = "insecure.denied" // JSON array of feature ids

    /** Immutable switch snapshot; the app layer keeps a volatile copy and refreshes on writes. */
    data class State(
        val relaxed: Boolean = false,
        val enabled: Set<String> = emptySet(),
        val denied: Set<String> = emptySet(),
    )

    sealed class Decision {
        /** The capability family either has no switch (low risk) or its switch is satisfied. */
        data object Allowed : Decision()

        /** The family is switched off: `DISABLED_BY_SETTING`, fix opens the switch. */
        data class Disabled(val feature: String) : Decision()
    }

    /**
     * Capability/permission → switch feature. Checked by permission first (families like
     * `device.app` carry both safe and dangerous methods), then by capability id. Null = no gate.
     */
    fun featureFor(capabilityId: String, permission: String?): String? = when {
        permission == "device.app.manage" -> "app_manage"
        else -> when (capabilityId) {
            "device.shell" -> "shell"
            "android.api.direct" -> "raw_api"
            "files.all" -> "all_files"
            "device.sms" -> "sms"
            "device.telephony" -> "telephony"
            "device.logcat" -> "logcat"
            "device.location.mock" -> "mock_location"
            "net.reverse" -> "net_reverse"
            else -> null
        }
    }

    /** The decision for one call. Deny wins over everything; relaxed covers every non-denied feature. */
    fun evaluate(capabilityId: String, permission: String?, state: State): Decision {
        val feature = featureFor(capabilityId, permission) ?: return Decision.Allowed
        if (feature in state.denied) return Decision.Disabled(feature)
        if (feature in state.enabled) return Decision.Allowed
        if (state.relaxed && feature in KNOWN) return Decision.Allowed
        return Decision.Disabled(feature)
    }

    /** Parses the persisted state; unknown feature ids are dropped so a stale id from a renamed
     *  switch can never keep gating anything. */
    fun parseState(relaxed: Boolean, enabledJson: String?, deniedJson: String?): State = State(
        relaxed = relaxed,
        enabled = parseIds(enabledJson).filter { it in KNOWN }.toSet(),
        denied = parseIds(deniedJson).filter { it in KNOWN }.toSet(),
    )

    private fun parseIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
            (element as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull() ?: emptyList()
    }
}
