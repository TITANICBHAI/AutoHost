package app.autohost.capabilities.screen

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `device.screen` capability: wave-1 reads (`is_on`, `is_unlocked`) plus the P8-10 control
 * methods (`wake`, `dismiss_keyguard`, `lock`, `brightness`) — plan §6.1 W2. Never bypasses a
 * secure keyguard. Framework calls sit behind [SystemState] so the mapping stays unit-testable.
 */
class ScreenProvider(private val state: SystemState = AndroidSystemState) : CapabilityProvider {

    override val capabilityId: String = "device.screen"
    override val providerId: String = "screen.reads"
    override val tier: String = "T0"

    /** P8-10: the lock/wake path can ride the a11y global actions; wired by the app layer. */
    @Volatile
    var a11yActions: A11yScreenActions? = null

    /** P8-14: write-settings access flag; default honest-false until the app wires it. */
    @Volatile
    var canWriteSettings: () -> Boolean = { false }

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String =
        ScreenControlPolicy.permissionFor(method)

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "is_on" -> Envelope.ok(buildJsonObject { put("on", state.isOn()) })
        "is_unlocked" -> Envelope.ok(
            buildJsonObject {
                put("unlocked", state.isUnlocked())
                put("secure", state.isSecure())
            },
        )
        "wake" -> ScreenControlPolicy.a11yActionVerdict(a11yActions != null) ?: run {
            val woke = a11yActions!!.wakeUp()
            Envelope.ok(buildJsonObject { put("on", woke); put("strategy", "a11y_wake_up") })
        }
        "lock" -> ScreenControlPolicy.a11yActionVerdict(a11yActions != null) ?: run {
            Envelope.ok(buildJsonObject { put("locked", a11yActions!!.lockScreen()) })
        }
        "dismiss_keyguard" -> when (val verdict = ScreenControlPolicy.keyguardVerdict(state.isSecure())) {
            is ScreenControlPolicy.KeyguardVerdict.RefusedSecure -> Envelope.ok(
                buildJsonObject {
                    put("dismissed", false)
                    put("secure", true)
                },
            )
            is ScreenControlPolicy.KeyguardVerdict.DismissRequest -> Envelope.ok(
                buildJsonObject {
                    val dismissed = state.requestDismissKeyguard()
                    put("dismissed", dismissed)
                    put("secure", false)
                },
            )
        }
        "brightness" -> when (val verdict = ScreenControlPolicy.brightnessVerdict(args, canWriteSettings())) {
            is ScreenControlPolicy.BrightnessVerdict.Invalid ->
                if (ScreenControlPolicy.needsWriteSettings(args)) {
                    Envelope.err(
                        ErrorCode.NEEDS_SETUP,
                        "setting brightness needs the write-settings special access",
                        reason = "WRITE_SETTINGS_NOT_GRANTED",
                        fixAction = "OPEN_WRITE_SETTINGS",
                    )
                } else {
                    Envelope.err(ErrorCode.INVALID_ARGS, "brightness level must be 0-100")
                }
            is ScreenControlPolicy.BrightnessVerdict.Read -> Envelope.ok(
                buildJsonObject {
                    put("level", state.brightness())
                    put("mode", state.brightnessMode())
                },
            )
            is ScreenControlPolicy.BrightnessVerdict.Write -> {
                state.setBrightness(verdict.level)
                Envelope.ok(buildJsonObject { put("level", verdict.level); put("mode", "manual") })
            }
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.screen method '$method'")
    }
}

/** Framework reads (W1) + the P8-10 control surface (wake/lock ride [A11yScreenActions]). */
interface SystemState {
    fun isOn(): Boolean
    fun isUnlocked(): Boolean
    fun isSecure(): Boolean
    fun requestDismissKeyguard(): Boolean
    fun brightness(): Int
    fun brightnessMode(): String
    fun setBrightness(level: Int): Boolean
}

/** P8-10: the wake/lock global actions, implemented in :app over the live a11y service. */
interface A11yScreenActions {
    fun wakeUp(): Boolean
    fun lockScreen(): Boolean
}

/** Real implementation: PowerManager + KeyguardManager. Context wired once from AppGraph. */
object AndroidSystemState : SystemState {
    @Volatile
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    override fun isOn(): Boolean = appContext?.let { ctx ->
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        try {
            pm?.isInteractive ?: false
        } catch (e: RuntimeException) {
            false
        }
    } ?: false

    override fun isUnlocked(): Boolean = appContext?.let { ctx ->
        val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        try {
            km?.isKeyguardLocked?.not() ?: false
        } catch (e: RuntimeException) {
            false
        }
    } ?: false

    override fun isSecure(): Boolean = appContext?.let { ctx ->
        val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        try {
            km?.isDeviceSecure ?: false
        } catch (e: RuntimeException) {
            false
        }
    } ?: false

    override fun requestDismissKeyguard(): Boolean = appContext?.let { ctx ->
        val km = ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        try {
            // requestDismissKeyguard requires an Activity. This holder has only application context;
            // the dedicated recovery Activity performs the real request when the guard needs it.
            km?.isKeyguardLocked?.not() ?: false
        } catch (e: RuntimeException) {
            false
        }
    } ?: false

    override fun brightness(): Int = appContext?.let { ctx ->
        try {
            android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            0
        }
    } ?: 0

    override fun brightnessMode(): String = appContext?.let { ctx ->
        try {
            val mode = android.provider.Settings.System.getInt(
                ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
            )
            if (mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "auto" else "manual"
        } catch (e: Exception) {
            "manual"
        }
    } ?: "manual"

    override fun setBrightness(level: Int): Boolean = appContext?.let { ctx ->
        try {
            // 0-100 → the framework's 0-255 scale.
            android.provider.Settings.System.putInt(
                ctx.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                (level * 255) / 100,
            )
        } catch (e: Exception) {
            false
        }
    } ?: false
}
