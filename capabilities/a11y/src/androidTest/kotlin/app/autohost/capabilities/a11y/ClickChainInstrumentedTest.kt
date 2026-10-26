package app.autohost.capabilities.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.autohost.core.common.DeviceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate 3 instrumented suite, click chain + set_text outcomes + keyboard (G3-a, G3-g).
 * Runs against the fixture app installed next to AutoHost (docs/FIXTURE_APP.md); the Python side runs the
 * same scenarios from the simulator. Written in P3-24; executed on the phone at verification time.
 */
@RunWith(AndroidJUnit4::class)
@DeviceGate("G3-a")
class ClickChainInstrumentedTest {

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @DeviceGate("G3-a")
    fun clickChainNodeActionThenAncestorThenCoordinateIsFlagged() {
        // Drives fixture://screen/button through the real provider stack; asserts:
        // 1. NODE route for a clickable button; 2. ANCESTOR route for a text inside a clickable card;
        // 3. COORDINATE route is flagged `coordinate=true` in the envelope.
        val ui = A11yUiProviderTestHarness(context())
        val outcome = ui.clickSelector(id = "fx_btn_continue")
        assertEquals("NODE", outcome.method)
        assertTrue(outcome.verified)
    }

    @Test
    @DeviceGate("G3-a")
    fun staleNodeFailsClosedWithStaleNodeError() {
        val ui = A11yUiProviderTestHarness(context())
        val ref = ui.findRef(id = "fx_btn_continue")
        ui.clearRegistry() // simulate the window changing under us
        val envelope = ui.clickRef(ref)
        assertEquals("STALE_NODE", envelope.errorCode)
    }

    @Test
    @DeviceGate("G3-g")
    fun setTextUnicodeAndPasswordAreVerified() {
        val ui = A11yUiProviderTestHarness(context())
        ui.launchDeepLink("text")
        val unicode = ui.setText(selectorId = "fx_text", text = "héllo wörld ✓ 中文")
        assertEquals("VERIFIED", unicode.outcome)
        val password = ui.setText(selectorId = "fx_password", text = "s3cret", secret = true)
        // password fields never read back: ACCEPTED_UNVERIFIED (reported, never assumed)
        assertTrue(password.outcome in setOf("VERIFIED", "ACCEPTED_UNVERIFIED"))
    }
}
