package app.autohost.runtime.agent

// Adapted from closepaw tool/PolicyEngine.kt (Apache-2.0, Copyright 2026 ClosePaw Authors).
// Changes: the approval MODES collapse into ADR-091 (7)'s Stage-A rule — the model is untrusted,
// so (spec-risk floor, sensitivity, action kind, label hints) → allow / ask / deny with no user
// mode dial; the session allow-list is package-scoped and only ever skips the SENSITIVITY ask
// (never a label-hint ask, never a deny); the effective tier is the stricter of the current and
// the destination app (upstream's rule, kept); scope and BLOCKED deny before anything asks
// (docs/REUSE_MATRIX.md RM-B2, P13-11).

import kotlinx.serialization.json.JsonObject

/**
 * P13-11 (AGENT_RUNTIME §6 policy column, §10): the tool policy. The canonical order:
 *
 *  1. malformed arguments (the validator failed) → Deny — fail closed
 *  2. host tools (finish/todo_write/wait) and ask_user → Allow
 *  3. non-act tools (observe/query/launch): a BLOCKED destination denies; otherwise Allow
 *     (the observation floor already filtered reads; `app_launch` allows for `p ∈ P` per §6)
 *  4. `ui_press` allows `back` only (the escape rule — the agent must not be trapped)
 *  5. an id-resolved target outside the run's packages P → Deny CAPABILITY_NOT_GRANTED
 *  6. effective sensitivity = stricter of (foreground, target) — BLOCKED → Deny
 *     OBSERVATION_BLOCKED
 *  7. `set_text` on a password field → Deny PASSWORD_FIELD (the agent never types secrets)
 *  8. label hints on the click family → Ask even in NORMAL apps — BEST-EFFORT and NEVER the
 *     security boundary (scope, grants, BLOCKED apps and supervised completion are)
 *  9. the session allow-list ("Allow for this run") skips ONLY the sensitivity ask
 * 10. click family (ui_click/ui_long_click/ui_set_text): CAUTIOUS → Ask, NORMAL → Allow;
 *     the other act tools (scroll/hide_keyboard/press) allow (§6's policy column)
 *
 * The spec `risk` is a floor, not a verdict: every click is `low` in the spec whatever it
 * clicks, so the floor alone never allows more than the table above.
 */
object ToolPolicy {

    /** Everything the policy needs about one resolved call. */
    data class Input(
        val tool: ToolCatalog.Tool,
        val args: JsonObject?,           // null = the call was malformed (denied before anything else)
        val targetPkg: String?,          // id-resolved target or the explicit package arg; null for selector-only/none
        val foregroundPkg: String?,
        val nodeLabel: String?,          // the target node's label (hints; truncated in ask text)
        val nodeIsPassword: Boolean,
        val sessionAllowed: Set<String>, // "Allow for this run" subjects — transient, never persisted
    )

    data class Context(
        val packages: Set<String>,       // the run's granted packages P
        val sensitivity: AppSensitivity,
    )

    sealed interface Decision {
        data object Allow : Decision

        /** Approval required — the reason is shown to the user (labels truncated to 40 chars). */
        data class Ask(val reason: String) : Decision

        /** [code] is a brokerError code; [reason] a reasonCode (null when none fits). */
        data class Deny(val code: String, val reason: String?, val message: String) : Decision
    }

    fun check(input: Input, context: Context): Decision {
        if (input.args == null) {
            return Decision.Deny("INVALID_ARGS", null, "the call had no parseable arguments")
        }
        // 2. host tools and ask_user
        if (input.tool.capability == null || input.tool.name == "ask_user") return Decision.Allow

        val destinationBlocked = input.targetPkg != null && context.sensitivity.isBlocked(input.targetPkg)
        // 3. non-act tools: reads are already floor-filtered; launch/query deny BLOCKED destinations
        if (input.tool.permission != "device.ui.act") {
            if (destinationBlocked) {
                return Decision.Deny(
                    "PERMISSION_DENIED", "OBSERVATION_BLOCKED",
                    "'${input.targetPkg}' is on the blocked list",
                )
            }
            return Decision.Allow
        }
        // 4. escape: back only (the catalog enum already restricts the key; defense in depth)
        if (input.tool.name == "ui_press") {
            val key = input.args.str("key")
            if (key != "back") {
                return Decision.Deny("INVALID_ARGS", null, "only key=back is allowed in Stage A")
            }
            return Decision.Allow
        }
        // 5. the target must be inside the run's packages (the id-carrying defence in §5.2 (4))
        if (input.targetPkg != null && input.targetPkg !in context.packages) {
            return Decision.Deny(
                "PERMISSION_DENIED", "CAPABILITY_NOT_GRANTED",
                "the target app '${input.targetPkg}' is outside this run's packages",
            )
        }
        // 6. the stricter of current vs destination wins (the upstream rule)
        val effectiveTier = stricterOf(input.foregroundPkg, input.targetPkg, context.sensitivity)
        if (effectiveTier == AppTier.BLOCKED) {
            return Decision.Deny(
                "PERMISSION_DENIED", "OBSERVATION_BLOCKED",
                "the blocked app is on screen",
            )
        }
        // 7. never type into a password field
        if (input.tool.name == "ui_set_text" && input.nodeIsPassword) {
            return Decision.Deny(
                "TEXT_INPUT_REJECTED", "PASSWORD_FIELD",
                "that field is a password field — the agent never types secrets",
            )
        }
        // 8. label hints — best-effort, ask-only, never a deny and never skipped by the session list
        if (isClickFamily(input.tool) && labelHintHit(input.nodeLabel)) {
            return Decision.Ask(
                "the control is labelled \"${clip(input.nodeLabel!!)}\" — confirm this action",
            )
        }
        // 9/10. the click family asks in CAUTIOUS apps; the session allow-list skips that ask only
        if (isClickFamily(input.tool)) {
            val subject = input.targetPkg ?: input.foregroundPkg
            if (subject != null && subject in input.sessionAllowed) return Decision.Allow
            if (effectiveTier == AppTier.CAUTIOUS) {
                return Decision.Ask("unknown app — action requires approval")
            }
        }
        return Decision.Allow
    }

    /** The label-hint vocabulary (§6); English-first v1, other languages UNVERIFIED. */
    val HINT_WORDS = listOf(
        "pay", "send", "delete", "remove", "uninstall", "buy", "order",
        "confirm", "submit", "transfer", "post",
    )

    fun labelHintHit(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        return label.lowercase().split(Regex("[^a-z]+")).any { token ->
            token.isNotEmpty() && HINT_WORDS.any { hint -> token.startsWith(hint) }
        }
    }

    private fun isClickFamily(tool: ToolCatalog.Tool): Boolean =
        tool.name == "ui_click" || tool.name == "ui_long_click" || tool.name == "ui_set_text"

    /**
     * The stricter tier of the two apps (BLOCKED < CAUTIOUS < NORMAL by ordinal). An UNKNOWN
     * foreground is CAUTIOUS (the upstream fail-safe: classify(null) never says NORMAL); a
     * target without a resolved package (selector-only) inherits the foreground's tier — the
     * selector resolves inside the current screen.
     */
    private fun stricterOf(foreground: String?, target: String?, sensitivity: AppSensitivity): AppTier {
        val fgTier = foreground?.let { sensitivity.classify(it) } ?: AppTier.CAUTIOUS
        val targetTier = if (target != null) sensitivity.classify(target) else fgTier
        return minOf(fgTier, targetTier)
    }

    private fun clip(label: String): String =
        if (label.length <= 40) label else label.take(39) + "…"
}

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
