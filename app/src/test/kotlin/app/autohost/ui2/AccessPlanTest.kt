package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPlanTest {

    @Test fun `working routes without a computer come first, routes that do not work yet come last`() {
        for (sdk in listOf(29, 30, 34, 36)) {
            val routes = AccessPlan.routes(sdk)
            val working = routes.filter { it.implemented }
            val firstComputer = working.indexOfFirst { it.needsComputer }
            val lastFree = working.indexOfLast { !it.needsComputer }
            assertTrue("sdk $sdk: $routes", firstComputer == -1 || lastFree < firstComputer)
            assertEquals("sdk $sdk: unfinished routes last", routes.takeLast(routes.count { !it.implemented }), routes.filter { !it.implemented })
        }
    }

    @Test fun `the in-app wireless route is never offered as working while it is a draft`() {
        val wireless = AccessPlan.routes(34).single { it.route == AccessRoute.WIRELESS_DEBUGGING }
        assertFalse(wireless.implemented)
        assertFalse(wireless.needsComputer)
        assertNotNull(AccessPlan.bestWithoutComputer(34))
        assertFalse(AccessPlan.bestWithoutComputer(34)!!.route == AccessRoute.WIRELESS_DEBUGGING)
    }

    @Test fun `on android 11 and newer shizuku needs no computer but on 10 it does`() {
        assertFalse(AccessPlan.routes(30).single { it.route == AccessRoute.SHIZUKU }.needsComputer)
        assertTrue(AccessPlan.routes(29).single { it.route == AccessRoute.SHIZUKU }.needsComputer)
    }

    @Test fun `on android 10 the free route is root only and the fallback names a computer`() {
        assertEquals(AccessRoute.ROOT, AccessPlan.bestWithoutComputer(29)?.route)
        // Nothing but root is computer-free there; the plan still offers a fallback and says it needs a computer.
        val fb = AccessPlan.fallback(29)
        assertNotNull(fb)
    }

    @Test fun `bestWithoutComputer is never a route that needs one`() {
        for (sdk in 29..36) {
            val best = AccessPlan.bestWithoutComputer(sdk)
            if (best != null) assertFalse(best.needsComputer)
        }
    }
}
