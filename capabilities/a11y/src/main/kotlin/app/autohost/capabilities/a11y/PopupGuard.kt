package app.autohost.capabilities.a11y

/**
 * Pure popup-guard policy (plan §5.7): before acting on a node, check whether a higher-layer window sits
 * above the target's window (system dialog, permission prompt, ANR). Decide between tapping blindly (never),
 * raising `OBSCURED_BY_WINDOW`, or dismissing a *known* dialog when the task's `dismiss_known_dialogs`
 * policy allows it.
 */
object PopupGuardPolicy {

    /** Task-level policy from the manifest/config; default is to stop honestly. */
    enum class Mode { STOP, DISMISS_KNOWN }

    /** What kind of window is in the way. */
    data class Occluder(
        val windowId: Int,
        val pkg: String,
        val type: String, // "application" | "system" | "accessibility_overlay" | …
        /** Resource ids visible in the occluding window (best effort, may be empty). */
        val ids: Set<String> = emptySet(),
        /** Visible labels / descriptions in the occluding window (best effort, may be empty). */
        val labels: Set<String> = emptySet(),
    )

    sealed class Decision {
        /** Nothing above the target: act normally. */
        object Clear : Decision() {
            override fun toString(): String = "Clear"
        }

        /** A known system dialog is in the way and the policy allows dismissing it. */
        data class Dismiss(val dialog: KnownDialog, val occluder: Occluder) : Decision()

        /** Anything else: refuse the action with OBSCURED_BY_WINDOW. */
        data class Obscured(val detail: String) : Decision()
    }

    /**
     * Known system dialogs we may auto-dismiss. Matching is conservative: the window package must be a
     * system package AND one of the dialog's view ids or localized labels must be present. View ids are
     * device-verified in spike S3 (docs/SPIKES.md); variants (single-step, spinner/dropdown, inline radio)
     * are covered by listing every button id used across API levels.
     */
    enum class KnownDialog(val ids: Set<String>, val labels: Set<String>) {
        MEDIA_PROJECTION_CONSENT(
            ids = setOf(
                "com.android.systemui:id/media_projection_yes_button",
                "com.android.systemui:id/positive_button",
            ),
            labels = setOf("start now", "start recording", "iniciar ahora", "comenzar ahora"),
        ),
        RUNTIME_PERMISSION(
            ids = setOf(
                "com.android.permissioncontroller:id/permission_allow_button",
                "com.android.packageinstaller:id/permission_allow_button",
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            ),
            labels = setOf("allow", "while using the app", "permitir"),
        ),
        APP_INFO_FORCE_STOP(
            ids = setOf("com.android.settings:id/button1"),
            labels = setOf("force stop"),
        ),
        PACKAGE_INSTALLER_CONFIRM(
            // The install confirmation across API levels and OEM variants (Google's installer,
            // AOSP packageinstaller, permissioncontroller-hosted confirmations).
            ids = setOf(
                "com.android.packageinstaller:id/install_button",
                "com.android.packageinstaller:id/ok_button",
                "com.android.packageinstaller:id/next_button",
                "com.android.packageinstaller:id/done_button",
                "com.google.android.packageinstaller:id/install_button",
                "com.android.permissioncontroller:id/install_button",
                "com.android.permissioncontroller:id/continue_button",
                "com.android.packageinstaller:id/launch_button",
            ),
            labels = setOf("install", "next", "ok", "done", "instalar", "installieren"),
        ),
        ;

        companion object {
            /** Matches when [pkg] is a system package and one of the known ids/labels appears in the window. */
            fun match(pkg: String, ids: Set<String>, labels: Set<String>): KnownDialog? {
                if (!(pkg.startsWith("com.android") || pkg.startsWith("com.google.android"))) return null
                return entries.firstOrNull { d ->
                    val idHit = d.ids.any { known ->
                        ids.any { it == known || (known.contains('/') && it.endsWith(":id/" + known.substringAfterLast('/'))) }
                    }
                    val labelHit = labels.any { label -> d.labels.any { l -> label.contains(l, ignoreCase = true) } }
                    idHit || labelHit
                }
            }
        }
    }

    /**
     * @param targetWindowId  the window the action's node lives in
     * @param occluders       windows with a higher layer than the target's (highest first)
     * @param mode            the task's dismiss policy
     */
    fun evaluate(targetWindowId: Int, occluders: List<Occluder>, mode: Mode): Decision {
        val blocker = occluders.firstOrNull() ?: return Decision.Clear
        val known = KnownDialog.match(blocker.pkg, blocker.ids, blocker.labels)
        return when {
            mode == Mode.DISMISS_KNOWN && known != null -> Decision.Dismiss(known, blocker)
            else -> Decision.Obscured(
                "a ${blocker.type} window from ${blocker.pkg} (id ${blocker.windowId}) sits above the target window $targetWindowId",
            )
        }
    }
}

/**
 * Applies [PopupGuardPolicy] to live data. Kept separate from the policy so the decision logic is exhaustively
 * testable while this object only reshapes framework/window data into the policy's inputs.
 */
object PopupGuard {
    fun evaluate(
        targetWindowId: Int,
        windows: List<RootResolver.WindowMeta>,
        visibleIdsOf: (windowId: Int) -> Pair<Set<String>, Set<String>>,
        mode: PopupGuardPolicy.Mode,
    ): PopupGuardPolicy.Decision {
        val targetLayer = windows.firstOrNull { it.id == targetWindowId }?.layer
            ?: return PopupGuardPolicy.Decision.Clear // unknown target window: nothing proven above it
        val occluders = windows
            .filter { it.layer > targetLayer && it.type != "input_method" }
            .sortedByDescending { it.layer }
            .map { w ->
                val (ids, labels) = visibleIdsOf(w.id)
                PopupGuardPolicy.Occluder(w.id, w.pkg ?: "", w.type, ids = ids, labels = labels)
            }
        return PopupGuardPolicy.evaluate(targetWindowId, occluders, mode)
    }
}
