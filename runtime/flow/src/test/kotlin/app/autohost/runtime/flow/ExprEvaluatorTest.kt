package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6-05: expression language (mirrors test_ref_flows.ExprTests). */
class ExprEvaluatorTest {
    private val scope = FlowScope(
        config = kotlinx.serialization.json.buildJsonObject { },
        profile = kotlinx.serialization.json.buildJsonObject { },
        env = mutableMapOf(
            "n" to JsonPrimitive("3"),
            "flag" to JsonPrimitive(true),
        ),
        run = kotlinx.serialization.json.buildJsonObject { put("attempt", 2) },
        secrets = mapOf("s" to "x"),
    )

    private fun eval(expr: String) = ExprEvaluator.eval(expr, scope)

    @Test fun `comparisons and logic`() {
        assertTrue(eval("1 < 2 && 'a' == 'a'") == JsonPrimitive(true))
        assertTrue(eval("!(1 > 2) || false") == JsonPrimitive(true))
        assertTrue(eval("2 >= 2") == JsonPrimitive(true))
        assertTrue(eval("1 != 2") == JsonPrimitive(true))
    }

    @Test fun `precedence and parentheses`() {
        assertTrue(eval("1 + 2 == 3") == JsonPrimitive(true))
        assertTrue(eval("(1 + 2) == 3") == JsonPrimitive(true))
        assertTrue(eval("1 + 1 == 3") == JsonPrimitive(false))
    }

    @Test fun `string concat and number math`() {
        assertEquals(JsonPrimitive("abc"), eval("'a' + 'b' + 'c'"))
        assertEquals(JsonPrimitive(3L), eval("4 - 1 + 0"))
        assertEquals(JsonPrimitive("3x"), eval("env.n + 'x'"))
    }

    @Test fun `mixed plus is evaluation error`() {
        assertThrows(ExprError::class.java) { eval("'a' + 1") }
    }

    @Test fun `unknown identifier is evaluation error`() {
        val e = assertThrows(ExprError::class.java) { eval("nope") }
        assertTrue(e.message!!.contains("unknown variable"))
        assertThrows(ExprError::class.java) { eval("config.nope") }
    }

    @Test fun `cross type equality is false`() {
        assertFalse(eval("1 == '1'") == JsonPrimitive(true))
        assertFalse(eval("true == 1") == JsonPrimitive(true))
        assertTrue(eval("1 != '1'") == JsonPrimitive(true))
    }

    @Test fun `secrets are refused in expressions`() {
        val e = assertThrows(ExprError::class.java) { eval("secret.s == 'x'") }
        assertTrue(e.message!!.contains("secrets"))
    }

    @Test fun `when true with a non-boolean result fails closed`() {
        val envScope = FlowScope(
            config = kotlinx.serialization.json.buildJsonObject { },
            profile = kotlinx.serialization.json.buildJsonObject { },
            env = mutableMapOf("n" to JsonPrimitive(1)),
            run = kotlinx.serialization.json.buildJsonObject { },
            secrets = emptyMap(),
        )
        val e = assertThrows(FlowFailure::class.java) { ExprEvaluator.isTrue("env.n", envScope) }
        assertEquals("INVALID_ARGS", e.code)
    }
}
