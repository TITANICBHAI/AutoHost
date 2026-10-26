package app.autohost.core.engine.surfaces

/**
 * P7-10: the exported `RUN_TASK` intent contract (plan §10.3 "External triggers"; §14 "minimal
 * exported components (token-guarded RUN_TASK)"). Other apps (e.g. Tasker) start a task by firing
 *
 *     am broadcast -n <pkg>/.surfaces.RunTaskReceiver -a app.autohost.RUN_TASK \
 *         --es token <per-install-token> --es task <taskId> [--es profile <profileId>] \
 *         [--es stage <stage>] [--es payload <json-object>]
 *
 * P8-03: alternatively `--es trigger <name>` fires every enabled `external` trigger rule bound to
 * that name (plan §11.9) instead of starting one task directly. The two forms are exclusive;
 * both are token-guarded exactly the same way.
 *
 * The token is per-install (generated on first use, rotatable in Surfaces settings; it is NOT the
 * Dev API token and grants nothing beyond starting runs). Validation is fail-closed and constant-
 * time for the token compare; everything else is identifier-shaped or rejected.
 */
object ExternalRunRequest {

    const val ACTION = "app.autohost.RUN_TASK"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_TASK = "task"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_STAGE = "stage"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_TRIGGER = "trigger" // P8-03: named external trigger (mutually exclusive with task)

    /** Result codes the receiver reports (ordered-broadcast style; visible to senders that ask). */
    const val RESULT_STARTED = 0
    const val RESULT_REJECTED = 1
    const val RESULT_BAD_TOKEN = 2
    const val RESULT_TASK_NOT_RUNNABLE = 3

    sealed interface Outcome {
        /** Token OK and fields valid; the receiver proceeds to start the run (or fire the trigger). */
        data class Accepted(
            val taskId: String,
            val profileId: String?,
            val stage: String?,
            val payload: kotlinx.serialization.json.JsonObject,
            /** P8-03: non-null → fire the named `external` trigger rules instead of [taskId]. */
            val triggerName: String? = null,
        ) : Outcome

        /** Rejection with a machine reason; the receiver logs and reports the code. */
        data class Rejected(val reason: String) : Outcome
    }

    fun parse(
        token: String?,
        task: String?,
        profile: String?,
        stage: String?,
        payloadJson: String?,
        expectedToken: String?,
        triggerName: String? = null,
    ): Outcome {
        if (expectedToken.isNullOrEmpty()) return Outcome.Rejected("NO_TOKEN_CONFIGURED")
        if (token.isNullOrEmpty()) return Outcome.Rejected("MISSING_TOKEN")
        if (!constantTimeEquals(token, expectedToken)) return Outcome.Rejected("BAD_TOKEN")
        // P8-03: the trigger form needs no task; the direct form requires one. Both at once is a
        // sender bug — reject instead of guessing.
        if (!triggerName.isNullOrEmpty() && !task.isNullOrEmpty()) return Outcome.Rejected("TRIGGER_AND_TASK")
        if (triggerName.isNullOrEmpty() && task.isNullOrEmpty()) return Outcome.Rejected("MISSING_TASK")
        if (!triggerName.isNullOrEmpty() && !triggerName.matches(IDENTIFIER)) return Outcome.Rejected("BAD_TRIGGER_NAME")
        if (task.isNullOrEmpty()) return Outcome.Rejected("MISSING_TASK")
        if (!task.matches(IDENTIFIER)) return Outcome.Rejected("BAD_TASK_ID")
        if (!profile.isNullOrEmpty() && !profile.matches(IDENTIFIER)) return Outcome.Rejected("BAD_PROFILE_ID")
        if (!stage.isNullOrEmpty() && !stage.matches(IDENTIFIER)) return Outcome.Rejected("BAD_STAGE")
        val payload: kotlinx.serialization.json.JsonObject = if (payloadJson.isNullOrEmpty()) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        } else {
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(payloadJson) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: return Outcome.Rejected("BAD_PAYLOAD")
        }
        return Outcome.Accepted(
            taskId = task,
            profileId = profile?.takeIf { it.isNotEmpty() },
            stage = stage?.takeIf { it.isNotEmpty() },
            payload = payload,
            triggerName = triggerName?.takeIf { it.isNotEmpty() },
        )
    }

    /** Length-independent comparison so timing cannot probe the token byte by byte. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private val IDENTIFIER = Regex("[a-zA-Z][a-zA-Z0-9._-]*")
}
