package app.autohost.core.engine.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-14: the lazy permission map stays honest and covered-by-test (plan §6.2). */
class LazyPermissionsTest {

    @Test fun `files shared maps to the storage runtime permission with the SAF note`() {
        val reqs = LazyPermissions.requirementsFor("files.shared")
        assertEquals(1, reqs.size)
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", reqs.single().permission)
        assertEquals(LazyPermissions.Kind.RUNTIME, reqs.single().kind)
        assertTrue(reqs.single().note.contains("SAF"))
    }

    @Test fun `unknown capabilities have no requirements (low risk)`() {
        assertTrue(LazyPermissions.requirementsFor("device.ui").isEmpty())
        assertTrue(LazyPermissions.requirementsFor("store").isEmpty())
        assertTrue(LazyPermissions.requirementsFor("made.up.capability").isEmpty())
    }

    @Test fun `covered capabilities list is stable for the health center`() {
        assertEquals(listOf("files.shared", "device.audio", "device.notifications"), LazyPermissions.coveredCapabilities())
    }
}
