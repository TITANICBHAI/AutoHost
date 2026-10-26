package app.autohost.capabilities.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9-09: the installer auto-accept policy additions — the INSTALL flow, the flow-dialog affinity
 * (a projection flow must never tap an installer button and vice versa) and the new
 * PACKAGE_INSTALLER_CONFIRM dialog matching (view-id + localized-label, plan risk table).
 */
class InstallerAutoAcceptTest {

    @Test fun `install flow accepts installer and runtime dialogs only`() {
        assertTrue(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.INSTALL, PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM))
        assertTrue(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.INSTALL, PopupGuardPolicy.KnownDialog.RUNTIME_PERMISSION))
        assertFalse(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.INSTALL, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT))
        assertFalse(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.NONE, PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM))
    }

    @Test fun `projection flow affinity is unchanged`() {
        assertTrue(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT))
        assertFalse(AutoAcceptPolicy.accepts(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM))
    }

    @Test fun `policy stand-downs cover affinity misses`() {
        val decision = AutoAcceptPolicy().evaluate(
            pendingFlow = AutoAcceptPolicy.Flow.MEDIA_PROJECTION,
            match = PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM,
            nowMs = 1000,
            lastFailureMs = -1,
            acceptTimestamps = emptyList(),
            attemptsForFlow = 0,
        )
        assertTrue(decision is AutoAcceptPolicy.Decision.StandDown)
        assertTrue((decision as AutoAcceptPolicy.Decision.StandDown).detail.contains("does not belong"))
    }

    @Test fun `installer dialog matches by id and by label`() {
        assertEquals(
            PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM,
            PopupGuardPolicy.KnownDialog.match(
                pkg = "com.android.packageinstaller",
                ids = setOf("com.android.packageinstaller:id/install_button"),
                labels = emptySet(),
            ),
        )
        assertEquals(
            PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM,
            PopupGuardPolicy.KnownDialog.match(
                pkg = "com.google.android.packageinstaller",
                ids = setOf("com.google.android.packageinstaller:id/continue_button"),
                labels = emptySet(),
            ),
        )
        assertEquals(
            PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM,
            PopupGuardPolicy.KnownDialog.match(
                pkg = "com.android.packageinstaller",
                ids = emptySet(),
                labels = setOf("Install this app?"),
            ),
        )
        // Non-system windows never match, whatever they show.
        assertEquals(
            null,
            PopupGuardPolicy.KnownDialog.match(
                pkg = "com.evil.repackager",
                ids = setOf("com.android.packageinstaller:id/install_button"),
                labels = setOf("install"),
            ),
        )
    }
}
