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

/**
 * P8-07: the `files.shared` capability — SAF trees the user picked plus MediaStore reads/writes
 * (plan §6.1 W2). All framework calls sit behind [System] so the provider is JVM-testable.
 *
 * Honesty rules:
 * - A tree that is not in the run's grant is a `SCOPE_VIOLATION` with a `PICK_FOLDER` fix.
 * - MediaStore methods without the `media` scope flag are `SCOPE_VIOLATION`; missing runtime
 *   permission surfaces as `NEEDS_SETUP(RUNTIME_PERMISSION: …)` with the P8-14 fix.
 * - Reads over [SharedFilesPolicy.MAX_TRANSFER_BYTES] truncate and report `truncated: true`.
 */
class FilesSharedProvider(private val system: System) : CapabilityProvider {

    /** SAF + MediaStore framework seam (AndroidSharedFiles in production). */
    interface System {
        fun listChildren(treeUri: String, segments: List<String>): List<Entry>?
        fun read(treeUri: String, segments: List<String>): ByteArray?
        fun write(treeUri: String, segments: List<String>, bytes: ByteArray): Boolean
        fun delete(treeUri: String, segments: List<String>): Boolean

        /** MediaStore query: newest first, capped; [mediaType] is images/video/audio/files. */
        fun mediaList(mediaType: String, limit: Int): List<MediaRow>?
        fun mediaRead(id: String): ByteArray?
        fun mediaWrite(name: String, bytes: ByteArray, mediaType: String): String?
        fun mediaDelete(id: String): Boolean
        fun hasMediaReadPermission(): Boolean

        data class Entry(val name: String, val isDir: Boolean, val size: Long)
        data class MediaRow(val id: String, val displayName: String, val mediaType: String, val size: Long)
    }

    override val capabilityId: String = "files.shared"
    override val providerId: String = "files.shared"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "files.shared" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        val grant = ctx.grants[PERMISSION]
        return when (method) {
            "list" -> withTree(ctx, grant, args) { tree, segments ->
                val rows = system.listChildren(tree, segments)
                    ?: return@withTree errNotFound(tree)
                val capped = rows.take(SharedFilesPolicy.MAX_LIST_ENTRIES)
                Envelope.ok(buildJsonObject {
                    put("entries", buildJsonArray {
                        capped.forEach { entry ->
                            add(buildJsonObject {
                                put("name", entry.name)
                                put("dir", entry.isDir)
                                put("size", entry.size)
                            })
                        }
                    })
                    put("truncated", rows.size > capped.size)
                })
            }
            "read" -> withTree(ctx, grant, args) { tree, segments ->
                val bytes = system.read(tree, segments) ?: return@withTree errNotFound(tree)
                when (val plan = SharedFilesPolicy.readPlan(bytes.size.toLong())) {
                    is SharedFilesPolicy.ReadPlan.Fit -> Envelope.ok(buildJsonObject {
                        put("body_b64", b64Encode(bytes))
                        put("truncated", false)
                    })
                    is SharedFilesPolicy.ReadPlan.Truncated -> Envelope.ok(buildJsonObject {
                        put("body_b64", b64Encode(bytes.copyOf(plan.bytes)))
                        put("truncated", true)
                    })
                }
            }
            "write" -> withTree(ctx, grant, args) { tree, segments ->
                val bytes = decodeBody(args)
                    ?: return@withTree Envelope.err(ErrorCode.INVALID_ARGS, "content is required")
                if (bytes.size > SharedFilesPolicy.MAX_TRANSFER_BYTES) {
                    return@withTree Envelope.err(
                        ErrorCode.INVALID_ARGS,
                        "content exceeds the ${SharedFilesPolicy.MAX_TRANSFER_BYTES}-byte cap",
                    )
                }
                if (system.write(tree, segments, bytes)) {
                    Envelope.ok(buildJsonObject { put("written", bytes.size) })
                } else {
                    Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the document tree refused the write")
                }
            }
            "delete" -> withTree(ctx, grant, args) { tree, segments ->
                if (system.delete(tree, segments)) {
                    Envelope.ok(buildJsonObject { put("deleted", true) })
                } else {
                    errNotFound(tree)
                }
            }
            "media_list" -> withMedia(ctx, grant) {
                val type = (args["media_type"] as? JsonPrimitive)?.content ?: "files"
                if (type !in MEDIA_TYPES) {
                    return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "media_type must be one of $MEDIA_TYPES")
                }
                val limit = ((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 100).coerceIn(1, 500)
                val rows = system.mediaList(type, limit)
                    ?: return@withMedia Envelope.err(ErrorCode.INTERNAL, "MediaStore query failed")
                Envelope.ok(buildJsonObject {
                    put("rows", buildJsonArray {
                        rows.forEach { row ->
                            add(buildJsonObject {
                                put("id", row.id)
                                put("name", row.displayName)
                                put("media_type", row.mediaType)
                                put("size", row.size)
                            })
                        }
                    })
                })
            }
            "media_read" -> withMedia(ctx, grant) {
                val id = (args["id"] as? JsonPrimitive)?.content
                    ?: return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
                val bytes = system.mediaRead(id) ?: return@withMedia errNotFound(id)
                when (val plan = SharedFilesPolicy.readPlan(bytes.size.toLong())) {
                    is SharedFilesPolicy.ReadPlan.Fit -> Envelope.ok(buildJsonObject {
                        put("body_b64", b64Encode(bytes))
                        put("truncated", false)
                    })
                    is SharedFilesPolicy.ReadPlan.Truncated -> Envelope.ok(buildJsonObject {
                        put("body_b64", b64Encode(bytes.copyOf(plan.bytes)))
                        put("truncated", true)
                    })
                }
            }
            "media_write" -> withMedia(ctx, grant) {
                val name = (args["name"] as? JsonPrimitive)?.content
                    ?: return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "\"name\" is required")
                val bytes = decodeBody(args)
                    ?: return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "content is required")
                if (bytes.size > SharedFilesPolicy.MAX_TRANSFER_BYTES) {
                    return@withMedia Envelope.err(
                        ErrorCode.INVALID_ARGS,
                        "content exceeds the ${SharedFilesPolicy.MAX_TRANSFER_BYTES}-byte cap",
                    )
                }
                val type = (args["media_type"] as? JsonPrimitive)?.content ?: "files"
                if (type !in MEDIA_TYPES) {
                    return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "media_type must be one of $MEDIA_TYPES")
                }
                val id = system.mediaWrite(name, bytes, type)
                    ?: return@withMedia Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "MediaStore refused the write")
                Envelope.ok(buildJsonObject { put("id", id) })
            }
            "media_delete" -> withMedia(ctx, grant) {
                val id = (args["id"] as? JsonPrimitive)?.content
                    ?: return@withMedia Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
                if (system.mediaDelete(id)) Envelope.ok(buildJsonObject { put("deleted", true) })
                else errNotFound(id)
            }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown files.shared method '$method'")
        }
    }

    private inline fun withTree(
        ctx: CallContext,
        grant: JsonObject?,
        args: JsonObject,
        block: (treeUri: String, segments: List<String>) -> Envelope,
    ): Envelope {
        val tree = (args["tree"] as? JsonPrimitive)?.content
        when (val verdict = SharedFilesPolicy.treeVerdict(grant, tree)) {
            is SharedFilesPolicy.TreeVerdict.Allowed -> {}
            is SharedFilesPolicy.TreeVerdict.NotGranted -> return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "tree \"$tree\" is not in this run's files.shared grant",
                fixAction = "PICK_FOLDER",
            )
            is SharedFilesPolicy.TreeVerdict.NoGrant -> return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no files.shared grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val segments = SharedFilesPolicy.safeRelative((args["path"] as? JsonPrimitive)?.content)
            ?: return Envelope.err(ErrorCode.SCOPE_VIOLATION, "path escapes the picked tree (.. / absolute)")
        return block(tree ?: "", segments)
    }

    private inline fun withMedia(ctx: CallContext, grant: JsonObject?, block: () -> Envelope): Envelope {
        if (!SharedFilesPolicy.mediaAllowed(grant)) {
            return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "MediaStore access needs \"media\": true in the files.shared grant scope",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        if (!system.hasMediaReadPermission()) {
            return Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "MediaStore reads need the shared-storage runtime permission",
                reason = "RUNTIME_PERMISSION:READ_EXTERNAL_STORAGE",
                fixAction = "REQUEST_RUNTIME_PERMISSION",
            )
        }
        return block()
    }

    private fun decodeBody(args: JsonObject): ByteArray? {
        val b64 = (args["body_b64"] as? JsonPrimitive)?.content ?: return null
        return runCatching { b64Decode(b64) }.getOrNull()
    }

    private fun errNotFound(what: String) =
        Envelope.err(ErrorCode.NOT_FOUND, "no such document under $what")

    companion object {
        const val PERMISSION = "files.shared"
        val METHODS = setOf("list", "read", "write", "delete", "media_list", "media_read", "media_write", "media_delete")
        val MEDIA_TYPES = setOf("images", "video", "audio", "files")
    }
}

/** Base64 helpers on java.util.Base64 (JVM-stable; the worker marshals binaries as b64). */
internal fun b64Encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

internal fun b64Decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
