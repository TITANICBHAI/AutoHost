package app.autohost.core.engine.triggers

import app.autohost.core.engine.events.TriggerEvent
import app.autohost.core.model.TriggerRule
import kotlinx.serialization.json.JsonPrimitive

/**
 * P8-03: pure event↔rule matching (plan §11.9 sources/params). Regexes compile lazily per call
 * and any invalid pattern simply fails to match (`TRIGGER_RULE_INVALID` is the upsert path's job;
 * a broken pattern must never crash the delivery loop).
 */
object EventMatcher {

    fun matches(event: TriggerEvent, rule: TriggerRule): Boolean {
        if (event.kind != rule.source) return false
        return when (rule.source) {
            "notification.posted" -> notification(rule, event, withText = true)
            "notification.removed" -> notification(rule, event, withText = false)
            "app.entered", "app.exited" -> param(rule, "package") == event.pkg
            "battery.low", "battery.okay", "power.connected", "power.disconnected",
            "screen.on", "screen.off", "user.present",
            -> true
            "battery.level" -> {
                val level = event.payload["level"]?.intOrNull() ?: return false
                val op = param(rule, "op") ?: return false
                val percent = param(rule, "percent")?.toIntOrNull() ?: return false
                compareLevel(level, op, percent)
            }
            "network.connected" -> typeFilter(rule, event)
            "network.type_changed" -> typeFilter(rule, event)
            "network.wifi_ssid" -> event.payload["ssid"]?.strOrNull() == param(rule, "ssid")
            "sms.received" -> regexOk(param(rule, "sender_regex"), event.payload["sender"]) &&
                regexOk(param(rule, "body_regex"), event.payload["body"])
            "toast.shown" -> {
                val pkgParam = param(rule, "package")
                (pkgParam == null || pkgParam == event.pkg) &&
                    regexOk(param(rule, "text_regex"), event.payload["text"])
            }
            "external" -> param(rule, "name") == event.payload["name"]?.strOrNull()
            "task.chain" -> {
                val on = param(rule, "on") ?: "any"
                param(rule, "task") == event.payload["task"]?.strOrNull() &&
                    when (on) {
                        "succeeded" -> event.payload["status"]?.strOrNull() == "SUCCEEDED"
                        "failed" -> event.payload["status"]?.strOrNull() in setOf("FAILED", "INTERRUPTED")
                        else -> true
                    }
            }
            else -> false
        }
    }

    private fun notification(rule: TriggerRule, event: TriggerEvent, withText: Boolean): Boolean {
        val pkgParam = param(rule, "package")
        if (pkgParam != null && pkgParam != event.pkg) return false
        if (!regexOk(param(rule, "title_regex"), event.payload["title"])) return false
        if (withText && !regexOk(param(rule, "text_regex"), event.payload["text"])) return false
        return true
    }

    private fun typeFilter(rule: TriggerRule, event: TriggerEvent): Boolean {
        val wanted = param(rule, "type") ?: return true
        return event.payload["type"]?.strOrNull() == wanted
    }

    private fun compareLevel(level: Int, op: String, percent: Int): Boolean = when (op) {
        "lt" -> level < percent
        "lte" -> level <= percent
        "gt" -> level > percent
        "gte" -> level >= percent
        "eq" -> level == percent
        else -> false
    }

    /** A regex param matches when the pattern compiles AND (no value given or the value matches). */
    private fun regexOk(pattern: String?, value: kotlinx.serialization.json.JsonElement?): Boolean {
        if (pattern == null) return true
        val text = value?.strOrNull() ?: return false
        return runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
    }

    private fun param(rule: TriggerRule, key: String): String? =
        (rule.params[key] as? JsonPrimitive)?.content

    private fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
        (this as? JsonPrimitive)?.content?.toIntOrNull()

    private fun kotlinx.serialization.json.JsonElement.strOrNull(): String? =
        (this as? JsonPrimitive)?.content
}
