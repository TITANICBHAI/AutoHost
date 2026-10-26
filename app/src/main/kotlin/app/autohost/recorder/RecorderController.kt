package app.autohost.recorder

import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.a11y.RecorderTapEvent
import app.autohost.capabilities.a11y.SimpleA11yReader
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.model.UiNode
import app.autohost.runtime.flow.RecordedEvent
import app.autohost.runtime.flow.RecordedKind
import app.autohost.runtime.flow.RecordedNode
import app.autohost.runtime.flow.RecordedScreen
import app.autohost.runtime.flow.Recorder
import app.autohost.runtime.flow.RecorderResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * P6-09: the recorder controller — the :app half of the recorder (plan §7.9). Arms the
 * accessibility event tap, converts tap events + live snapshots into
 * [app.autohost.runtime.flow.RecordedEvent]s, and on stop renders the flow document plus the
 * recording sidecar (ADR-053). The REC overlay is owned by [RecorderOverlay]; the review screen
 * (`ui/RecorderReviewScreen.kt`) consumes [state] and calls [save].
 *
 * State lives in [UiState] (CONVENTIONS: StateFlow in, caller reads effects from the stop/save
 * results — the recorder has no async effects of its own).
 */
class RecorderController(
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Dev-folder root the review screen saves into (injected by AppGraph; null = no external storage). */
    private val devRoot: () -> File? = { null },
) {
    data class UiState(
        val recording: Boolean = false,
        val stepCount: Int = 0,
        val lastError: String? = null,
        /** Set when a recording stops: the artifacts the review screen shows. */
        val result: RecorderResult? = null,
        /** Flow document JSON after stop (rendered from [result]). */
        val flowJson: JsonObject? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val reader = SimpleA11yReader({ A11yBridge.service.value }, clock)
    private val events = mutableListOf<RecordedEvent>()
    private var collectJob: Job? = null
    private var overlay: RecorderOverlay? = null

    /** Starts a recording session. Fails honestly when the accessibility service is not connected. */
    fun start(overlayHost: RecorderOverlay? = null): Boolean {
        if (_state.value.recording) return true
        if (!A11yBridge.connected) {
            _state.value = _state.value.copy(lastError = "the accessibility service is not connected")
            return false
        }
        synchronized(events) { events.clear() }
        overlay = overlayHost
        A11yBridge.armRecorder()
        collectJob = scope.launch { A11yBridge.recorderEvents.collect { onTap(it) } }
        overlayHost?.show()
        _state.value = UiState(recording = true)
        AhLog.i(TAG, "recorder armed")
        return true
    }

    /** Stops, converts the session, and publishes flow.json + sidecar for the review screen. */
    fun stop(): UiState {
        if (!_state.value.recording) return _state.value
        A11yBridge.disarmRecorder()
        collectJob?.cancel()
        collectJob = null
        overlay?.hide()
        overlay = null
        val snapshot = synchronized(events) { events.toList() }
        val result = Recorder.record(snapshot)
        val flowJson = Recorder.toFlowDocument(result, flowName(result))
        val next = UiState(
            recording = false,
            stepCount = result.steps.size,
            lastError = null,
            result = result,
            flowJson = flowJson,
        )
        _state.value = next
        AhLog.i(TAG, "recorder stopped")
        return next
    }

    /**
     * Review screen "Save as flow": writes `flow.json` + `flow.json.recording.json` (sidecar,
     * ADR-053) into a new dev-folder folder. The flow/sidecar come from the caller because the
     * review screen edits the step list (delete/reorder) before saving. Returns null when
     * external storage is unavailable or there is nothing recorded.
     */
    fun save(folderName: String, flowJson: JsonObject, result: RecorderResult): Pair<File, File>? {
        val root = devRoot() ?: return null
        val folder = File(root, folderName).apply { mkdirs() }
        val flowFile = File(folder, "flow.json")
        val sidecarFile = File(folder, "flow.json.recording.json")
        flowFile.writeText(AhJson.stringify(flowJson))
        sidecarFile.writeText(AhJson.stringify(result.sidecar(folderName).toJson()))
        AhLog.i(TAG, "recorded flow saved")
        return flowFile to sidecarFile
    }

    /** Drops the finished session (review screen discarded). */
    fun reset() {
        _state.value = UiState()
    }

    private fun onTap(tap: RecorderTapEvent) {
        val kind = when (tap.kind) {
            RecorderTapEvent.Kind.CLICK -> RecordedKind.CLICK
            RecorderTapEvent.Kind.LONG_CLICK -> RecordedKind.LONG_CLICK
            RecorderTapEvent.Kind.TEXT -> RecordedKind.TEXT
        }
        // The live screen at event time: pre-state snapshot + uniqueness oracle for ranking.
        val snapshot = reader.read(tap.pkg?.let { setOf(it) })
        val foreground = app.autohost.core.common.ForegroundTracker.state.value
        val screen = RecordedScreen(
            packageName = tap.pkg ?: snapshot.pkg ?: "",
            activity = foreground?.pkg?.takeIf { it == (tap.pkg ?: snapshot.pkg) }
                ?.let { foreground.activity } ?: tap.pkg ?: snapshot.pkg ?: "",
            nodes = snapshot.nodes.map { it.toRecorded(tap) },
        )
        val node = matchNode(tap, snapshot.nodes) ?: RecordedNode(
            id = tap.viewId ?: "",
            text = tap.text ?: "",
            desc = tap.desc ?: "",
            cls = tap.className ?: "android.view.View",
            bounds = tap.bounds ?: listOf(0, 0, 0, 0),
            editable = tap.editable,
            password = tap.password,
        )
        synchronized(events) {
            events.add(
                RecordedEvent(
                    kind = kind,
                    node = node,
                    screen = screen,
                    tsMs = tap.tsMs,
                    text = tap.text,
                ),
            )
        }
        _state.value = _state.value.copy(stepCount = _state.value.stepCount + 1)
        overlay?.updateCount()
    }

    /** Matches the tapped node against the snapshot by exact bounds, then nearest-bounds fallback. */
    private fun matchNode(tap: RecorderTapEvent, nodes: List<UiNode>): RecordedNode? {
        val bounds = tap.bounds ?: return null
        nodes.firstOrNull { listOf(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) == bounds }
            ?.let { return it.toRecorded(tap) }
        // Bounds drift between event and snapshot (animations): accept a near-exact overlap only.
        return nodes
            .map { it to (kotlin.math.abs(it.bounds.left - bounds[0]) + kotlin.math.abs(it.bounds.top - bounds[1])) }
            .filter { (_, distance) -> distance <= 24 }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.toRecorded(tap)
    }

    private fun UiNode.toRecorded(tap: RecorderTapEvent? = null) = RecordedNode(
        id = id.ifEmpty { tap?.viewId.orEmpty() },
        text = text.ifEmpty { tap?.text.orEmpty() },
        desc = desc.ifEmpty { tap?.desc.orEmpty() },
        cls = cls.ifEmpty { tap?.className.orEmpty() },
        bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        editable = has("editable"),
        password = has("password"),
        clickable = has("clickable"),
    )

    private fun flowName(result: RecorderResult): String =
        "recorded-" + (if (result.startedAtMs > 0) result.startedAtMs else clock.nowMs())

    companion object {
        private const val TAG = "recorder"
    }
}
