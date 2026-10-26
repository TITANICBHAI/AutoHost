package app.autohost.core.engine.health

/**
 * P8-14: lazy Android-permission flows (plan §6.2: "When a task is granted a capability whose
 * provider needs an Android permission or special access, availability reads
 * NEEDS_SETUP(RUNTIME_PERMISSION: …); the Fix button launches a small explanatory request flow.
 * Pre-flight re-checks before every run").
 *
 * Pure mapping: capability → the Android permissions the provider's paths need, with honest
 * API-level notes (rules differ by API level — the map carries the note, the provider checks the
 * live state). Providers keep doing their own runtime checks ([NEEDS_SETUP] reasons stay the
 * source of truth); this map is what the Fix flow and Setup & Health show *before* a run.
 */
object LazyPermissions {

    data class Requirement(
        val permission: String,
        /** Where the fix goes: a runtime request, a special-access screen or an app setting. */
        val kind: Kind,
        val note: String,
    )

    enum class Kind { RUNTIME, SPECIAL_ACCESS, APP_SETTING }

    /** Capability id → requirements (first match wins for the family prefix). */
    private val MAP: List<Pair<String, List<Requirement>>> = listOf(
        "files.shared" to listOf(
            Requirement(
                "android.permission.READ_EXTERNAL_STORAGE",
                Kind.RUNTIME,
                "MediaStore reads of other apps' rows; SAF trees need no permission (user picks them)",
            ),
        ),
        "device.audio" to listOf(
            Requirement(
                "android.permission.RECORD_AUDIO",
                Kind.RUNTIME,
                "only for future microphone paths; volume/TTS/vibrate/torch need no permission",
            ),
        ),
        "device.notifications" to listOf(
            Requirement(
                "android.permission.POST_NOTIFICATIONS",
                Kind.RUNTIME,
                "notify.post channels on API 33+; the listener needs its own enable (separate fix)",
            ),
        ),
    )

    fun requirementsFor(capabilityId: String): List<Requirement> =
        MAP.firstOrNull { (id, _) -> capabilityId == id || capabilityId.startsWith("$id.") }?.second ?: emptyList()

    /** The capabilities the lazy flow knows about (Setup & Health can list them honestly). */
    fun coveredCapabilities(): List<String> = MAP.map { it.first }
}
