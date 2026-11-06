package app.autohost.capabilities.a11y

/**
 * X-20 (ADR-100): the idle-cost guard for the accessibility service's EVENT path — pure and
 * JVM-tested (the phone CPU sample is the D-25-skipped half).
 *
 * Per event the service does two kinds of work:
 *
 *  - **Counted always** (cheap, and it IS the settle machinery): the window-change counter
 *    (`noteWindowChange`) and its flow emission — `wait_for_settle` is built on them. The
 *    recorder tap check is armed-only and never blocks the path.
 *  - **The expensive tree consult** (`consultAutoAccept`: windows() + `UiTreeBuilder.build` to
 *    match a known dialog) — allowed ONLY while a host flow is pending or a run is active.
 *    With nothing running the service builds NO tree: events are counted, not processed.
 *
 * The worker processes (`:w*`) and the Python interpreter are lazy by construction — they exist
 * only while a run needs them (the orchestrator spawns per run; Chaquopy loads on first use) —
 * that laziness is enforced by X-19's architecture conformance suite, not here.
 */
object A11yIdlePolicy {

    fun mayConsultTree(pendingFlow: AutoAcceptPolicy.Flow, runActive: Boolean): Boolean =
        pendingFlow != AutoAcceptPolicy.Flow.NONE || runActive

    /** The cheap counters never stop — they are what `wait_for_settle` and the idle watchdog read. */
    fun countsEventsWhileIdle(): Boolean = true
}
