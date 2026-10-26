package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9-10/P9-11: the device.settings write path — the pure policy and the provider mapping (reads
 * unchanged; the Writer seam decides the path; protected keys refuse; secure/global need confirm).
 */
class SettingsWriteTest {

    private class FakeWriter : SystemSettingsProvider.Writer {
        val written = mutableListOf<Triple<String, String, String>>()
        var applied = true

        override fun write(table: String, key: String, value: String): SystemSettingsProvider.WriteOutcome {
            written.add(Triple(table, key, value))
            return SystemSettingsProvider.WriteOutcome(applied, "write_secure")
        }
    }

    private fun provider(reader: (String, String) -> String? = { _, _ -> null }, writer: SystemSettingsProvider.Writer? = null) =
        SystemSettingsProvider(reader, writer)

    private fun ctx() = CallContext(
        runId = "r1",
        taskId = "t1",
        profileId = null,
        grants = mapOf("device.settings.write" to JsonObject(emptyMap())),
        nowMs = 0L,
    )

    @Test fun `protected keys refuse before the writer is touched`() = runBlocking {
        val writer = FakeWriter()
        val envelope = provider(writer = writer).invoke(
            ctx(),
            "write",
            buildJsonObject {
                put("table", JsonPrimitive("secure"))
                put("key", JsonPrimitive("enabled_accessibility_services"))
                put("value", JsonPrimitive("x"))
                put("confirm", JsonPrimitive("true"))
            },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION, envelope.errorOrNull()?.codeAsEnum())
        assertTrue(writer.written.isEmpty())
    }

    @Test fun `secure table needs confirm`() = runBlocking {
        val writer = FakeWriter()
        val envelope = provider(writer = writer).invoke(
            ctx(),
            "write",
            buildJsonObject {
                put("table", JsonPrimitive("global"))
                put("key", JsonPrimitive("stay_on_while_plugged_in"))
                put("value", JsonPrimitive("7"))
            },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION, envelope.errorOrNull()?.codeAsEnum())
        assertTrue(writer.written.isEmpty())
    }

    @Test fun `write applies, reports the path and verifies by read-back`() = runBlocking {
        val writer = FakeWriter()
        val store = HashMap<String, String>()
        val p = provider(
            reader = { table, key -> store["$table.$key"] },
            writer = { table, key, value ->
                store["$table.$key"] = value
                SystemSettingsProvider.WriteOutcome(true, "write_secure")
            },
        )
        val envelope = p.invoke(
            ctx(),
            "write",
            buildJsonObject {
                put("table", JsonPrimitive("system"))
                put("key", JsonPrimitive("user_rotation"))
                put("value", JsonPrimitive("1"))
            },
        )
        val payload = envelope.result!!.jsonObject
        assertEquals("true", payload["written"]!!.jsonPrimitive.content)
        assertEquals("true", payload["verified"]!!.jsonPrimitive.content)
        assertEquals("write_secure", payload["path"]!!.jsonPrimitive.content)
    }

    @Test fun `no writer seam answers needs setup`() = runBlocking {
        val envelope = provider(writer = null).invoke(
            ctx(),
            "write",
            buildJsonObject {
                put("table", JsonPrimitive("system"))
                put("key", JsonPrimitive("k"))
                put("value", JsonPrimitive("v"))
            },
        )
        assertEquals(ErrorCode.NEEDS_SETUP, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `policy verdicts pin the denylist and caps`() {
        assertEquals("the key 'default_input_method' is protected (owned by AutoHost's self-heal)", SettingsWritePolicy.verdict("secure", "default_input_method", "x", true))
        assertTrue(SettingsWritePolicy.verdict("system", "bad key!", "v", false)?.contains("unexpected characters") == true)
        assertTrue(SettingsWritePolicy.verdict("system", "k", "v".repeat(1001), false)?.contains("exceeds") == true)
        assertTrue(SettingsWritePolicy.verdict("secure", "k", "v", false)?.contains("confirm") == true)
        assertEquals(null, SettingsWritePolicy.verdict("system", "some_key", "v", false))
        assertEquals(null, SettingsWritePolicy.verdict("global", "some_key", "v", true))
    }

    @Test fun `reads still work through the same provider`() = runBlocking {
        val p = provider(reader = { table, key -> if (table == "secure" && key == "k") "v" else null })
        val envelope = p.invoke(ctx(), "read", buildJsonObject { put("table", JsonPrimitive("secure")); put("key", JsonPrimitive("k")) })
        val payload = envelope.result!!.jsonObject
        assertEquals("v", payload["value"]!!.jsonPrimitive.content)
        assertEquals("true", payload["exists"]!!.jsonPrimitive.content)
    }
}
