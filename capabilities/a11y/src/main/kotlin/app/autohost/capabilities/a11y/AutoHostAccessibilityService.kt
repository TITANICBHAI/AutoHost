package app.autohost.capabilities.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import app.autohost.core.common.ForegroundTracker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accessibility service: the device-facing half of the a11y capability. On connect it publishes itself
 * through [A11yBridge]; window-state events feed the process-wide [ForegroundTracker] and the
 * [ForegroundResolver]; content-change events drive the event-driven waits; toast events are recorded.
 */
class AutoHostAccessibilityService : AccessibilityService() {

    val foregroundResolver = ForegroundResolver(A11yBridge.clock)
    val autoAcceptGate = AutoAcceptGate(A11yBridge.clock)

    /** P7-12: hold-to-stop detector; created lazily against the current user config. */
    private var keyFilterDetector: VolumeKeyHoldDetector? = null
    private var keyFilterKeyCode: Int = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yBridge.connect(this)
    }

    /**
     * P7-12: the global stop gesture (plan §10.3). When the user enabled the key filter, an armed
     * DOWN is consumed (volume HUD stays quiet during the hold); crossing the hold threshold fires
     * the global-stop hook exactly once (while the key is still down — the DOWN schedules the
     * threshold poll); releasing earlier simply ends the gesture. Without the filter — or for
     * other keys — events pass through untouched.
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        val e = event ?: return false
        val spec = A11yBridge.keyFilterProvider?.invoke() ?: return false
        if (e.keyCode != spec.keyCode) return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
            return true // key repeat while armed: keep consuming, do not re-arm
        }
        val detector = detectorFor(spec)
        return when (e.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                val armed = detector.onKeyDown(e.keyCode, A11yBridge.clock.monotonicMs())
                if (armed) {
                    mainHandler.postDelayed({
                        if (detector.poll(A11yBridge.clock.monotonicMs())) A11yBridge.fireGlobalStop("key_filter")
                    }, spec.holdMs)
                }
                armed
            }
            android.view.KeyEvent.ACTION_UP -> detector.onKeyUp(e.keyCode, A11yBridge.clock.monotonicMs())
            else -> false
        }
    }

    private fun detectorFor(spec: A11yBridge.KeyFilterSpec): VolumeKeyHoldDetector {
        val existing = keyFilterDetector
        if (existing != null && keyFilterKeyCode == spec.keyCode) return existing
        return VolumeKeyHoldDetector(A11yBridge.clock, spec.holdMs, spec.keyCode).also {
            keyFilterDetector = it
            keyFilterKeyCode = spec.keyCode
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // P6-09: recorder tap first — armed-only, cheap, never blocks the callback path.
        if (A11yBridge.isRecorderArmed) {
            RecorderTapEvent.fromEvent(event, A11yBridge.clock.nowMs())?.let { A11yBridge.tapRecorderEvent(it) }
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                A11yBridge.noteWindowChange()
                A11yBridge.emitWindowChange()
                foregroundResolver.update(pkg)
                ForegroundTracker.update(pkg, event.className?.toString(), A11yBridge.clock.nowMs())
                consultAutoAccept()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                A11yBridge.noteWindowChange()
                A11yBridge.emitWindowChange()
                consultAutoAccept()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_CHANGED -> {
                val text = try {
                    event.text?.firstOrNull()?.toString()
                } catch (e: RuntimeException) {
                    null
                }
                val parcelable = try {
                    event.parcelableData?.javaClass?.name
                } catch (e: RuntimeException) {
                    null
                }
                ToastListener.extract(
                    pkg = event.packageName?.toString(),
                    parcelableClassName = parcelable,
                    text = text,
                    atMs = A11yBridge.clock.nowMs(),
                )?.let { A11yBridge.recordToast(it) }
            }
        }
    }

    /**
     * The AutoAcceptGate acts only on *known* dialogs while a host flow is pending; unknown windows stand
     * down (AutoAcceptPolicy, unit-tested). The affirmative control is found in the occluding window by
     * view id, falling back to the localized labels — Gate 3-e verifies this end to end on the phone.
     */
    private fun consultAutoAccept() {
        if (autoAcceptGate.pendingFlow == AutoAcceptPolicy.Flow.NONE) return
        val metas = SimpleA11yReader({ this }, A11yBridge.clock).windows()
        val top = metas.firstOrNull { it.pkg?.startsWith("com.android") == true && it.hasRoot } ?: return
        val pkg = top.pkg ?: return
        val root = rootOf(top.id) ?: return
        val tree = UiTreeBuilder.build(root, pkg)
        val ids = tree.nodes.mapNotNull { it.id }.toSet()
        val labels = (tree.nodes.map { it.text } + tree.nodes.map { it.desc }).filter { it.isNotBlank() }.toSet()
        val match = PopupGuardPolicy.KnownDialog.match(pkg, ids, labels) ?: return
        when (val decision = autoAcceptGate.consult(match)) {
            is AutoAcceptPolicy.Decision.Accept -> {
                val clicked = clickAffirmative(tree, match)
                if (clicked) autoAcceptGate.recordAccepted() else autoAcceptGate.recordFailure()
            }
            is AutoAcceptPolicy.Decision.StandDown -> Unit // policy said no; nothing is clicked, ever
        }
    }

    /** Clicks the known dialog's affirmative control (by id, else by label). Best effort; honest result. */
    private fun clickAffirmative(tree: UiTreeBuilder.Tree, dialog: PopupGuardPolicy.KnownDialog): Boolean {
        val candidates = dialog.ids.map { it.substringAfterLast('/') }
        val node = tree.nodes.firstOrNull { n -> n.id.isNotEmpty() && candidates.any { n.id.endsWith(it) } }
            ?: tree.nodes.firstOrNull { n -> dialog.labels.any { l -> n.text.contains(l, ignoreCase = true) } }
            ?: return false
        val info = tree.infos.getOrNull(node.index) ?: return false
        return try {
            info.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun rootOf(windowId: Int): android.view.accessibility.AccessibilityNodeInfo? = try {
        windows.orEmpty().filterNotNull().firstOrNull { it.id == windowId }?.root
    } catch (e: RuntimeException) {
        null
    }

    override fun onInterrupt() {
        // Required override; the ReadGate degrades reads while the service is interrupted.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        A11yBridge.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yBridge.disconnect(this)
        super.onDestroy()
    }
}

/**
 * Process-local access to the connected service (HANDOFF §3). Providers check [service] before offering
 * availability; the app wires [clock] at startup so timestamping goes through the shared Clock rule.
 */
object A11yBridge {
    private val _service = MutableStateFlow<AutoHostAccessibilityService?>(null)
    val service: StateFlow<AutoHostAccessibilityService?> = _service.asStateFlow()

    /** Emits on every window state/content change — the event-driven side of wait_for/wait_for_settle. */
    private val _windowChanges = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val windowChanges: SharedFlow<Long> = _windowChanges.asSharedFlow()

    private val _lastToast = MutableStateFlow<ToastListener.ToastEvent?>(null)
    val lastToast: StateFlow<ToastListener.ToastEvent?> = _lastToast.asStateFlow()

    // P6-09: the recorder event tap. Armed state is a plain volatile flag (read on the a11y
    // callback path); events buffer to 64 so the controller can collect them asynchronously.
    @Volatile
    private var recorderArmed: Boolean = false
    private val _recorderEvents = MutableSharedFlow<RecorderTapEvent>(extraBufferCapacity = 64)
    val recorderEvents: SharedFlow<RecorderTapEvent> = _recorderEvents.asSharedFlow()

    fun armRecorder() {
        recorderArmed = true
    }

    fun disarmRecorder() {
        recorderArmed = false
    }

    val isRecorderArmed: Boolean get() = recorderArmed

    internal fun tapRecorderEvent(event: RecorderTapEvent) {
        if (recorderArmed) _recorderEvents.tryEmit(event)
    }

    @Volatile
    var clock: app.autohost.core.common.Clock = app.autohost.core.common.SystemClockImpl()

    /** Wall-clock of the last window content/state change; drives wait_for_settle. */
    @Volatile
    var lastWindowChangeMs: Long = 0L
        private set

    val connected: Boolean get() = _service.value != null

    // P7-12: the volume-key global stop. The app side supplies the user's key-filter config
    // (Surfaces settings; null = off) and the kill-switch hook; both are plain volatile slots so
    // the a11y callback path never blocks on the main process graph.
    data class KeyFilterSpec(val keyCode: Int, val holdMs: Long)

    @Volatile
    var keyFilterProvider: (() -> KeyFilterSpec?)? = null

    @Volatile
    var globalStopHook: ((reason: String) -> Unit)? = null

    fun fireGlobalStop(reason: String) {
        globalStopHook?.invoke(reason)
    }

    internal fun connect(service: AutoHostAccessibilityService) {
        _service.value = service
        noteWindowChange()
    }

    internal fun disconnect(service: AutoHostAccessibilityService) {
        if (_service.value === service) _service.value = null
    }

    fun noteWindowChange() {
        lastWindowChangeMs = clock.nowMs()
    }

    fun emitWindowChange() {
        _windowChanges.tryEmit(lastWindowChangeMs)
    }

    fun recordToast(event: ToastListener.ToastEvent) {
        _lastToast.value = event
    }

    /** Suspends until the service connects or [timeoutMs] pass. Used by launch-then-verify flows. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = clock.monotonicMs() + timeoutMs
        while (_service.value == null) {
            if (clock.monotonicMs() >= deadline) return false
            kotlinx.coroutines.delay(50)
        }
        return true
    }
}
