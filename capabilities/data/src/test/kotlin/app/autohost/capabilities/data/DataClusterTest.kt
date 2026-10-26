package app.autohost.capabilities.data

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10-06..08 JVM tests: DataPolicy caps and destination rails, the provider shapes over fakes
 * (lazy permissions, honest flags), and the per-run SMS budget. Pure JVM — no Android imports.
 */
class DataPolicyTest {

    @Test fun `row limits clamp into the window`() {
        assertEquals(DataPolicy.DEFAULT_ROWS, DataPolicy.rows(null))
        assertEquals(1, DataPolicy.rows(0))
        assertEquals(DataPolicy.MAX_ROWS, DataPolicy.rows(100_000))
        assertEquals(42, DataPolicy.rows(42))
    }

    @Test fun `event windows are ordered and bounded`() {
        assertTrue(DataPolicy.windowVerdict(0, 1))
        assertFalse(DataPolicy.windowVerdict(5, 1))
        assertFalse(DataPolicy.windowVerdict(0, DataPolicy.MAX_WINDOW_MS + 1))
        assertTrue(DataPolicy.windowVerdict(0, DataPolicy.MAX_WINDOW_MS))
    }

    @Test fun `dialable numbers pass and keep their digits`() {
        assertEquals("+15551234567", (DataPolicy.numberVerdict("+1 (555) 123-4567", false) as DataPolicy.NumberVerdict.Ok).normalized)
        assertEquals("0411234567", (DataPolicy.numberVerdict("0411 23 45 67", false) as DataPolicy.NumberVerdict.Ok).normalized)
    }

    @Test fun `short codes and premium destinations are refused`() {
        assertTrue(DataPolicy.numberVerdict("12345", false) is DataPolicy.NumberVerdict.Bad)
        assertTrue(DataPolicy.numberVerdict("12345", true) is DataPolicy.NumberVerdict.Ok) // explicit override only
        assertTrue(DataPolicy.numberVerdict("19001234567", false) is DataPolicy.NumberVerdict.Bad)
        assertTrue(DataPolicy.numberVerdict("09001234567", false) is DataPolicy.NumberVerdict.Bad)
        assertTrue(DataPolicy.numberVerdict("", false) is DataPolicy.NumberVerdict.Bad)
        assertTrue(DataPolicy.numberVerdict("call me maybe", false) is DataPolicy.NumberVerdict.Bad)
    }

    @Test fun `sms bodies are capped`() {
        assertEquals(null, DataPolicy.smsBodyVerdict("hello"))
        assertTrue(DataPolicy.smsBodyVerdict("") != null)
        assertTrue(DataPolicy.smsBodyVerdict("x".repeat(DataPolicy.MAX_SMS_BODY + 1)) != null)
    }

    @Test fun `record durations are bounded`() {
        assertEquals(DataPolicy.DEFAULT_RECORD_MS, DataPolicy.recordMs(null))
        assertEquals(500, DataPolicy.recordMs(10))
        assertEquals(DataPolicy.MAX_RECORD_MS, DataPolicy.recordMs(999_999))
    }
}

/** Fakes for the cluster. */
private class FakeContacts : PersonalDataStores.Contacts {
    val rows = mutableListOf<Map<String, String?>>()
    var inserted = 0
    override fun query(filter: String?, limit: Int): List<Map<String, String?>> =
        rows.filter { filter.isNullOrBlank() || (it["name"] ?: "").contains(filter!!, ignoreCase = true) }.take(limit)
    override fun insert(name: String, phone: String?, email: String?): Long {
        inserted++
        return 100L + inserted
    }
}

private class FakeCalendar : PersonalDataStores.Calendar {
    val events = mutableListOf<PersonalDataStores.CalEvent>()
    override fun query(fromMs: Long, toMs: Long, limit: Int): List<PersonalDataStores.CalEvent> =
        events.filter { it.endMs >= fromMs && it.startMs <= toMs }.take(limit)
    override fun insert(title: String, startMs: Long, endMs: Long, location: String?, description: String?): Long {
        events.add(object : PersonalDataStores.CalEvent {
            override val title = title
            override val startMs = startMs
            override val endMs = endMs
            override val location = location
            override val description = description
        })
        return 200L + events.size
    }
}

private class FakeMedia : PersonalDataStores.Media {
    val rows = mutableMapOf<Long, PersonalDataStores.MediaRow>()
    var nextId = 1L
    override fun list(kind: String?, limit: Int): List<PersonalDataStores.MediaRow> =
        rows.values.filter { kind == null || it.kind == kind }.take(limit)
    override fun read(id: Long, maxBytes: Int): String? = rows[id]?.let {
        java.util.Base64.getEncoder().encodeToString(ByteArray(it.sizeBytes.toInt()))
    }
    override fun insert(kind: String, displayName: String, dataB64: String): Long {
        val size = dataB64.length.toLong() * 3 / 4
        val id = nextId++
        rows[id] = object : PersonalDataStores.MediaRow {
            override val id = id
            override val kind = kind
            override val displayName = displayName
            override val sizeBytes = size
            override val addedMs = 0L
        }
        return id
    }
    override fun delete(id: Long): Boolean = rows.remove(id) != null
}

private fun ctx(vararg permissions: String) = CallContext(
    "r1", "t1", "p1",
    permissions.associate { it to kotlinx.serialization.json.JsonObject(emptyMap()) },
    nowMs = 1_000_000,
)

class PersonalDataProvidersTest {

    @Test fun `contacts query needs the read permission`() = runBlocking {
        val provider = ContactsProvider(FakeContacts())
        val denied = provider.invoke(ctx(), "query", buildJsonObject { })
        assertEquals(ErrorCode.PERMISSION_DENIED, ErrorCode.valueOf(denied.error!!.code))
    }

    @Test fun `contacts availability follows the lazy permission`() {
        val provider = ContactsProvider(FakeContacts())
        val needsSetup = provider.availability(app.autohost.core.broker.DeviceEnv())
        assertEquals("RUNTIME_PERMISSION:contacts.read", (needsSetup as app.autohost.core.model.Availability.NeedsSetup).reason)
        provider.granted = true
        assertTrue(provider.availability(app.autohost.core.broker.DeviceEnv()) is app.autohost.core.model.Availability.Available)
    }

    @Test fun `contacts insert requires write and returns an id`() = runBlocking {
        val store = FakeContacts()
        val provider = ContactsProvider(store)
        val denied = provider.invoke(ctx("contacts.read"), "insert", buildJsonObject { put("name", JsonPrimitive("A")) })
        assertEquals(ErrorCode.PERMISSION_DENIED, ErrorCode.valueOf(denied.error!!.code))
        val ok = provider.invoke(ctx("contacts.read", "contacts.write"), "insert", buildJsonObject {
            put("name", JsonPrimitive("Ada"))
            put("phone", JsonPrimitive("+15550001"))
        })
        assertTrue(ok.ok)
        assertEquals(101L, ok.result!!.jsonObject["id"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, store.inserted)
    }

    @Test fun `calendar insert validates order and window`() = runBlocking {
        val provider = CalendarProvider(FakeCalendar())
        val bad = provider.invoke(ctx("calendar.write"), "insert", buildJsonObject {
            put("title", JsonPrimitive("x"))
            put("start_ms", JsonPrimitive(20L))
            put("end_ms", JsonPrimitive(10L))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(bad.error!!.code))
        val bigWindow = provider.invoke(ctx("calendar.read"), "query", buildJsonObject {
            put("from_ms", JsonPrimitive(0L))
            put("to_ms", JsonPrimitive(DataPolicy.MAX_WINDOW_MS + 1))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(bigWindow.error!!.code))
    }

    @Test fun `media kinds are closed`() = runBlocking {
        val provider = MediaProvider(FakeMedia())
        val bad = provider.invoke(ctx("media.write"), "insert", buildJsonObject {
            put("kind", JsonPrimitive("doc"))
            put("display_name", JsonPrimitive("x"))
            put("data_b64", JsonPrimitive("YQ=="))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(bad.error!!.code))
    }

    @Test fun `media read miss is not found`() = runBlocking {
        val provider = MediaProvider(FakeMedia())
        val miss = provider.invoke(ctx("media.write"), "read", buildJsonObject { put("id", JsonPrimitive(42L)) })
        assertEquals(ErrorCode.NOT_FOUND, ErrorCode.valueOf(miss.error!!.code))
    }
}

private class FakeSms(private val inbox: List<TelephonySeams.SmsRow> = emptyList()) : TelephonySeams.Sms {
    val sent = mutableListOf<Pair<String, String>>()
    var failNext = false
    override fun inbox(filter: String?, senders: Set<String>?, limit: Int): List<TelephonySeams.SmsRow> =
        inbox.filter { (senders == null || it.sender in senders) && (filter == null || it.body.contains(filter)) }.take(limit)
    override fun send(address: String, body: String): TelephonySeams.SendOutcome =
        if (failNext) TelephonySeams.SendOutcome(false, "radio off") else TelephonySeams.SendOutcome(true).also { sent.add(address to body) }
}

private class FakeTelephony : TelephonySeams.Telephony {
    var state = TelephonySeams.CallState.IDLE
    var placed: String? = null
    override fun callState() = state to null
    override fun placeCall(number: String): Boolean {
        placed = number
        return true
    }
}

private class TestClock(var ms: Long = 0) : app.autohost.core.common.Clock {
    override fun nowMs(): Long = ms
}

class SmsTelephonyProvidersTest {

    @Test fun `sms send budget is per run and cleared on terminal`() = runBlocking {
        val store = FakeSms()
        val provider = SmsProvider(store, TestClock())
        val grants = ctx("device.sms.send")
        repeat(DataPolicy.MAX_SMS_SENDS_PER_RUN) {
            val ok = provider.invoke(grants, "send", buildJsonObject {
                put("address", JsonPrimitive("+15551234567"))
                put("body", JsonPrimitive("run budget"))
            })
            assertTrue(ok.ok)
        }
        val over = provider.invoke(grants, "send", buildJsonObject {
            put("address", JsonPrimitive("+15551234567"))
            put("body", JsonPrimitive("over"))
        })
        assertEquals(ErrorCode.RATE_LIMITED, ErrorCode.valueOf(over.error!!.code))
        SmsProvider.clearRun("r1")
        assertEquals(0, SmsProvider.sendsThisRun.size)
    }

    @Test fun `sms send is honest about delivery`() = runBlocking {
        val provider = SmsProvider(FakeSms(), TestClock())
        val envelope = provider.invoke(ctx("device.sms.send"), "send", buildJsonObject {
            put("address", JsonPrimitive("+15551234567"))
            put("body", JsonPrimitive("hello"))
        })
        val result = envelope.result!!.jsonObject
        assertEquals(true, result["sent"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, result["delivered"]!!.jsonPrimitive.content.toBoolean()) // never claimed
    }

    @Test fun `wait_for times out honestly`() = runBlocking {
        val clock = TestClock()
        val provider = SmsProvider(FakeSms(), clock)
        val envelope = provider.invoke(ctx("device.sms.read"), "wait_for", buildJsonObject {
            put("contains", JsonPrimitive("nope"))
            put("timeout_ms", JsonPrimitive(1_000L))
        })
        assertEquals(ErrorCode.TIMEOUT, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `sms availability is flavor-gated honestly`() {
        val provider = SmsProvider(FakeSms(), TestClock())
        val unsupported = provider.availability(app.autohost.core.broker.DeviceEnv())
        assertEquals("UNSUPPORTED_FLAVOR", (unsupported as app.autohost.core.model.Availability.Unsupported).reason)
        provider.flavorFull = true
        val needsSetup = provider.availability(app.autohost.core.broker.DeviceEnv())
        assertTrue((needsSetup as app.autohost.core.model.Availability.NeedsSetup).reason.startsWith("RUNTIME_PERMISSION"))
        provider.granted = true
        assertTrue(provider.availability(app.autohost.core.broker.DeviceEnv()) is app.autohost.core.model.Availability.Available)
    }

    @Test fun `place_call never claims the call connected`() = runBlocking {
        val fake = FakeTelephony()
        val provider = TelephonyProvider(fake)
        val envelope = provider.invoke(ctx("device.telephony.place_call"), "place_call", buildJsonObject {
            put("number", JsonPrimitive("+15551234567"))
        })
        val result = envelope.result!!.jsonObject
        assertEquals(true, result["placed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, result["verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("+15551234567", fake.placed)
    }

    @Test fun `call_state maps the telephony states`() = runBlocking {
        val fake = FakeTelephony()
        val provider = TelephonyProvider(fake)
        fake.state = TelephonySeams.CallState.RINGING
        val envelope = provider.invoke(ctx("device.telephony.read"), "call_state", buildJsonObject { })
        assertEquals("ringing", envelope.result!!.jsonObject["state"]!!.jsonPrimitive.content)
    }
}
