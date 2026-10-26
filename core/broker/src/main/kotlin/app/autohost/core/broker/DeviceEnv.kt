package app.autohost.core.broker

/** The device facts providers may consult for availability decisions (pure data; no Android types). */
data class DeviceEnv(
    val sdkInt: Int,
    val isLowRam: Boolean = false,
    val hasAccessibilityService: Boolean = false,
    val manufacturer: String = "",
    val model: String = "",
)
