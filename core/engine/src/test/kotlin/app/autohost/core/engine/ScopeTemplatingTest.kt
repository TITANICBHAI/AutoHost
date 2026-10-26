package app.autohost.core.engine

import app.autohost.core.engine.tasks.ScopeTemplating
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeTemplatingTest {
    private val config = buildJsonObject {
        put("target_package", "app.autohost.fixture")
        put("nested", buildJsonObject { put("key", "value") })
    }
    private val profile = buildJsonObject { put("region", "eu-1") }

    @Test
    fun `given config placeholder when applied then value substitutes`() {
        val scope = buildJsonObject { put("packages", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("${'$'}{config.target_package}")))) }
        val applied = ScopeTemplating.apply(scope, config, profile)
        val packages = applied["packages"] as kotlinx.serialization.json.JsonArray
        assertEquals("app.autohost.fixture", (packages.first() as JsonPrimitive).content)
    }

    @Test
    fun `given profile placeholder when applied then value substitutes`() {
        assertEquals("eu-1", ScopeTemplating.applyString("${'$'}{profile.region}", config, profile))
    }

    @Test
    fun `given dotted path when applied then resolves nested value`() {
        assertEquals("value", ScopeTemplating.applyString("${'$'}{config.nested.key}", config, profile))
    }

    @Test
    fun `given unresolved placeholder when applied then stays literal fail closed`() {
        val out = ScopeTemplating.applyString("${'$'}{config.missing}", config, profile)
        assertEquals("${'$'}{config.missing}", out)
    }

    @Test
    fun `given placeholder inside longer string when applied then inline substitution`() {
        val out = ScopeTemplating.applyString("prefix-${'$'}{config.target_package}-suffix", config, profile)
        assertEquals("prefix-app.autohost.fixture-suffix", out)
    }

    @Test
    fun `given non string json when applied then untouched`() {
        val scope = buildJsonObject { put("count", JsonPrimitive(3)) }
        assertEquals(3, (scope["count"] as JsonPrimitive).content.toInt())
        val applied = ScopeTemplating.apply(scope, config, profile)
        assertEquals(3, (applied["count"] as JsonPrimitive).content.toInt())
    }
}
