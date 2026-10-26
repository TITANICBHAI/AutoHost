package app.autohost.core.devapi

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-21: JSON-RPC 2.0 envelope helpers (docs/DEVAPI.md §2). Errors carry the host's own reason code in
 * `error.data.code` (a code from schemas/errors.json) with the JSON-RPC code always -32000 for app errors.
 */
object JsonRpc {

    data class Request(val id: Any?, val method: String, val params: JsonObject) {
        val isNotification: Boolean get() = id == null
    }

    sealed interface Response {
        fun toJson(): JsonObject

        data class Success(val id: Any?, val result: JsonObject) : Response {
            override fun toJson(): JsonObject = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: 0)
                put("result", result)
            }
        }

        data class Failure(val id: Any?, val code: String, val message: String, val details: JsonObject? = null) : Response {
            override fun toJson(): JsonObject = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: 0)
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", message)
                    put("data", buildJsonObject {
                        put("code", code)
                        if (details != null) put("details", details)
                    })
                })
            }
        }
    }

    /** Parses a request; failures map to PARSE_ERROR / INVALID_REQUEST responses. */
    fun parse(text: String): Either<Request, Response> {
        val root = try {
            AhJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Either.Right(Response.Failure(null, "PARSE_ERROR", "body is not a JSON object"))
        val method = (root["method"] as? JsonPrimitive)?.content
            ?: return Either.Right(Response.Failure(id(root), "INVALID_REQUEST", "\"method\" is required"))
        val invalidVersion = (root["jsonrpc"] as? JsonPrimitive)?.let { it.content != "2.0" } ?: true
        if (invalidVersion) {
            return Either.Right(Response.Failure(id(root), "INVALID_REQUEST", "\"jsonrpc\" must be \"2.0\""))
        }
        val params = (root["params"] as? JsonObject) ?: JsonObject(emptyMap())
        val idValue = id(root)
        return Either.Left(Request(idValue, method, params))
    }

    private fun id(root: JsonObject): Any? = when (val id = root["id"]) {
        is JsonPrimitive -> if (id.isString) id.content else id.content.toLongOrNull() ?: id.content
        null -> null
        else -> null
    }

    sealed interface Either<out L, out R> {
        data class Left<L>(val value: L) : Either<L, Nothing>
        data class Right<R>(val value: R) : Either<Nothing, R>
    }
}

/**
 * P2-21: bearer token handling. Tokens are 32 random bytes (hex), stored only in app settings,
 * compared in constant time. `ContentProvider` exposes the token over `adb shell content` for bootstrap.
 */
class TokenStore(private val random: () -> ByteArray = { ByteArray(32).also { java.security.SecureRandom().nextBytes(it) } }) {

    @Volatile
    private var current: String? = null

    fun issue(): String {
        val token = random().joinToString("") { "%02x".format(it) }
        current = token
        return token
    }

    fun currentOrNull(): String? = current

    fun revoke() {
        current = null
    }

    fun matches(candidate: String?): Boolean {
        val expected = current ?: return false
        if (candidate == null) return false
        return constantTimeEquals(expected, candidate)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.encodeToByteArray()
        val bb = b.encodeToByteArray()
        var diff = ba.size xor bb.size
        for (i in ba.indices) diff = diff or (ba[i].toInt() xor bb.getOrNull(i)?.toInt().orEmpty())
        return diff == 0
    }

    private fun Int.orEmpty(): Int = this
}

/** P2-21: rate limiting — a sliding window of timestamps (default 20 req/s, docs/DEVAPI.md §1). */
class RateLimiter(private val limit: Int = 20, private val windowMs: Long = 1_000) {
    private val hits = ArrayDeque<Long>()

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        while (hits.isNotEmpty() && nowMs - hits.first() > windowMs) hits.removeFirst()
        if (hits.size >= limit) return false
        hits.addLast(nowMs)
        return true
    }
}
