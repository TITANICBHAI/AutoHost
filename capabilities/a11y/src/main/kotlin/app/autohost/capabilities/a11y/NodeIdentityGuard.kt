package app.autohost.capabilities.a11y

import app.autohost.core.model.UiNode

/**
 * P12-11 (RM-A1): the node identity guard — before a node action, the resolved node's CURRENT
 * identity is compared with the identity at snapshot time. A stale (gone/moved) or relabelled
 * (text changed) node refuses with STALE_NODE: **never a wrong-node click**. This replaces
 * trusting the resolved handle alone and closes the "closest clickable ancestor" guess.
 *
 * Identity = id + class + bounds + text. A pure table over UiNode pairs (the caller reads the
 * CURRENT node and builds the current UiNode the same way the snapshot did).
 */
object NodeIdentityGuard {

    sealed class Verdict {
        /** The node is recognizably the same: proceed with the action. */
        data object Match : Verdict()

        /** The node is gone or structurally different: refuse (STALE_NODE). */
        data class Stale(val detail: String) : Verdict()

        /** The node is there but its text changed since the snapshot: refuse (STALE_NODE). */
        data class Relabelled(val from: String, val to: String) : Verdict()
    }

    fun verify(snapshotted: UiNode, current: UiNode?): Verdict {
        if (current == null) return Verdict.Stale("the node is gone from the current tree")
        if (current.id != snapshotted.id) return Verdict.Stale("resource id changed")
        if (current.cls != snapshotted.cls) return Verdict.Stale("class changed")
        if (current.bounds != snapshotted.bounds) return Verdict.Stale("bounds moved")
        if (current.text != snapshotted.text) {
            return Verdict.Relabelled(
                from = snapshotted.text,
                to = current.text,
            )
        }
        return Verdict.Match
    }
}
