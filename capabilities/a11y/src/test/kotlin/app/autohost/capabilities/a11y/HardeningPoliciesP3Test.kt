package app.autohost.capabilities.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 hardening decisions: TraversalGuard, RootResolver, ReadGatePolicy, SettlePolicy, PopupGuardPolicy,
 *  SetTextChain, AutoAcceptPolicy, TextNormalizer multi-label (P3-01..P3-09, P3-12, P3-15). */
class HardeningPoliciesP3Test {

    // --- TraversalGuard (P3-04) ---------------------------------------------------------------------------------

    @Test fun `traversal guard skips cycles`() {
        val guard = TraversalGuard()
        assertEquals(TraversalGuard.Decision.Record, guard.enter(1, 0))
        assertEquals(TraversalGuard.Decision.Record, guard.enter(2, 1))
        assertEquals(TraversalGuard.Decision.Skip("CYCLE"), guard.enter(1, 2)) // child points at ancestor
        guard.exit(2)
        guard.exit(1)
        assertEquals(TraversalGuard.Decision.Record, guard.enter(1, 0)) // after exit the node is legal again
    }

    @Test fun `traversal guard enforces depth and node budget`() {
        val guard = TraversalGuard(maxDepth = 2, maxNodes = 2)
        assertEquals(TraversalGuard.Decision.Record, guard.enter(1, 0))
        assertEquals(TraversalGuard.Decision.Record, guard.enter(2, 1))
        assertEquals(TraversalGuard.Decision.Skip("DEPTH_CAP"), guard.enter(3, 3))
        assertEquals(TraversalGuard.Decision.Record, guard.enter(3, 2))
        assertEquals(TraversalGuard.Decision.Skip("NODE_BUDGET"), guard.enter(4, 2))
        assertTrue(guard.truncated())
    }

    // --- RootResolver (P3-05) -----------------------------------------------------------------------------------

    private fun window(id: Int, layer: Int, type: String = "application", pkg: String? = "app.x", active: Boolean = false, hasRoot: Boolean = true) =
        RootResolver.WindowMeta(id, layer, type, pkg, active, focused = active, hasRoot)

    @Test fun `resolver picks active window plus higher layers deduped by id`() {
        val windows = listOf(
            window(1, 5, pkg = "com.android.systemui"), // dialog above
            window(2, 10, active = true),
            window(2, 10, active = true), // duplicate id
            window(3, 1),
            window(4, 7, type = "input_method", pkg = "app.autohost"), // keyboard excluded
            window(5, 3, hasRoot = false), // unreadable root
        )
        val decision = RootResolver.resolve(RootResolver.Input(windows, activeRootPkg = null))
        assertTrue(decision is RootResolver.Decision.Read)
        val read = decision as RootResolver.Decision.Read
        assertEquals(listOf(2, 1, 3), read.windows.map { it.id }) // layer order, keyboard dropped, deduped
        assertEquals("app.x", read.primaryPackage)
    }

    @Test fun `resolver falls back to the active root when the window list is empty`() {
        val decision = RootResolver.resolve(RootResolver.Input(emptyList(), activeRootPkg = "app.x"))
        assertTrue(decision is RootResolver.Decision.Read)
        assertEquals("app.x", (decision as RootResolver.Decision.Read).primaryPackage)
    }

    @Test fun `resolver reports no active window when nothing is readable`() {
        assertEquals(RootResolver.Decision.NoActiveWindow, RootResolver.resolve(RootResolver.Input(emptyList(), null)))
        assertEquals(RootResolver.Decision.NoActiveWindow, RootResolver.resolve(RootResolver.Input(listOf(window(1, 1, hasRoot = false)), null)))
    }

    @Test fun `empty tree after read is not an error`() {
        assertEquals("EMPTY_TREE", RootResolver.classifyAfterRead(readWindows = 1, nodeCount = 0))
        assertEquals("NO_READABLE_ROOT", RootResolver.classifyAfterRead(0, 0))
        assertNull(RootResolver.classifyAfterRead(2, 40))
    }

    // --- ReadGatePolicy (P3-06) ---------------------------------------------------------------------------------

    @Test fun `read gate proceeds under budget and saturation`() {
        val policy = ReadGatePolicy(maxConcurrentReads = 2)
        assertTrue(policy.decide(activeReads = 0, elapsedMs = 0) is ReadGatePolicy.Decision.Proceed)
        assertTrue(policy.decide(activeReads = 1, elapsedMs = 2_000) is ReadGatePolicy.Decision.Proceed)
    }

    @Test fun `read gate degrades on saturation and budget exhaustion`() {
        val policy = ReadGatePolicy(maxConcurrentReads = 2, budgetMs = 2_500)
        val saturated = policy.decide(activeReads = 2, elapsedMs = 0)
        assertEquals(ReadGatePolicy.Reason.POOL_SATURATED, (saturated as ReadGatePolicy.Decision.Degrade).reason)
        val exhausted = policy.decide(activeReads = 0, elapsedMs = 3_000)
        assertEquals(ReadGatePolicy.Reason.BUDGET_EXHAUSTED, (exhausted as ReadGatePolicy.Decision.Degrade).reason)
    }

    // --- SettlePolicy (P3-08) ------------------------------------------------------------------------------------

    @Test fun `settle requires quiet window and streak`() {
        assertEquals(SettlePolicy.Decision.Settled, SettlePolicy.evaluate(quietMs = 500, timeoutMs = 5_000, elapsedMs = 900, lastChangedMs = 100, nowMs = 700))
        assertEquals(
            SettlePolicy.Decision.KeepWaiting::class,
            SettlePolicy.evaluate(quietMs = 500, timeoutMs = 5_000, elapsedMs = 100, lastChangedMs = 100, nowMs = 300)::class,
        )
        assertEquals(SettlePolicy.Decision.TimedOut, SettlePolicy.evaluate(quietMs = 500, timeoutMs = 500, elapsedMs = 500, lastChangedMs = 450, nowMs = 500))
    }

    @Test fun `retry only when nothing changed and not already retried`() {
        assertTrue(SettlePolicy.retryIfNoChange(changed = false, alreadyRetried = false))
        assertFalse(SettlePolicy.retryIfNoChange(changed = true, alreadyRetried = false))
        assertFalse(SettlePolicy.retryIfNoChange(changed = false, alreadyRetried = true)) // exactly once
    }

    @Test fun `fingerprint is stable across equal snapshots and differs on change`() {
        val nodes = listOf(app.autohost.core.model.UiNode(0, -1, 0, "app", "Button", "fx", "hi", "", app.autohost.core.model.Bounds(0, 0, 1, 1), emptySet()))
        val moved = nodes.map { it.copy(bounds = it.bounds.copy(t = 5)) }
        assertEquals(SettlePolicy.fingerprint(nodes), SettlePolicy.fingerprint(nodes))
        assertTrue(SettlePolicy.fingerprint(nodes) != SettlePolicy.fingerprint(moved))
    }

    // --- PopupGuardPolicy (P3-09) ---------------------------------------------------------------------------------

    private fun occluder(pkg: String = "com.android.systemui", ids: Set<String> = emptySet(), labels: Set<String> = emptySet()) =
        PopupGuardPolicy.Occluder(9, pkg, "system", ids, labels)

    @Test fun `clear when nothing sits above`() {
        assertEquals(PopupGuardPolicy.Decision.Clear, PopupGuardPolicy.evaluate(3, emptyList(), PopupGuardPolicy.Mode.STOP))
    }

    @Test fun `unknown dialog obscures the target`() {
        val decision = PopupGuardPolicy.evaluate(3, listOf(occluder()), PopupGuardPolicy.Mode.STOP)
        assertTrue(decision is PopupGuardPolicy.Decision.Obscured)
    }

    @Test fun `known dialog dismisses only when policy allows`() {
        val consent = occluder(ids = setOf("com.android.systemui:id/media_projection_yes_button"))
        assertTrue(PopupGuardPolicy.evaluate(3, listOf(consent), PopupGuardPolicy.Mode.STOP) is PopupGuardPolicy.Decision.Obscured)
        assertTrue(PopupGuardPolicy.evaluate(3, listOf(consent), PopupGuardPolicy.Mode.DISMISS_KNOWN) is PopupGuardPolicy.Decision.Dismiss)
    }

    @Test fun `label fallback matches known dialogs across variants`() {
        val byLabel = occluder(labels = setOf("Start now"))
        assertEquals(PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, PopupGuardPolicy.KnownDialog.match("com.android.systemui", emptySet(), byLabel.labels))
        assertNull(PopupGuardPolicy.KnownDialog.match("com.vendor.app", setOf("com.android.systemui:id/media_projection_yes_button"), emptySet()))
    }

    @Test fun `popup guard ignores the keyboard window`() {
        val windows = listOf(
            RootResolver.WindowMeta(1, 1, "application", "app.x", active = true, focused = true, hasRoot = true),
            RootResolver.WindowMeta(2, 9, "input_method", "app.autohost", active = false, focused = false, hasRoot = true),
        )
        val decision = PopupGuard.evaluate(1, windows, { Pair(emptySet(), emptySet()) }, PopupGuardPolicy.Mode.STOP)
        assertEquals(PopupGuardPolicy.Decision.Clear, decision)
    }

    // --- SetTextChain (P3-12) --------------------------------------------------------------------------------------

    @Test fun `chain tries set text then ime then paste`() {
        val first = SetTextChain.next(emptyList(), secret = false, imeAvailable = true)
        assertEquals(SetTextChain.Strategy.SET_TEXT, (first as SetTextChain.Decision.Next).strategy)
        val second = SetTextChain.next(listOf(SetTextChain.Attempt(SetTextChain.Strategy.SET_TEXT, SetTextChain.REJECTED)), secret = false, imeAvailable = true)
        assertEquals(SetTextChain.Strategy.IME_COMMIT, (second as SetTextChain.Decision.Next).strategy)
        val third = SetTextChain.next(
            listOf(
                SetTextChain.Attempt(SetTextChain.Strategy.SET_TEXT, SetTextChain.REJECTED),
                SetTextChain.Attempt(SetTextChain.Strategy.IME_COMMIT, SetTextChain.COMMIT_OUTCOME_UNKNOWN),
            ),
            secret = false,
            imeAvailable = true,
        )
        assertEquals(SetTextChain.Strategy.CLIPBOARD_PASTE, (third as SetTextChain.Decision.Next).strategy)
    }

    @Test fun `secret text never touches the clipboard`() {
        val attempts = listOf(
            SetTextChain.Attempt(SetTextChain.Strategy.SET_TEXT, SetTextChain.REJECTED),
            SetTextChain.Attempt(SetTextChain.Strategy.IME_COMMIT, SetTextChain.COMMIT_OUTCOME_UNKNOWN),
        )
        val decision = SetTextChain.next(attempts, secret = true, imeAvailable = true)
        assertTrue(decision is SetTextChain.Decision.Unverified)
    }

    @Test fun `first verified attempt wins and rejected ends as rejected`() {
        val done = SetTextChain.next(listOf(SetTextChain.Attempt(SetTextChain.Strategy.SET_TEXT, SetTextChain.VERIFIED)), secret = false, imeAvailable = false)
        assertEquals(SetTextChain.VERIFIED, (done as SetTextChain.Decision.Done).attempt.outcome)
        val rejected = SetTextChain.next(listOf(SetTextChain.Attempt(SetTextChain.Strategy.SET_TEXT, SetTextChain.REJECTED)), secret = true, imeAvailable = false)
        assertTrue(rejected is SetTextChain.Decision.Rejected)
    }

    // --- AutoAcceptPolicy (P3-15) ------------------------------------------------------------------------------------

    @Test fun `no pending flow means never click`() {
        val decision = AutoAcceptPolicy().evaluate(AutoAcceptPolicy.Flow.NONE, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, 1_000, -1, emptyList(), 0)
        assertTrue(decision is AutoAcceptPolicy.Decision.StandDown)
    }

    @Test fun `unknown dialogs are never clicked`() {
        val decision = AutoAcceptPolicy().evaluate(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, null, 1_000, -1, emptyList(), 0)
        assertTrue(decision is AutoAcceptPolicy.Decision.StandDown)
    }

    @Test fun `failure cooldown rate limit and give-up protect the user`() {
        val policy = AutoAcceptPolicy(failureCooldownMs = 60_000, maxAcceptsPerMinute = 2, giveUpAfterAttempts = 3)
        assertTrue(policy.evaluate(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, 61_000, lastFailureMs = 1_000, acceptTimestamps = emptyList(), attemptsForFlow = 0) is AutoAcceptPolicy.Decision.Accept)
        assertTrue(policy.evaluate(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, 2_000, lastFailureMs = 1_000, acceptTimestamps = emptyList(), attemptsForFlow = 0) is AutoAcceptPolicy.Decision.StandDown) // cooling down
        assertTrue(policy.evaluate(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, 5_000, -1, listOf(4_000, 4_500), 0) is AutoAcceptPolicy.Decision.StandDown) // rate limited
        assertTrue(policy.evaluate(AutoAcceptPolicy.Flow.MEDIA_PROJECTION, PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT, 5_000, -1, emptyList(), attemptsForFlow = 3) is AutoAcceptPolicy.Decision.StandDown) // gave up
    }

    // --- TextNormalizer multi-label (P3-02) -----------------------------------------------------------------------------

    @Test fun `multi-label matching normalises each label`() {
        val nodes = listOf(
            app.autohost.core.model.UiNode(0, -1, 0, "app", "Button", "fx_b", "Continuar", "", app.autohost.core.model.Bounds(0, 0, 10, 10), emptySet()),
        )
        val multi = app.autohost.core.model.Selector(terms = listOf(app.autohost.core.model.SelectorTerm(text = app.autohost.core.model.Match(listOf("continue", "continuar")))))
        assertEquals(listOf(0), app.autohost.core.model.SelectorEngine.findAll(nodes, multi).map { it.index })
        assertEquals("continuar", app.autohost.core.model.TextNormalizer.normalize("  Continuar "))
    }
}
