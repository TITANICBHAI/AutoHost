package app.autohost.capabilities.data

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
import kotlinx.serialization.json.putJsonArray

/**
 * P10-06 (ADR-073): `contacts`, `calendar` and `media` — the personal-data cluster. Every
 * provider is a thin gate over a seam (the app side binds ContentResolver/MediaStore); pure
 * caps live in [DataPolicy]. Runtime permissions are lazy (plan §6.2): availability reports
 * NEEDS_SETUP with the FixHub action until the user granted the ask.
 *
 * `media` is the device-wide MediaStore grant (READ_MEDIA_IMAGES/VIDEO/AUDIO API 33+ family,
 * READ_EXTERNAL_STORAGE below) — distinct from `files.shared`'s media_* methods, which are
 * scoped to a user-picked SAF tree. Inserted media is capped at [DataPolicy.MAX_INSERT_BYTES].
 */
object PersonalDataStores {

    /** Contacts seam (ContentResolver-backed on device; fake in tests). */
    interface Contacts {
        fun query(filter: String?, limit: Int): List<Map<String, String?>>
        fun insert(name: String, phone: String?, email: String?): Long
    }

    interface CalEvent {
        val title: String
        val startMs: Long
        val endMs: Long
        val location: String?
        val description: String?
    }

    interface Calendar {
        fun query(fromMs: Long, toMs: Long, limit: Int): List<CalEvent>
        fun insert(title: String, startMs: Long, endMs: Long, location: String?, description: String?): Long
    }

    interface MediaRow {
        val id: Long
        val kind: String
        val displayName: String
        val sizeBytes: Long
        val addedMs: Long
    }

    interface Media {
        fun list(kind: String?, limit: Int): List<MediaRow>

        /** Base64 (NO_WRAP) or null when missing/over the cap — base64 lives in the seam. */
        fun read(id: Long, maxBytes: Int): String?
        fun insert(kind: String, displayName: String, dataB64: String): Long
        fun delete(id: Long): Boolean
    }
}

/** Availability helper shared by the cluster: the app wiring reports the lazy-permission truth. */
abstract class LazyPermissionProvider(
    private val permissionId: String,
) : CapabilityProvider {
    @Volatile
    var granted: Boolean = false

    override fun availability(env: DeviceEnv): Availability = when {
        granted -> Availability.Available
        else -> Availability.NeedsSetup(reason = "RUNTIME_PERMISSION:$permissionId", fixAction = "REQUEST_PERMISSIONS")
    }
}

class ContactsProvider(private val store: PersonalDataStores.Contacts) : LazyPermissionProvider("contacts.read") {

    override val capabilityId: String = "contacts"
    override val providerId: String = "contacts.store"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "query" -> "contacts.read"
        "insert" -> "contacts.write"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "query" -> {
            val limit = DataPolicy.rows((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull())
            val filter = (args["filter"] as? JsonPrimitive)?.content
            val rows = store.query(filter, limit)
            Envelope.ok(buildJsonObject {
                putJsonArray("contacts") {
                    rows.forEach { row ->
                        add(buildJsonObject {
                            put("name", row["name"])
                            put("phone", row["phone"])
                            put("email", row["email"])
                        })
                    }
                }
                put("count", rows.size)
            })
        }
        "insert" -> {
            val name = (args["name"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"name\" is required")
            val phone = (args["phone"] as? JsonPrimitive)?.content
            val email = (args["email"] as? JsonPrimitive)?.content
            if (name.isBlank()) return Envelope.err(ErrorCode.INVALID_ARGS, "name must not be blank")
            val id = store.insert(name, phone, email)
            Envelope.ok(buildJsonObject { put("id", id) })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown contacts method '$method'")
    }
}

class CalendarProvider(private val store: PersonalDataStores.Calendar) : LazyPermissionProvider("calendar.read") {

    override val capabilityId: String = "calendar"
    override val providerId: String = "calendar.store"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "query" -> "calendar.read"
        "insert" -> "calendar.write"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "query" -> {
            val now = ctx.nowMs
            val fromMs = (args["from_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: now
            val toMs = (args["to_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: (now + 7L * 24 * 60 * 60 * 1000)
            if (!DataPolicy.windowVerdict(fromMs, toMs)) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "window must be ordered and within 31 days")
            }
            val limit = DataPolicy.rows((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull())
            val events = store.query(fromMs, toMs, limit)
            Envelope.ok(buildJsonObject {
                putJsonArray("events") {
                    events.forEach { event ->
                        add(buildJsonObject {
                            put("title", event.title)
                            put("start_ms", event.startMs)
                            put("end_ms", event.endMs)
                            if (event.location != null) put("location", event.location)
                            if (event.description != null) put("description", event.description)
                        })
                    }
                }
                put("count", events.size)
            })
        }
        "insert" -> {
            val title = (args["title"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"title\" is required")
            val startMs = (args["start_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"start_ms\" is required")
            val endMs = (args["end_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"end_ms\" is required")
            if (title.isBlank()) return Envelope.err(ErrorCode.INVALID_ARGS, "title must not be blank")
            if (endMs <= startMs) return Envelope.err(ErrorCode.INVALID_ARGS, "end_ms must be after start_ms")
            val location = (args["location"] as? JsonPrimitive)?.content
            val description = (args["description"] as? JsonPrimitive)?.content
            val id = store.insert(title, startMs, endMs, location, description)
            Envelope.ok(buildJsonObject { put("id", id) })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown calendar method '$method'")
    }
}

class MediaProvider(private val store: PersonalDataStores.Media) : LazyPermissionProvider("media") {

    override val capabilityId: String = "media"
    override val providerId: String = "media.store"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "read", "insert" -> "media.write" // bytes in/out are the higher-risk half; keep one id for both
        "list", "delete" -> "media"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "list" -> {
            val limit = DataPolicy.rows((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull())
            val kind = (args["kind"] as? JsonPrimitive)?.content
            val rows = store.list(kind, limit)
            Envelope.ok(buildJsonObject {
                putJsonArray("media") {
                    rows.forEach { row ->
                        add(buildJsonObject {
                            put("id", row.id)
                            put("kind", row.kind)
                            put("display_name", row.displayName)
                            put("size_bytes", row.sizeBytes)
                            put("added_ms", row.addedMs)
                        })
                    }
                }
                put("count", rows.size)
            })
        }
        "read" -> {
            val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
            val dataB64 = store.read(id, DataPolicy.MAX_READ_BYTES)
                ?: return Envelope.err(ErrorCode.NOT_FOUND, "media id $id not found (or over the ${DataPolicy.MAX_READ_BYTES} byte cap)")
            Envelope.ok(buildJsonObject {
                put("data_b64", dataB64)
                put("size_bytes", dataB64.length.toLong() * 3 / 4) // decoded estimate; the seam owns the truth
            })
        }
        "insert" -> {
            val kind = (args["kind"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"kind\" is required (image|video|audio)")
            if (kind !in listOf("image", "video", "audio")) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "kind must be image, video or audio")
            }
            val displayName = (args["display_name"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"display_name\" is required")
            val dataB64 = (args["data_b64"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"data_b64\" is required")
            if (dataB64.length.toLong() * 3 / 4 > DataPolicy.MAX_INSERT_BYTES) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "media exceeds ${DataPolicy.MAX_INSERT_BYTES} bytes")
            }
            val id = try {
                store.insert(kind, displayName, dataB64)
            } catch (e: IllegalArgumentException) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "data_b64 is not valid base64")
            }
            Envelope.ok(buildJsonObject { put("id", id) })
        }
        "delete" -> {
            val id = (args["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
            val deleted = store.delete(id)
            if (!deleted) return Envelope.err(ErrorCode.NOT_FOUND, "media id $id not found")
            Envelope.ok(buildJsonObject { put("deleted", true) })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown media method '$method'")
    }
}
