package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListKeysTest {

    @Test fun `no step key equals any group key, so the Setup list can never repeat a key`() {
        val keys = StepId.values().map { ListKeys.step(it) } + FeatureGroup.values().map { ListKeys.group(it) }
        assertEquals("duplicate key in the Setup list: $keys", keys.size, keys.toSet().size)
    }

    @Test fun `the name that crashed a phone is a real collision in the enums and is now kept apart`() {
        assertTrue(StepId.values().any { it.name == "NOTIFICATIONS" })
        assertTrue(FeatureGroup.values().any { it.name == "NOTIFICATIONS" })
        assertTrue(ListKeys.step(StepId.NOTIFICATIONS) != ListKeys.group(FeatureGroup.NOTIFICATIONS))
    }
}
