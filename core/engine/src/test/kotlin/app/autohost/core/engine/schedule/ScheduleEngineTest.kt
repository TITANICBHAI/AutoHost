package app.autohost.core.engine.schedule

import app.autohost.core.common.FakeClock
import app.autohost.core.model.FanOut
import app.autohost.core.model.ScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * P4-16: fake-clock suite for the schedule engine (plan §18 P4-16, Gate 4-a). Every rule type,
 * limit, condition and misfire policy is driven through a controllable clock — no sleeps, no
 * wall time. The Python reference pins identical expectations (autohost_ref/schedule.py); the
 * shared golden vectors live in ScheduleEngineGoldenTest.
 */
class ScheduleEngineTest {

    private val clock = FakeClock(startMs = T0)
    private val engine = ScheduleEngine(clock)

    private fun rule(vararg overrides: Pair<String, Any?>): ScheduleRule {
        val json = buildString {
            append("{")
            append("\"id\":\"sch_test\",\"task\":\"t\"")
            for ((k, v) in overrides) {
                append(",\"$k\":")
                append(jsonValue(v))
            }
            append("}")
        }
        return ScheduleRule.fromJson(json)!!
    }

    private fun jsonValue(v: Any?): String = when (v) {
        is String -> "\"$v\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, mv) -> "\"$k\":${jsonValue(mv)}" }
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        else -> "null"
    }

    // ---- interval ------------------------------------------------------------

    @Test fun `interval fixed grid anchors at rule anchor`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z")
        assertEquals(T0 + 30 * M, engine.nextFire(r, T0))
        assertEquals(ANCHOR + 60 * M, engine.nextFire(r, ANCHOR + 30 * M))
    }

    @Test fun `interval without anchor starts after`() {
        val r = rule("type" to "interval", "every_minutes" to 15)
        assertEquals(T0 + 15 * M, engine.nextFire(r, T0))
    }

    @Test fun `interval previous_end mode falls back to grid when stale`() {
        val r = rule("type" to "interval", "every_minutes" to 10, "from" to "previous_end",
            "anchor" to "2026-10-25T00:00:00Z")
        val fresh = engine.nextFire(r, T0, ScheduleState(lastRunEndMs = T0 - 2 * M))
        assertEquals(T0 + 8 * M, fresh)
        val stale = engine.nextFire(r, T0, ScheduleState(lastRunEndMs = T0 - 3 * H))
        assertEquals(ANCHOR + ((T0 - ANCHOR) / (10 * M) + 1) * 10 * M, stale)
    }

    @Test fun `limits end the rule honestly`() {
        val endAt = rule("type" to "interval", "every_minutes" to 30,
            "anchor" to "2026-10-25T00:00:00Z", "limits" to mapOf("end_at" to "2026-10-25T15:15:00Z"))
        assertNull(engine.nextFire(endAt, T0))

        val maxTotal = rule("type" to "interval", "every_minutes" to 30,
            "anchor" to "2026-10-25T00:00:00Z", "limits" to mapOf("max_total_runs" to 5))
        assertNull(engine.nextFire(maxTotal, T0, ScheduleState(runsTotal = 5)))
        assertNotNull(engine.nextFire(maxTotal, T0, ScheduleState(runsTotal = 4)))

        val stopAfter = rule("type" to "interval", "every_minutes" to 30,
            "anchor" to "2026-10-25T00:00:00Z", "limits" to mapOf("stop_after_consecutive_failures" to 3))
        assertNull(engine.nextFire(stopAfter, T0, ScheduleState(consecutiveFailures = 3)))
        assertNotNull(engine.nextFire(stopAfter, T0, ScheduleState(consecutiveFailures = 2)))
    }

    @Test fun `per day limit blocks only the counter day`() {
        val r = rule("type" to "interval", "every_minutes" to 30,
            "anchor" to "2026-10-25T00:00:00Z", "limits" to mapOf("max_runs_per_day" to 4))
        assertEquals(ANCHOR + D, engine.nextFire(r, T0, ScheduleState(runsToday = 4, dayKey = "2026-10-25")))
        assertEquals(T0 + 30 * M, engine.nextFire(r, T0, ScheduleState(runsToday = 4, dayKey = "2026-10-24")))
    }

    // ---- daily / weekly ------------------------------------------------------

    @Test fun `daily picks the next time in the rule timezone`() {
        val r = rule("type" to "daily", "times" to listOf("09:00", "18:30"), "timezone" to "Asia/Kolkata")
        val fire = engine.nextFire(r, T0)!!
        val kolkata = ZoneOffset.ofHoursMinutes(5, 30)
        val local = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(fire), kolkata)
        // T0 is 20:30 IST -> both of today's times passed -> tomorrow 09:00 IST
        assertEquals("2026-10-26T09:00", local.toString().take(16))
    }

    @Test fun `weekly skips to the next chosen day`() {
        val r = rule("type" to "weekly", "days" to listOf(1, 3, 5), "times" to listOf("08:15"), "timezone" to "UTC")
        val fire = engine.nextFire(r, T0)!! // Sunday -> Monday
        assertEquals("2026-10-26T08:15", utcIso(fire))
    }

    @Test fun `preview respects per day limit with fresh future days`() {
        val r = rule("type" to "daily", "times" to listOf("09:00", "10:00", "11:00"), "timezone" to "UTC",
            "limits" to mapOf("max_runs_per_day" to 2))
        val fires = engine.nextFires(r, ANCHOR, ScheduleState(runsToday = 0, dayKey = "2026-10-25"), 5)
        assertEquals(
            listOf("2026-10-25T09:00", "2026-10-25T10:00", "2026-10-26T09:00", "2026-10-26T10:00", "2026-10-27T09:00"),
            fires.map { utcIso(it) },
        )
    }

    // ---- cron ----------------------------------------------------------------

    @Test fun `cron hour range and weekday restriction`() {
        val r = rule("type" to "cron", "cron" to "0 9-17 * * 1-5", "timezone" to "UTC")
        val fire = engine.nextFire(r, T0)!! // Sunday 15:00 -> Monday 09:00
        assertEquals("2026-10-26T09:00", utcIso(fire))
    }

    @Test fun `cron unrestricted dom does not make dow fire daily`() {
        // "30 4 * * 7": dom is * -> ignored; fires only on Sundays
        val r = rule("type" to "cron", "cron" to "30 4 * * 7", "timezone" to "UTC")
        assertEquals("2026-11-01T04:30", utcIso(engine.nextFire(r, T0)!!))
    }

    @Test fun `cron dom and dow restricted together mean OR`() {
        val r = rule("type" to "cron", "cron" to "0 12 13 * 1", "timezone" to "UTC")
        val fires = engine.nextFires(r, ANCHOR, ScheduleState(), 5)
        assertEquals(
            listOf("2026-10-26T12:00", "2026-11-02T12:00", "2026-11-09T12:00", "2026-11-13T12:00", "2026-11-16T12:00"),
            fires.map { utcIso(it) },
        )
    }

    // ---- once / window -------------------------------------------------------

    @Test fun `once fires once and is done`() {
        val future = rule("type" to "once", "at" to "2026-11-01T09:00:00+05:30")
        // 2026-11-01T03:30Z = ANCHOR + 7 days + 3.5 h
        assertEquals(ANCHOR + 7 * D + 12600000L, engine.nextFire(future, T0))
        val past = rule("type" to "once", "at" to "2026-10-20T09:00:00+05:30")
        assertNull(engine.nextFire(past, T0))
    }

    @Test fun `window grid anchors at window start`() {
        val r = rule("type" to "window", "start" to "09:00", "end" to "17:00", "every_minutes" to 120,
            "timezone" to "UTC")
        assertEquals("2026-10-26T09:00", utcIso(engine.nextFire(r, T0)!!))
        val fires = engine.nextFires(r, T0, ScheduleState(), 3)
        assertEquals(listOf("09:00", "11:00", "13:00"), fires.map { utcIso(it).takeLast(5) })
    }

    // ---- fan-out -------------------------------------------------------------

    @Test fun `fanout modes order and stagger profiles`() {
        val base = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z")
        val sequential = base.copy(profileIds = listOf("p1", "p2"), fanout = FanOut("sequential"))
        assertEquals(listOf("p1" to T0 + 30 * M, "p2" to T0 + 30 * M), engine.fanOut(sequential, T0 + 30 * M)
            .map { it.profileId to it.dueAtMs })
        val staggered = base.copy(profileIds = listOf("p1", "p2"), fanout = FanOut("staggered", 30))
        assertEquals(
            listOf("p1" to T0, "p2" to T0 + 30_000),
            engine.fanOut(staggered, T0).map { it.profileId to it.dueAtMs },
        )
        val parallel = base.copy(profileIds = listOf("p1", "p2"), fanout = FanOut("parallel"))
        assertEquals(2, engine.fanOut(parallel, T0).count { it.dueAtMs == T0 })
    }

    // ---- conditions ----------------------------------------------------------

    @Test fun `conditions report the first failed reason`() {
        val env = ConditionEnv(nowMs = ANCHOR + 10 * H, charging = true, batteryPct = 55, unmetered = true)
        assertEquals(ConditionOutcome.PASS, engine.evaluateConditions(
            app.autohost.core.model.ScheduleConditions(charging = true, batteryMin = 30), env))
        assertEquals("charging", engine.evaluateConditions(
            app.autohost.core.model.ScheduleConditions(charging = true),
            env.copy(charging = false)).failedReason)
        assertEquals("battery_min", engine.evaluateConditions(
            app.autohost.core.model.ScheduleConditions(batteryMin = 30), env.copy(batteryPct = 10)).failedReason)
        assertEquals("unmetered", engine.evaluateConditions(
            app.autohost.core.model.ScheduleConditions(unmetered = true), env.copy(unmetered = false)).failedReason)
    }

    @Test fun `condition window checks day and time`() {
        val clock2 = FakeClock(startMs = ANCHOR + 10 * H)
        val e = ScheduleEngine(clock2)
        val inside = app.autohost.core.model.ScheduleConditions(
            window = app.autohost.core.model.ScheduleConditions.TimeWindow("09:00", "17:00", emptyList(), "UTC"))
        assertEquals(ConditionOutcome.PASS, e.evaluateConditions(inside, ConditionEnv(nowMs = clock2.nowMs())))
        val mondayOnly = inside.copy(window = inside.window!!.copy(days = listOf(1)))
        assertEquals("window_day", e.evaluateConditions(mondayOnly, ConditionEnv(nowMs = clock2.nowMs())).failedReason)
        val night = inside.copy(window = inside.window!!.copy(start = "00:00", end = "08:00"))
        assertEquals("window_time", e.evaluateConditions(night, ConditionEnv(nowMs = clock2.nowMs())).failedReason)
    }

    // ---- misfire -------------------------------------------------------------

    @Test fun `misfire resolver ranks missed fires per policy`() {
        val missed = listOf(T0 - 3 * H, T0 - 2 * H, T0 - H)
        assertEquals(emptyList<Long>(), MisfireResolver.resolve(app.autohost.core.model.MisfirePolicy.Skip, missed))
        assertEquals(listOf(T0 - H), MisfireResolver.resolve(app.autohost.core.model.MisfirePolicy.RunOnce, missed))
        assertEquals(listOf(T0 - 3 * H, T0 - 2 * H),
            MisfireResolver.resolve(app.autohost.core.model.MisfirePolicy.CatchUp(2), missed))
    }

    @Test fun `missed fires enumeration is bounded and rule-faithful`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z")
        val missed = MisfireResolver.missedFires(engine, r, T0 - 4 * H, T0 - 10 * M)
        assertEquals(8, missed.size) // 11:00..14:30, including the persisted next fire.
        assertEquals(T0 - 4 * H, missed.first())
    }

    @Test fun `reconcile policy arm-only when alarm missing but schedule healthy`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z")
        val decision = ReconcilePolicy.decide(
            r, engine, ScheduleState(),
            ReconcileInput(nowMs = T0, armedFireId = null, armedAtMs = null,
                nextFireAtMs = T0 + 30 * M, exactAlarmsAllowed = true, backendKind = "alarm"),
        )
        assertEquals(ReconcileDecision.Rearm, decision)
    }

    @Test fun `reconcile policy healthy when alarm armed and future`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z")
        val decision = ReconcilePolicy.decide(
            r, engine, ScheduleState(),
            ReconcileInput(nowMs = T0, armedFireId = "f1", armedAtMs = T0 - 60_000,
                nextFireAtMs = T0 + 30 * M, exactAlarmsAllowed = true, backendKind = "alarm"),
        )
        assertEquals(ReconcileDecision.Healthy, decision)
    }

    @Test fun `reconcile policy applies misfire policy to overdue fires`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z",
            "misfire" to mapOf("catch_up" to 2))
        val decision = ReconcilePolicy.decide(
            r, engine, ScheduleState(),
            ReconcileInput(nowMs = T0 + 6 * H, armedFireId = null, armedAtMs = null,
                nextFireAtMs = T0 + 30 * M, exactAlarmsAllowed = true, backendKind = "work"),
        )
        val missed = decision as ReconcileDecision.Missed
        assertEquals("catch_up(2)", missed.policyName)
        assertEquals(2, missed.fires.size)
        assertEquals(T0 + 30 * M, missed.fires.first())
    }

    @Test fun `reconcile policy skip drops missed fires and re-arms`() {
        val r = rule("type" to "interval", "every_minutes" to 30, "anchor" to "2026-10-25T00:00:00Z",
            "misfire" to "skip")
        val decision = ReconcilePolicy.decide(
            r, engine, ScheduleState(),
            ReconcileInput(nowMs = T0 + 6 * H, armedFireId = null, armedAtMs = null,
                nextFireAtMs = T0 + 30 * M, exactAlarmsAllowed = true, backendKind = "work"),
        )
        val missed = decision as ReconcileDecision.Missed
        assertEquals("skip", missed.policyName)
        assertEquals(emptyList<Long>(), missed.fires)
    }

    @Test fun `reconcile finished when the rule is exhausted`() {
        val r = rule("type" to "once", "at" to "2026-10-25T14:00:00Z")
        val decision = ReconcilePolicy.decide(
            r, engine, ScheduleState(),
            ReconcileInput(nowMs = T0, armedFireId = null, armedAtMs = null,
                nextFireAtMs = null, exactAlarmsAllowed = true, backendKind = "alarm"),
        )
        assertEquals(ReconcileDecision.Finished, decision)
    }

    // ---- fake-clock sanity ---------------------------------------------------

    @Test fun `fake clock drives day rollover without sleeps`() {
        val r = rule("type" to "daily", "times" to listOf("15:30"), "timezone" to "UTC")
        clock.advance(31 * M) // 15:31
        assertEquals("2026-10-26T15:30", utcIso(engine.nextFire(r, clock.nowMs())!!))
        assertEquals("2026-10-25", engine.dayKeyFor(r, T0))
    }

    companion object {
        const val M = 60_000L
        const val H = 3_600_000L
        const val D = 86_400_000L
        const val ANCHOR = 1792886400000L // 2026-10-25T00:00:00Z (Sunday)
        const val T0 = 1792940400000L // 2026-10-25T15:00:00Z (Sunday)

        fun utcIso(ms: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
    }
}
