package app.autohost

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S03 (milestone M2): the app has never been launched on a device (D-24). This runs the real Application and the real
 * MainActivity on the JVM. A failure here is a genuine "would crash at launch" finding, not a test problem.
 * SDK 35: Robolectric 4.16 needs JDK 21 to run SDK 36 and CI uses JDK 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchSmokeTest {

    @Test fun `the application builds its graph without a startup failure`() {
        val app = ApplicationProvider.getApplicationContext<AutoHostApp>()
        assertNull("AppGraph failed: ${app.startupFailure}", app.startupFailure)
    }

    @Test fun `main activity starts and stays open`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertFalse(controller.get().isFinishing)
    }
}
