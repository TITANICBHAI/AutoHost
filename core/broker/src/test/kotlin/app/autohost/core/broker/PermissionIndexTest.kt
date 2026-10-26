package app.autohost.core.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionIndexTest {
    private val index = PermissionIndex.loadFromResources(PermissionIndex::class.java.classLoader)

    @Test
    fun `given bundled specs when parsed then capabilities and permissions indexed`() {
        assertTrue(index.isCapability("device.ui"))
        assertTrue(index.isPermission("device.ui.observe"))
        assertTrue(index.isPermission("store.profile"))
        assertFalse(index.isPermission("device.ui"))
        assertFalse(index.isCapability("device.ui.act"))
        assertEquals("device.ui", index.capabilityOf("device.ui.act"))
    }

    @Test
    fun `given capability when permissions listed then every permission maps back`() {
        for (capabilityId in setOf("device.ui", "device.app", "store", "state", "secrets", "system", "net.http", "notify.post", "device.screenshot", "device.screen", "device.ime", "ui.prompt", "files.private")) {
            val perms = index.permissionsOf(capabilityId)
            if (perms.isEmpty()) continue
            for (perm in perms) {
                assertEquals(capabilityId, index.capabilityOf(perm))
            }
        }
        assertTrue(index.allPermissions().contains("system.info"))
    }
}
