package app.autohost.ui2

import android.content.Context
import android.provider.Settings
import app.autohost.AndroidHealthEnv
import app.autohost.AppGraph
import app.autohost.core.broker.security.InsecureFeatures

/** Reads the phone's real state into the pure [LiveAccess]. The only Android-touching part of the Phone tab's logic. */
object LiveAccessReader {

    fun read(context: Context, graph: AppGraph): LiveAccess {
        val env = AndroidHealthEnv.build(
            context,
            graph.a11yConnected(),
            workerAlive = true,
            devApiEnabled = graph.devApiEnabled,
        )
        val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val switchState = graph.insecureSwitches.state
        val switchesOn = FeatureCatalog.all
            .filter { it.need == Need.SWITCH }
            .filter { InsecureFeatures.evaluate(it.capabilityId, null, switchState) == InsecureFeatures.Decision.Allowed }
            .map { it.capabilityId }
            .toSet()
        return LiveAccess(
            accessibilityOn = env.accessibilityConnected,
            notificationAccessOn = env.notificationListenerConnected,
            keyboardSelected = defaultIme.startsWith(context.packageName + "/"),
            advancedLive = graph.shellTransportStatuses().any { it.second == "live" },
            secureSettingsGranted = env.writeSecureSettings,
            unsafeSwitchesOn = switchesOn,
            batteryExempt = env.batteryExemption,
        )
    }
}
