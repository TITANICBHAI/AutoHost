package app.autohost.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.autohost.capabilities.data.PersonalDataStores

/**
 * P10-06 (ADR-073): the ContentResolver-backed seam implementations for the personal-data
 * cluster. Row caps are enforced HERE as well as in the provider (defense in depth); runtime
 * permission truth is read per call so the lazy-permission flow (P8-14) stays honest.
 */
class AndroidContacts(private val context: Context) : PersonalDataStores.Contacts {

    private val resolver: ContentResolver = context.contentResolver

    private fun hasRead(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    override fun query(filter: String?, limit: Int): List<Map<String, String?>> {
        if (!hasRead()) return emptyList()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val selection = if (filter.isNullOrBlank()) null else "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val args = if (filter.isNullOrBlank()) null else arrayOf("%$filter%")
        val rows = mutableListOf<Map<String, String?>>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, selection, args,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                rows.add(mapOf("name" to name, "phone" to phoneOf(id), "email" to emailOf(id)))
            }
        }
        return rows
    }

    private fun phoneOf(contactId: String): String? = resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId), null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun emailOf(contactId: String): String? = resolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
        arrayOf(contactId), null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    override fun insert(name: String, phone: String?, email: String?): Long {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("WRITE_CONTACTS not granted")
        }
        val values = ContentValues().apply {
            put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
            put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
        }
        val rawUri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, values)!!
        val rawId = android.content.ContentUris.parseId(rawUri)
        fun data(mime: String, value: String?, kind: String) {
            if (value == null) return
            val row = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, mime)
                put(kind, value)
            }
            resolver.insert(ContactsContract.Data.CONTENT_URI, row)
        }
        data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, name, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
        data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone, ContactsContract.CommonDataKinds.Phone.NUMBER)
        data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email, ContactsContract.CommonDataKinds.Email.ADDRESS)
        return rawId
    }
}

class AndroidCalendar(private val context: Context) : PersonalDataStores.Calendar {

    private val resolver: ContentResolver = context.contentResolver

    override fun query(fromMs: Long, toMs: Long, limit: Int): List<PersonalDataStores.CalEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val projection = arrayOf(
            CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
        )
        val rows = mutableListOf<PersonalDataStores.CalEvent>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI, projection,
            "${CalendarContract.Events.DTEND} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(fromMs.toString(), toMs.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val title = cursor.getString(0) ?: continue
                val start = cursor.getLong(1)
                val end = cursor.getLong(2)
                rows.add(
                    object : PersonalDataStores.CalEvent {
                        override val title = title
                        override val startMs = start
                        override val endMs = end
                        override val location = cursor.getString(3)
                        override val description = cursor.getString(4)
                    },
                )
            }
        }
        return rows
    }

    override fun insert(title: String, startMs: Long, endMs: Long, location: String?, description: String?): Long {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("WRITE_CALENDAR not granted")
        }
        val calendarId = primaryCalendarId() ?: throw IllegalStateException("no writable calendar on this device")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
        }
        return android.content.ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, values)!!)
    }

    private fun primaryCalendarId(): Long? = resolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }
}

class AndroidMedia(private val context: Context) : PersonalDataStores.Media {

    private val resolver: ContentResolver = context.contentResolver

    private fun collectionFor(kind: String): android.net.Uri = when (kind) {
        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    override fun list(kind: String?, limit: Int): List<PersonalDataStores.MediaRow> {
        val uri = if (kind == null) MediaStore.Files.getContentUri("external") else collectionFor(kind)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val rows = mutableListOf<PersonalDataStores.MediaRow>()
        resolver.query(uri, projection, null, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val type = cursor.getInt(1)
                val kindName = when (type) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "audio"
                    else -> "file"
                }
                rows.add(
                    object : PersonalDataStores.MediaRow {
                        override val id = cursor.getLong(0)
                        override val kind = kindName
                        override val displayName = cursor.getString(2) ?: "?"
                        override val sizeBytes = cursor.getLong(3)
                        override val addedMs = cursor.getLong(4) * 1000
                    },
                )
            }
        }
        return rows
    }

    override fun read(id: Long, maxBytes: Int): String? {
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(maxBytes)
                if (bytes.size >= maxBytes && input.read() >= 0) return@use null // over the cap
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        }.getOrNull()
    }

    override fun insert(kind: String, displayName: String, dataB64: String): Long {
        val bytes = android.util.Base64.decode(dataB64, android.util.Base64.NO_WRAP)
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName)
            put(
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                when (kind) {
                    "video" -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    "audio" -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                    else -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                },
            )
        }
        val uri = resolver.insert(collectionFor(kind), values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return android.content.ContentUris.parseId(uri)
    }

    override fun delete(id: Long): Boolean {
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        return resolver.delete(uri, null, null) > 0
    }
}
