package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** P6-05: scope + templating (mirrors test_ref_flows.TemplateTests). */
class VariableScopeTest {
    private fun scope(
        config: Pair<String, Any>? = null,
        env: Map<String, JsonElement> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
    ): FlowScope = FlowScope(
        config = config?.let { (k, v) ->
            when (v) {
                is Int -> buildJsonObject { put(k, v) }
                is Boolean -> buildJsonObject { put(k, v) }
                else -> buildJsonObject { put(k, v as String) }
            }
        } ?: buildJsonObject { },
        profile = buildJsonObject { },
        env = env.toMutableMap(),
        run = buildJsonObject { put("attempt", 1) },
        secrets = secrets,
    )

    @Test fun `whole string placeholder keeps type`() {
        val sc = scope(config = "n" to 5)
        assertEquals(JsonPrimitive(5), sc.resolveString(JsonPrimitive("\${config.n}")))
        val flag = FlowScope(
            config = buildJsonObject { put("flag", true) },
            profile = buildJsonObject { },
            env = mutableMapOf(),
            run = buildJsonObject { },
            secrets = emptyMap(),
        )
        assertEquals(JsonPrimitive(true), flag.resolveString(JsonPrimitive("\${config.flag}")))
    }

    @Test fun `mixed string interpolates scalars`() {
        val sc = FlowScope(
            config = buildJsonObject { put("n", 5) },
            profile = buildJsonObject { },
            env = mutableMapOf("name" to JsonPrimitive("x")),
            run = buildJsonObject { },
            secrets = emptyMap(),
        )
        assertEquals(
            "n=5 name=x none=\${profile.m}",
            (sc.resolveString(JsonPrimitive("n=\${config.n} name=\${env.name} none=\${profile.m}")) as JsonPrimitive).content,
        )
    }

    @Test fun `bare name prefers env`() {
        val sc = FlowScope(
            config = buildJsonObject { put("x", "config") },
            profile = buildJsonObject { },
            env = mutableMapOf("x" to JsonPrimitive("env")),
            run = buildJsonObject { },
            secrets = emptyMap(),
        )
        assertEquals(JsonPrimitive("env"), sc.resolveString(JsonPrimitive("\${x}")))
    }

    @Test fun `unresolved placeholder stays literal`() {
        val sc = scope()
        assertEquals(
            "v=\${config.missing}",
            (sc.resolveString(JsonPrimitive("v=\${config.missing}")) as JsonPrimitive).content,
        )
    }

    @Test fun `secret stays literal outside actions`() {
        val sc = scope(secrets = mapOf("pw" to "s3cr3t"))
        assertEquals(
            "pw=\${secret.pw}",
            (sc.resolveString(JsonPrimitive("pw=\${secret.pw}"), allowSecrets = false) as JsonPrimitive).content,
        )
        assertEquals(
            "pw=s3cr3t",
            (sc.resolveString(JsonPrimitive("pw=\${secret.pw}"), allowSecrets = true) as JsonPrimitive).content,
        )
    }

    @Test fun `missing secret raises typed failure in action position`() {
        val sc = scope()
        val outcome = runBlockingOutcome(
            """{"schema": 1, "steps": [
               {"inputText": {"into": {"id": "field"}, "text": "${'$'}{secret.nope}", "secret": true}}]}""",
            sc,
        )
        assertEquals("FAILED", outcome.getValue("status").jsonPrimitive.content)
        assertEquals("NOT_FOUND", outcome.getValue("reason").jsonPrimitive.content)
    }

    private fun runBlockingOutcome(flowText: String, sc: FlowScope): kotlinx.serialization.json.JsonObject {
        val broker = object : FlowBroker {
            override suspend fun call(capability: String, method: String, args: kotlinx.serialization.json.JsonObject) =
                buildJsonObject { }
        }
        val ex = FlowExec(broker)
        val outcome = kotlinx.coroutines.runBlocking {
            FlowInterpreter.runFlow(FlowParser.parse(flowText), ex, secrets = sc.secrets)
        }
        return outcome.toJson()
    }

    @Test fun `redact replaces secret values`() {
        val sc = scope(secrets = mapOf("pw" to "s3cr3t"))
        assertEquals("token [REDACTED] leaked", sc.redact("token s3cr3t leaked"))
    }
}
