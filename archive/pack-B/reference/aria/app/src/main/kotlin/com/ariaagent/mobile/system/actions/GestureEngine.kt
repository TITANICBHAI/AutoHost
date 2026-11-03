package com.ariaagent.mobile.system.actions

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ariaagent.mobile.system.accessibility.AgentAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * GestureEngine — executes LLM action decisions as physical gestures.
 *
 * The LLM outputs JSON: {"tool":"Click","node_id":"#3","reason":"..."}
 * GestureEngine resolves the node_id → screen coordinates → gesture dispatch.
 *
 * Two coordinate modes:
 *
 *   1. Node-ID mode (accessibility tree available):
 *        {"tool":"Click","node_id":"#3"}
 *        {"tool":"Swipe","node_id":"#7","direction":"up"}
 *      GestureEngine looks up the node's bounding box → computes center → dispatches.
 *
 *   2. XY mode (game / Flutter / Unity — no accessibility tree):
 *        {"tool":"TapXY","x":0.45,"y":0.60,"reason":"..."}
 *        {"tool":"SwipeXY","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2,"reason":"..."}
 *      Coordinates are normalised [0.0–1.0] relative to screen width/height.
 *      AgentAccessibilityService.getScreenSize() converts to real pixels.
 *      These are produced by the LLM when [SAM REGIONS] or [VISION DESCRIPTION]
 *      is the only signal (no node IDs available).
 *
 * After each action: wait for AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
 * to confirm the screen updated (action was received by the OS).
 *
 * Phase: 3 (Action Layer)
 */
object GestureEngine {

    /**
     * Parse and execute an action from the LLM's JSON output.
     * Handles both node-ID actions and normalised XY coordinate actions.
     * @return true if action was dispatched successfully
     */
    suspend fun executeFromJson(actionJson: String): Boolean {
        return try {
            val json      = JSONObject(actionJson)
            val tool      = json.optString("tool", "")
            val nodeId    = json.optString("node_id", "")
            val direction = json.optString("direction", "")
            val text      = json.optString("text", "")

            when (tool.lowercase()) {
                // ── Node-ID actions ─────────────────────────────────────────
                "click", "tap"        -> tap(nodeId)
                "swipe"               -> swipe(nodeId, direction)
                "type", "typetext"    -> type(nodeId, text)
                "scroll"              -> scroll(nodeId, direction)
                "longpress"           -> longPress(nodeId)
                "back"                -> { AgentAccessibilityService.performBack(); true }

                // ── XY coordinate actions (vision / SAM fallback) ────────────
                "tapxy" -> {
                    val normX = json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f)
                    val normY = json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                    tapXY(normX, normY)
                }
                "swipexy" -> {
                    val x1 = json.optDouble("x1", 0.3).toFloat().coerceIn(0f, 1f)
                    val y1 = json.optDouble("y1", 0.8).toFloat().coerceIn(0f, 1f)
                    val x2 = json.optDouble("x2", 0.3).toFloat().coerceIn(0f, 1f)
                    val y2 = json.optDouble("y2", 0.2).toFloat().coerceIn(0f, 1f)
                    swipeXY(x1, y1, x2, y2)
                }

                else -> {
                    Log.w("GestureEngine", "executeFromJson: unrecognised tool='$tool' — LLM output may be malformed | json=$actionJson")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w("GestureEngine", "executeFromJson: JSON parse/dispatch failed — ${e.message} | json=$actionJson")
            false
        }
    }

    // ── Node-ID based actions ─────────────────────────────────────────────────

    /**
     * Tap the center of the element with the given semantic ID.
     *
     * Bug #10 fix: dispatchGesture() is asynchronous — the callback fires on the
     * main thread AFTER this call returns. Using suspendCancellableCoroutine
     * parks the coroutine until onCompleted/onCancelled is actually delivered,
     * so the returned Boolean is truthful instead of always false.
     */
    suspend fun tap(nodeId: String): Boolean {
        val node = AgentAccessibilityService.getNodeById(nodeId) ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        return suspendCancellableCoroutine { cont ->
            AgentAccessibilityService.dispatchTap(cx, cy, object : GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                    cont.resume(true)
                }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                    Log.w("GestureEngine", "tap cancelled by OS for node $nodeId — possible popup or screen transition")
                    cont.resume(false)
                }
            })
        }
    }

    /**
     * Swipe within a scrollable node.
     * Bug #10 fix: suspendCancellableCoroutine awaits the async gesture callback.
     */
    suspend fun swipe(nodeId: String, direction: String): Boolean {
        val node = AgentAccessibilityService.getNodeById(nodeId) ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up"    -> floatArrayOf(rect.centerX().toFloat(), rect.bottom.toFloat() * 0.8f,
                                    rect.centerX().toFloat(), rect.top.toFloat()    * 1.2f)
            "down"  -> floatArrayOf(rect.centerX().toFloat(), rect.top.toFloat()    * 1.2f,
                                    rect.centerX().toFloat(), rect.bottom.toFloat() * 0.8f)
            "left"  -> floatArrayOf(rect.right.toFloat()  * 0.8f, rect.centerY().toFloat(),
                                    rect.left.toFloat()   * 1.2f, rect.centerY().toFloat())
            "right" -> floatArrayOf(rect.left.toFloat()   * 1.2f, rect.centerY().toFloat(),
                                    rect.right.toFloat()  * 0.8f, rect.centerY().toFloat())
            else -> return false
        }

        return suspendCancellableCoroutine { cont ->
            AgentAccessibilityService.dispatchSwipe(x1, y1, x2, y2, object : GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                    cont.resume(true)
                }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                    Log.w("GestureEngine", "swipe cancelled by OS for node $nodeId direction=$direction")
                    cont.resume(false)
                }
            })
        }
    }

    /**
     * Type text into an editable node using ACTION_SET_TEXT.
     * Synchronous accessibility action — no callback, return value is direct.
     */
    fun type(nodeId: String, text: String): Boolean {
        val node = AgentAccessibilityService.getNodeById(nodeId) ?: return false
        if (!node.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Scroll a node using accessibility actions (no gesture needed).
     * Synchronous accessibility action — no callback, return value is direct.
     */
    fun scroll(nodeId: String, direction: String): Boolean {
        val node = AgentAccessibilityService.getNodeById(nodeId) ?: return false
        return when (direction.lowercase()) {
            "up"   -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "down" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else   -> false
        }
    }

    /**
     * Long press to trigger context menus.
     *
     * Bug #10 fix: suspendCancellableCoroutine awaits the async gesture callback.
     * Bug #11 fix: uses dispatchLongPress (500 ms stroke) instead of dispatchTap
     *   (50 ms stroke). A 50 ms touch is a tap; the OS requires ≥ 400 ms to
     *   recognise a long press and open the context menu.
     */
    suspend fun longPress(nodeId: String): Boolean {
        val node = AgentAccessibilityService.getNodeById(nodeId) ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return suspendCancellableCoroutine { cont ->
            AgentAccessibilityService.dispatchLongPress(
                rect.centerX().toFloat(), rect.centerY().toFloat(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                        cont.resume(true)
                    }
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                        Log.w("GestureEngine", "longPress cancelled by OS for node $nodeId")
                        cont.resume(false)
                    }
                }
            )
        }
    }

    // ── XY coordinate actions (vision-only / SAM fallback) ────────────────────

    /**
     * Tap at normalised screen coordinates [0.0–1.0].
     *
     * Used when the LLM produces TapXY from [SAM REGIONS] or [VISION DESCRIPTION]
     * because no accessibility node IDs are available (game / Flutter / Unity screens).
     *
     * Bug #10 fix: suspendCancellableCoroutine awaits the async gesture callback.
     *
     * @param normX  Normalised X [0.0–1.0] (0 = left edge, 1 = right edge)
     * @param normY  Normalised Y [0.0–1.0] (0 = top edge, 1 = bottom edge)
     */
    suspend fun tapXY(normX: Float, normY: Float): Boolean {
        val (w, h) = AgentAccessibilityService.getScreenSize()
        val px = normX * w
        val py = normY * h
        return suspendCancellableCoroutine { cont ->
            AgentAccessibilityService.dispatchTap(px, py, object : GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                    cont.resume(true)
                }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                    Log.w("GestureEngine", "tapXY cancelled by OS at norm(%.2f, %.2f)".format(normX, normY))
                    cont.resume(false)
                }
            })
        }
    }

    /**
     * Swipe between two normalised screen coordinates [0.0–1.0].
     *
     * Used for drag-scroll or swipe-to-dismiss in game/Flutter screens
     * where no accessibility node IDs are available.
     *
     * Bug #10 fix: suspendCancellableCoroutine awaits the async gesture callback.
     */
    suspend fun swipeXY(normX1: Float, normY1: Float, normX2: Float, normY2: Float): Boolean {
        val (w, h) = AgentAccessibilityService.getScreenSize()
        val x1 = normX1 * w
        val y1 = normY1 * h
        val x2 = normX2 * w
        val y2 = normY2 * h
        return suspendCancellableCoroutine { cont ->
            AgentAccessibilityService.dispatchSwipe(x1, y1, x2, y2, object : GestureResultCallback() {
                override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                    cont.resume(true)
                }
                override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                    Log.w("GestureEngine", "swipeXY cancelled by OS from norm(%.2f,%.2f) to norm(%.2f,%.2f)".format(normX1, normY1, normX2, normY2))
                    cont.resume(false)
                }
            })
        }
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}
