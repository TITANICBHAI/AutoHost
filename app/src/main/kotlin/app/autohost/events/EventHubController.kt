package app.autohost.events

import android.app.Application
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.notifications.NotificationBridge
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.common.ForegroundTracker
import app.autohost.core.engine.events.EventHub
import app.autohost.core.engine.events.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-02: the app-side owner of the event backbone (plan §11.8). One instance per process:
 *
 *  - owns the [EventHub] (created in AppGraph),
 *  - bridges the a11y service's foreground transitions and toasts into the hub,
 *  - bridges the notification listener's posted/removed events into the hub (P8-01),
 *  - starts/stops [AndroidEventSources] + the [EventHubService] FGS for the receiver-driven kinds
 *    — the service exists exactly while something needs it (plan §11.8: "a low-priority foreground
 *    service that exists only while event triggers are armed").
 *
 * App-foreground events carry only the package name; notification events only package/title/text;
 * toasts only package/text (§11.8 privacy).
 */
class EventHubController(
    private val app: Application,
    val hub: EventHub,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val sources = AndroidEventSources(
        app = app,
        hub = hub,
        clock = clock,
        hasFineLocation = {
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
    )

    private var receiversJob: Job? = null
    private var a11yJob: Job? = null
    private var notificationsJob: Job? = null

    /** Receiver-driven sources: the FGS exists only while this is on (§11.8). */
    fun setReceiversNeeded(needed: Boolean) {
        if (needed && receiversJob == null) {
            sources.start()
            EventHubService.start(app)
            receiversJob = scope.launch { /* marker: receivers active */ }
            AhLog.i(TAG, "receiver sources started", buildJsonObject { })
        } else if (!needed && receiversJob != null) {
            sources.stop()
            EventHubService.stop(app)
            receiversJob?.cancel()
            receiversJob = null
            AhLog.i(TAG, "receiver sources stopped", buildJsonObject { })
        }
    }

    /** A11y-fed bridges (foreground transitions + toasts); no-op until the service connects. */
    fun startA11yBridges() {
        if (a11yJob != null) return
        a11yJob = scope.launch {
            var previousPkg: String? = null
            ForegroundTracker.state.collect { fg ->
                val pkg = fg?.pkg
                if (pkg != null && pkg != previousPkg) {
                    if (previousPkg != null) {
                        hub.dispatch(TriggerEvent("app.exited", pkg = previousPkg, atMs = clock.nowMs()))
                    }
                    hub.dispatch(TriggerEvent("app.entered", pkg = pkg, atMs = clock.nowMs()))
                    previousPkg = pkg
                }
            }
        }
        scope.launch {
            var lastText: String? = null
            A11yBridge.lastToast.collect { toast ->
                if (toast != null && toast.text != lastText) {
                    lastText = toast.text
                    hub.dispatch(
                        TriggerEvent(
                            kind = "toast.shown",
                            pkg = toast.pkg,
                            payload = buildJsonObject { put("text", toast.text) },
                            atMs = toast.atMs,
                        ),
                    )
                }
            }
        }
    }

    /** Notification-listener feed (P8-01); called once the listener service posts its first snapshot. */
    fun startNotificationBridge() {
        if (notificationsJob != null) return
        notificationsJob = scope.launch {
            NotificationBridge.events.collect { event ->
                hub.dispatch(
                    TriggerEvent(
                        kind = event.kind,
                        pkg = event.notification.pkg,
                        payload = buildJsonObject {
                            put("title", event.notification.title)
                            put("text", event.notification.text)
                        },
                        atMs = event.atMs,
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "events"
    }
}
