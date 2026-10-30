package app.autohost.core.broker

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityRegistryDuplicateTest {

    @Test
    fun `given the same provider registered twice when listing then it appears once`() {
        val registry = CapabilityRegistry()
        val provider = FakeProvider(capabilityId = "store")

        registry.register(provider)
        registry.register(provider)

        assertEquals(1, registry.providers("store").size)
        assertEquals(1, registry.all().size)
    }
}
