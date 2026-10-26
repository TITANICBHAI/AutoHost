package app.autohost.capabilities.net

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
 * P8 first-build gap closed: the `net.http` capability (wave-1 spec existed; the SDK and the sim
 * always called it, but no host provider was registered — on a device every request would have
 * been UNSUPPORTED). Brokered HTTP over [Engine]: domain allowlist (NetPolicy), size caps with
 * honest `truncated`, redacted header echo (never logs the raw Authorization/Cookie values).
 */
class HttpProvider(private val engine: Engine) : CapabilityProvider {

    /** HTTP engine seam (JavaHttpEngine in production). */
    interface Engine {
        fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMs: Long,
            maxBytes: Int,
        ): Response

        data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray, val truncated: Boolean)
    }

    override val capabilityId: String = "net.http"
    override val providerId: String = "net.http"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "request") "net.http" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (method != "request") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown net.http method '$method'")
        }
        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        if (!NetPolicy.schemeAllowed(url, websocket = false)) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "net.http needs an http(s) URL")
        }
        when (val verdict = NetPolicy.domainVerdict(ctx.grants["net.http"], url)) {
            is NetPolicy.DomainVerdict.Allowed -> {}
            is NetPolicy.DomainVerdict.NoGrant -> return Envelope.err(
                ErrorCode.PERMISSION_DENIED, "the run has no net.http grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
            is NetPolicy.DomainVerdict.Denied -> return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "host \"${verdict.host}\" is not in this run's net.http domain allowlist",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val verb = ((args["method"] as? JsonPrimitive)?.content ?: "GET").uppercase()
        if (verb !in VERBS) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "method must be one of ${VERBS.joinToString("|")}")
        }
        val headers = LinkedHashMap<String, String>()
        (args["headers"] as? JsonObject)?.forEach { (name, value) ->
            (value as? JsonPrimitive)?.content?.let { headers[name] = it }
        }
        val bodyB64 = (args["body_b64"] as? JsonPrimitive)?.content
            ?: (args["body"] as? JsonPrimitive)?.content?.encodeToByteArray()?.let {
                java.util.Base64.getEncoder().encodeToString(it)
            }
        val timeout = NetPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val grantMax = (ctx.grants["net.http"]?.get("max_bytes") as? JsonPrimitive)?.content?.toIntOrNull()
        val maxBytes = ((args["max_bytes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: grantMax ?: NetPolicy.DEFAULT_MAX_BYTES)
            .coerceAtMost(NetPolicy.DEFAULT_MAX_BYTES)
        return try {
            val response = engine.request(
                verb, url, headers,
                bodyB64?.let { java.util.Base64.getDecoder().decode(it) },
                timeout, maxBytes,
            )
            Envelope.ok(buildJsonObject {
                put("status", response.status)
                put("headers", buildJsonObject {
                    NetPolicy.redactHeaders(response.headers).forEach { (name, value) -> put(name, value) }
                })
                put("body_b64", java.util.Base64.getEncoder().encodeToString(response.body))
                put("truncated", response.truncated)
            })
        } catch (e: java.net.SocketTimeoutException) {
            Envelope.err(ErrorCode.TIMEOUT, "request timed out after ${timeout}ms")
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "request failed: ${e.message}")
        }
    }

    companion object {
        val VERBS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    }
}

/** The Android JVM engine over HttpsURLConnection (no extra dependency, honest caps). */
class JavaHttpEngine : HttpProvider.Engine {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Long,
        maxBytes: Int,
    ): HttpProvider.Engine.Response {
        val connection = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null && method !in setOf("GET", "HEAD")) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }
        val status = connection.responseCode
        val responseHeaders = connection.headerFields.orEmpty()
            .filterKeys { it != null }
            .mapKeys { it.key as String }
            .mapValues { (it.value.joinToString(", ")) }
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream ?: connection.inputStream
        // Bounded read loop (API-29-safe: no java11 InputStream.readNBytes(int)).
        val (bytes, truncated) = stream.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val chunk = ByteArray(8192)
            var total = 0
            var truncatedLocal = false
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (total + n > maxBytes) {
                    out.write(chunk, 0, maxBytes - total)
                    truncatedLocal = true
                    break
                }
                out.write(chunk, 0, n)
                total += n
            }
            out.toByteArray() to truncatedLocal
        }
        connection.disconnect()
        return HttpProvider.Engine.Response(status, responseHeaders, bytes, truncated)
    }
}
