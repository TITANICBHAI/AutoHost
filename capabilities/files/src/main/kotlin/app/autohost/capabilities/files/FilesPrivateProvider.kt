package app.autohost.capabilities.files

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Host provider for the `files.private` capability (plan §6.1 W1: "Task-private storage").
 *
 * First-build gap closed in P8: the W1 spec and the SDK always called `files.private`, the sim
 * implemented it, but no host provider was registered — on a device every call would have been
 * UNSUPPORTED. The provider is host-tier (T0, no Android access): the task's private folder is a
 * plain directory under the app's filesDir, resolved by the app layer via [rootFor].
 *
 * Path rules mirror [SharedFilesPolicy.safeRelative]: relative, no `..`, no absolute — a task can
 * never escape its own folder (fail closed → SCOPE_VIOLATION).
 */
class FilesPrivateProvider(
    private val rootFor: (taskId: String) -> File,
    private val maxBytes: Int = MAX_TRANSFER_BYTES,
) : CapabilityProvider {

    override val capabilityId: String = "files.private"
    override val providerId: String = "host.files"
    override val tier: String = "host"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "files.private" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = try {
        val segments = SharedFilesPolicy.safeRelative((args["path"] as? JsonPrimitive)?.content ?: "")
            ?: return Envelope.err(ErrorCode.SCOPE_VIOLATION, "path escapes the task's private folder (.. / absolute)")
        val root = rootFor(ctx.taskId).apply { mkdirs() }
        val file = root + segments
        when (method) {
            "read" -> {
                if (!file.isFile) return Envelope.err(ErrorCode.NOT_FOUND, "no such file: ${segments.joinToString("/")}")
                val bytes = file.readBytes()
                when (val plan = SharedFilesPolicy.readPlan(bytes.size.toLong())) {
                    is SharedFilesPolicy.ReadPlan.Fit -> Envelope.ok(buildJsonObject {
                        put("content", bytes.decodeToString())
                        put("body_b64", b64Encode(bytes))
                        put("truncated", false)
                    })
                    is SharedFilesPolicy.ReadPlan.Truncated -> Envelope.ok(buildJsonObject {
                        put("body_b64", b64Encode(bytes.copyOf(plan.bytes)))
                        put("truncated", true)
                    })
                }
            }
            "write" -> {
                val bytes = decodeBody(args)
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "content (string) or body_b64 is required")
                if (bytes.size > maxBytes) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "content exceeds the $maxBytes-byte cap")
                }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                Envelope.ok(buildJsonObject { put("written", bytes.size) })
            }
            "list" -> {
                if (!file.isDirectory) {
                    return Envelope.err(ErrorCode.NOT_FOUND, "no such folder: ${segments.joinToString("/")}")
                }
                val entries = file.listFiles()?.sortedBy { it.name }?.take(SharedFilesPolicy.MAX_LIST_ENTRIES)
                    ?: emptyList()
                Envelope.ok(buildJsonObject {
                    put("entries", buildJsonArray {
                        entries.forEach { add(kotlinx.serialization.json.JsonPrimitive(if (it.isDirectory) "${it.name}/" else it.name)) }
                    })
                })
            }
            "delete" -> {
                if (!file.exists()) return Envelope.err(ErrorCode.NOT_FOUND, "no such file: ${segments.joinToString("/")}")
                Envelope.ok(buildJsonObject { put("deleted", file.delete()) })
            }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown files.private method '$method'")
        }
    } catch (e: Exception) {
        Envelope.err(ErrorCode.INTERNAL, "files.private failed: ${e.message}")
    }

    private infix fun File.plus(segments: List<String>): File = segments.fold(this) { dir, name -> File(dir, name) }

    private fun decodeBody(args: JsonObject): ByteArray? {
        val b64 = (args["body_b64"] as? JsonPrimitive)?.content
        if (b64 != null) return runCatching { b64Decode(b64) }.getOrNull()
        val content = (args["content"] as? JsonPrimitive)?.content ?: return null
        return content.encodeToByteArray()
    }

    companion object {
        const val MAX_TRANSFER_BYTES = 5 * 1024 * 1024
        val METHODS = setOf("read", "write", "list", "delete")
    }
}
