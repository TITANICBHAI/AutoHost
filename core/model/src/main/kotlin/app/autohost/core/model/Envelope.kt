package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Meta(
    val degraded: Boolean? = null,
    val degradedReason: String? = null,
    val snapshotAgeMs: Long? = null,
    val provider: String? = null,
    val durationMs: Long? = null,
    val unverified: Boolean? = null,
)

/** The only thing that crosses the broker: {"ok":true,"result":...} or {"ok":false,"error":{...}}. */
@Serializable
data class Envelope(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: AhError? = null,
    val meta: Meta? = null,
) {
    companion object {
        fun ok(result: JsonElement = JsonObject(emptyMap()), meta: Meta? = null): Envelope =
            Envelope(true, result, null, meta)

        fun err(error: AhError): Envelope = Envelope(false, null, error)

        fun err(code: ErrorCode, message: String, reason: String? = null, fixAction: String? = null): Envelope =
            err(AhError.of(code, message, reason, fixAction))
    }
}
