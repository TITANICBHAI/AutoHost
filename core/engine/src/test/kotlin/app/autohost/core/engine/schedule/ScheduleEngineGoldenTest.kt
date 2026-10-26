package app.autohost.core.engine.schedule

import app.autohost.core.common.FakeClock
import app.autohost.core.model.MisfirePolicy
import app.autohost.core.model.ScheduleRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Python reference (sdk-python/autohost_ref/schedule.py) produced
 * testing/golden/schedule_vectors.json; this Kotlin engine must agree with it case by case
 * (same contract as the compact-dump golden test in :core:model).
 */
class ScheduleEngineGoldenTest {

    private val engine = ScheduleEngine(FakeClock())

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private fun state(o: JsonObject?): ScheduleState = ScheduleState(
        runsTotal = o?.get("runs_total")?.jsonPrimitive?.long ?: 0,
        runsToday = o?.get("runs_today")?.jsonPrimitive?.int ?: 0,
        dayKey = o?.get("day_key")?.jsonPrimitive?.content,
        consecutiveFailures = o?.get("consecutive_failures")?.jsonPrimitive?.int ?: 0,
        lastRunEndMs = o?.get("last_run_end_ms")?.jsonPrimitive?.longOrNull,
    )

    private fun ruleJson(raw: JsonObject): String {
        // vectors store the wire form; the engine parses the same JSON the Python parse_rule accepts
        return raw.toString()
    }

    @Test fun `every golden case matches the python reference`() {
        val doc = Json.parseToJsonElement(repoFile("testing/golden/schedule_vectors.json").readText()).jsonObject
        val cases = doc.getValue("cases").jsonArray
        assertTrue("no golden cases found", cases.isNotEmpty())
        for (c in cases) {
            val case = c.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            when (case.getValue("kind").jsonPrimitive.content) {
                "next" -> {
                    val rule = ScheduleRule.fromJson(ruleJson(case.getValue("rule").jsonObject))
                        ?: error("case $name: rule failed to parse")
                    val expected = (case["expected"] as? JsonPrimitive)?.longOrNull
                    val after = case.getValue("after_ms").jsonPrimitive.long
                    val state = state(case["state"]?.jsonObject)
                    assertEquals("case $name: next_fire", expected, engine.nextFire(rule, after, state))
                    val next5 = case["expected_next5"]
                    if (next5 is JsonArray) {
                        assertEquals(
                            "case $name: next_fires(5)",
                            next5.map { it.jsonPrimitive.long },
                            engine.nextFires(rule, after, state, 5),
                        )
                    }
                }
                "misfire" -> {
                    val missed = case.getValue("missed").jsonArray.map { it.jsonPrimitive.long }
                    val policyEl = case.getValue("policy")
                    val policy: MisfirePolicy = when {
                        policyEl is JsonPrimitive && policyEl.isString -> when (policyEl.content) {
                            "skip" -> MisfirePolicy.Skip
                            else -> MisfirePolicy.RunOnce
                        }
                        else -> MisfirePolicy.CatchUp(
                            ((policyEl as JsonObject).getValue("catch_up").jsonPrimitive).int,
                        )
                    }
                    assertEquals("case $name: misfire", case.getValue("expected").jsonArray.map { it.jsonPrimitive.long },
                        MisfireResolver.resolve(policy, missed))
                }
                "fanout" -> {
                    val rule = ScheduleRule(
                        id = "fanout", task = "t", type = "interval", everyMinutes = 30,
                        profileIds = case.getValue("profile_ids").jsonArray.map { it.jsonPrimitive.content },
                        fanout = app.autohost.core.model.FanOut(
                            mode = case.getValue("mode").jsonPrimitive.content,
                            staggerSeconds = case.getValue("stagger_seconds").jsonPrimitive.int,
                        ),
                    )
                    val due = case.getValue("due_ms").jsonPrimitive.long
                    assertEquals(
                        "case $name: fanout",
                        case.getValue("expected").jsonArray.map { p ->
                            val pair = p.jsonArray
                            (pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.long)
                        },
                        engine.fanOut(rule, due).map { it.profileId to it.dueAtMs },
                    )
                }
                "conditions" -> {
                    val conditions = if (case.getValue("conditions") is JsonNull) {
                        app.autohost.core.model.ScheduleConditions()
                    } else {
                        Json.decodeFromString(
                            app.autohost.core.model.ScheduleConditions.serializer(),
                            case.getValue("conditions").jsonObject.toString(),
                        )
                    }
                    val env = case.getValue("env").jsonObject
                    val ok = case.getValue("expected_ok").jsonPrimitive.boolean
                    val reason = (case["expected_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    val outcome = engine.evaluateConditions(
                        conditions,
                        ConditionEnv(
                            nowMs = env.getValue("now_ms").jsonPrimitive.long,
                            charging = env["charging"]?.jsonPrimitive?.booleanOrNull ?: false,
                            batteryPct = env["battery_pct"]?.jsonPrimitive?.int ?: 100,
                            unmetered = env["unmetered"]?.jsonPrimitive?.booleanOrNull ?: true,
                            screenAwake = env["screen_awake"]?.jsonPrimitive?.booleanOrNull ?: false,
                            screenUnlocked = env["screen_unlocked"]?.jsonPrimitive?.booleanOrNull ?: false,
                        ),
                    )
                    assertEquals("case $name: conditions ok", ok, outcome.ok)
                    assertEquals("case $name: conditions reason", reason, outcome.failedReason)
                }
            }
        }
    }
}
