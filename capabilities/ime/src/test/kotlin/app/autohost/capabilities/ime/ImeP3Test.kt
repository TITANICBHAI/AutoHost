package app.autohost.capabilities.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImeSelector mapping + TextInputResult outcome values stay inside schemas/errors.json (P3-11). */
class ImeP3Test {

    @Test fun `ime selector recognises our service id`() {
        assertTrue(ImeSelector.isAutoHostSelected("app.autohost/app.autohost.capabilities.ime.AutoHostKeyboardService"))
        assertTrue(ImeSelector.isAutoHostSelected("some.pkg/app.autohost.capabilities.ime.AutoHostKeyboardService"))
        assertFalse(ImeSelector.isAutoHostSelected("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"))
        assertFalse(ImeSelector.isAutoHostSelected(null))
    }

    @Test fun `text input outcomes come from the schema vocabulary only`() {
        val known = setOf(
            TextInputResult.VERIFIED, TextInputResult.REJECTED, TextInputResult.ACCEPTED_UNVERIFIED,
            TextInputResult.COMMIT_OUTCOME_UNKNOWN, TextInputResult.INPUT_SESSION_CHANGED,
        )
        assertTrue(known.containsAll(TextInputResult::class.java.declaredFields.filter { it.type == String::class.java }.map { it.isAccessible = true; it.get(null) as String }))
    }

    @Test fun `default result reports unknown outcome with detail`() {
        val result = TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "IME not connected")
        assertEquals("ime_commit", result.strategy)
        assertEquals("IME not connected", result.detail)
    }
}
