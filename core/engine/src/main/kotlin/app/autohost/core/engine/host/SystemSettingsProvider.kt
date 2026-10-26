package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-11 reads (system/secure/global tables, T0, no privilege escalation) + the P9-10 write path
 * (ADR-067, plan §6.1 W4 — "write via Write-settings or secure settings; write is gated").
 *
 * Writes go through the [Writer] seam the app wires: first-choice is the one-time
 * `WRITE_SECURE_SETTINGS` grant (`adb shell pm grant …`, PC helper `autohost grant-secure-settings`),
 * fallback is the paired shell tier (`settings put …`). The pure [SettingsWritePolicy] decides what
 * may be written: a denylist of keys this app must never touch, a confirm requirement for the
 * `secure`/`global` tables, and value caps. Unavailable write paths answer `NEEDS_SETUP` honestly.
 */
class SystemSettingsProvider(private val reader: Reader, private val writer: Writer? = null) : CapabilityProvider {

    /** Settings framework seam (AndroidSettingsReader in production). */
    fun interface Reader {
        fun read(table: String, key: String): String?
    }

    /** Write seam; the table is always one of [SystemSettingsProvider.TABLES]. */
    fun interface Writer {
        fun write(table: String, key: String, value: String): WriteOutcome
    }

    data class WriteOutcome(val applied: Boolean, val path: String)

    override val capabilityId: String = "device.settings"
    override val providerId: String = "settings.read"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        in METHODS -> "device.settings.read"
        "write" -> "device.settings.write"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        val table = (args["table"] as? JsonPrimitive)?.content ?: ""
        if (method != "write" && table !in TABLES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "table must be ${TABLES.joinToString("|")}")
        }
        return when (method) {
            "read" -> {
                val key = (args["key"] as? JsonPrimitive)?.content
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"key\" is required")
                val value = reader.read(table, key)
                Envelope.ok(buildJsonObject {
                    value?.let { put("value", it) }
                    put("exists", value != null)
                })
            }
            "read_many" -> {
                val keys = (args["keys"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"keys\" is required")
                if (keys.size > MAX_KEYS) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "at most $MAX_KEYS keys per call")
                }
                Envelope.ok(buildJsonObject {
                    put("values", buildJsonObject {
                        keys.forEach { key ->
                            reader.read(table, key)?.let { put(key, it) }
                        }
                    })
                })
            }
            "write" -> write(table, args)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.settings method '$method'")
        }
    }

    private fun write(table: String, args: JsonObject): Envelope {
        if (table !in TABLES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "table must be ${TABLES.joinToString("|")}")
        }
        if (writer == null) {
            return Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "no settings write path is available: grant WRITE_SECURE_SETTINGS once from a PC, or pair the shell tier",
                reason = "SETTINGS_WRITE_UNAVAILABLE",
                fixAction = "OPEN_SHELL_SETUP",
            )
        }
        val key = (args["key"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"key\" is required")
        val value = (args["value"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"value\" is required")
        val confirm = (args["confirm"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val verdict = SettingsWritePolicy.verdict(table, key, value, confirm)
        if (verdict != null) {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, verdict, fixAction = "OPEN_PERMISSION_REVIEW")
        }
        return try {
            val outcome = writer.write(table, key, value)
            if (!outcome.applied) {
                return Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "the settings write was refused by the platform",
                    reason = "SETTINGS_WRITE_REFUSED",
                )
            }
            // Honest verification: read-back through the same reader.
            val readBack = reader.read(table, key)
            Envelope.ok(buildJsonObject {
                put("written", true)
                put("table", table)
                put("key", key)
                put("path", outcome.path)
                put("verified", readBack == value)
            })
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "settings write failed: ${e.message}", reason = "SETTINGS_WRITE_REFUSED")
        }
    }

    companion object {
        val TABLES = setOf("system", "secure", "global")
        const val MAX_KEYS = 50
        val METHODS = setOf("read", "read_many")
    }
}

/**
 * Pure policy for the `device.settings` write path. Fail closed: unknown keys in `secure`/`global`
 * need `confirm:true`; keys AutoHost itself depends on (or that could brick the user's setup) are
 * always refused through the capability — self-heal writes those through the app layer, never
 * through a task. Values are capped and must not contain control characters.
 */
object SettingsWritePolicy {

    /** Keys that must never be written from a task (deny wins over everything). */
    val DENIED_KEYS = setOf(
        "enabled_accessibility_services", // self-heal owns this; a task could disable rivals or itself
        "accessibility_enabled",
        "default_input_method", // the IME self-select owns this; a task could lock the user out
        "device_provisioned",
        "user_setup_complete",
        "secure_user_id",
    )

    const val MAX_VALUE = 1000

    /** Returns the refusal detail, or null when the write may proceed. */
    fun verdict(table: String, key: String, value: String, confirm: Boolean): String? {
        if (key.isBlank() || key.length > 220 || !key.all { it.isLetterOrDigit() || it in "._-" }) {
            return "key contains unexpected characters"
        }
        if (key in DENIED_KEYS) return "the key '$key' is protected (owned by AutoHost's self-heal)"
        if (value.length > MAX_VALUE) return "value exceeds $MAX_VALUE characters"
        if (value.any { it.code < 32 && it != '\t' }) return "value contains control characters"
        if ((table == "secure" || table == "global") && !confirm) {
            return "writing '$table' settings is gated: pass confirm=true for this destructive table"
        }
        return null
    }
}
