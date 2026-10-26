package app.autohost.capabilities.shell

/**
 * P9-01: the shell command builders used by the providers. Kept in one place so the allowlist
 * conversations, Setup docs and tests all quote identical command shapes. Every builder returns a
 * plain string — quoting rules are fixed here (single-quote escaping) so task-provided values
 * cannot smuggle shell syntax past [CommandAllowlist]'s metacharacter deny.
 */
object ShellCommands {

    private const val UI_DUMP_PATH = "/data/local/tmp/autohost_ui.xml"

    /** uiautomator dump → cat → rm (single command so one allowlist entry covers it; no metachars). */
    fun uiautomatorDump(): String =
        "uiautomator dump $UI_DUMP_PATH && cat $UI_DUMP_PATH && rm $UI_DUMP_PATH"

    fun screencap(): String = "screencap -p"

    fun inputTap(x: Int, y: Int): String = "input tap ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)}"

    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        "input swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} ${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} ${durationMs.coerceIn(0, 60_000)}"

    /**
     * `input text` with single-quote escaping: close the quote, an escaped literal quote, reopen —
     * the standard POSIX idiom. Metacharacters never reach the command because the allowlist
     * forbids them before this builder is even reached for user commands; this builder is only
     * used internally by `set_text` where text content IS the payload, so it is escaped here.
     */
    fun inputText(text: String): String {
        val escaped = text.replace("'", "'\\''")
        return "input text '$escaped'"
    }

    fun keyevent(code: Int): String = "input keyevent $code"

    /** `dumpsys input_method | grep mInputShown` — the honest keyboard-visibility read. */
    fun dumpsysKeyboard(): String = "dumpsys input_method mInputShown"

    fun foregroundActivity(): String = "dumpsys activity activities top"

    /** Part-2 preview (verified force-stop, `device.app.manage`): kept here so tests pin the shape. */
    fun forceStop(pkg: String): String = "am force-stop ${shellSafePackage(pkg)}"

    // ---- P9-09: device.app.manage ------------------------------------------------------------------------

    /** `pm clear <pkg>` — destructive; callers must gate it behind an explicit confirm flag. */
    fun pmClear(pkg: String): String = "pm clear ${shellSafePackage(pkg)}"

    /** Runtime permission grant/revoke via the shell uid (shell/root transports only). */
    fun pmGrant(pkg: String, permission: String): String =
        "pm grant ${shellSafePackage(pkg)} ${shellSafePermission(permission)}"

    fun pmRevoke(pkg: String, permission: String): String =
        "pm revoke ${shellSafePackage(pkg)} ${shellSafePermission(permission)}"

    /**
     * Streamed install: `cat <path> | pm install -S <bytes> -` — the pipe never crosses the task
     * allowlist (this is a provider-internal command under the device.app.manage grant). [path] is
     * a device-side path the shell uid can read (/data/local/tmp or a granted shared folder).
     */
    fun pmInstall(devicePath: String, sizeBytes: Long): String {
        require(devicePath.all { it.isLetterOrDigit() || it in "._/-" } && !devicePath.contains("..")) {
            "apk path contains unexpected characters"
        }
        require(sizeBytes > 0 && sizeBytes <= MAX_APK_BYTES) { "apk size out of range" }
        return "cat $devicePath | pm install -S $sizeBytes -"
    }

    fun pmUninstall(pkg: String): String = "pm uninstall ${shellSafePackage(pkg)}"

    fun pidOf(pkg: String): String = "pidof ${shellSafePackage(pkg)}"

    // ---- P9-10: device.logcat / device.radio / device.display / device.location.mock ----------------------

    /**
     * `logcat -d -v epoch` — the epoch format carries `<secs>.<msecs>` which [LogcatOutput.parse]
     * rehydrates into wall-clock ms. [-t N] bounds the snapshot; `*:S` + tag filters are appended by
     * the provider so user text never becomes a bare option.
     */
    fun logcatDump(maxLines: Int, tagFilters: List<String>): String {
        require(maxLines in 1..MAX_LOG_LINES) { "max_lines out of range" }
        val specs = tagFilters.take(8).map { safeToken(it, "tag") + ":*" } + "*:S"
        val tail = "-t $maxLines"
        return if (tagFilters.isEmpty()) {
            "logcat -d -v epoch $tail"
        } else {
            "logcat -d -v epoch $tail ${specs.joinToString(" ")}"
        }
    }

    fun logcatClear(): String = "logcat -c"

    fun svcWifi(enable: Boolean): String = "svc wifi ${if (enable) "enable" else "disable"}"

    fun svcData(enable: Boolean): String = "svc data ${if (enable) "enable" else "disable"}"

    fun svcBluetooth(enable: Boolean): String = "svc bluetooth ${if (enable) "enable" else "disable"}"

    /** Airplane mode needs the global write + the connectivity broadcast (two-step, both internal). */
    fun airplaneModePut(enable: Boolean): String =
        "settings put global airplane_mode_on ${if (enable) 1 else 0}"

    fun airplaneModeBroadcast(): String =
        "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"

    /**
     * `cmd uimode night yes|no|auto` (API 31+); on older builds the provider falls back to
     * `settings put secure ui_night_mode`. Kept as two builders so tests pin both shapes.
     */
    fun uimodeNight(mode: String): String {
        require(mode in setOf("yes", "no", "auto")) { "uimode night must be yes|no|auto" }
        return "cmd uimode night $mode"
    }

    fun uiNightModePut(value: Int): String = "settings put secure ui_night_mode $value"

    /** Orientation: 0 = auto-rotate on (accelerometer), 1..3 lock landscape/reverse/portrait. */
    fun accelerometerRotation(enabled: Boolean): String =
        "settings put system accelerometer_rotation ${if (enabled) 1 else 0}"

    fun userRotation(value: Int): String {
        require(value in 0..3) { "user_rotation must be 0..3" }
        return "settings put system user_rotation $value"
    }

    /** Font scale (0.85..1.30 sane window; Android's picker clamps further). */
    fun fontScale(scale: String): String {
        val v = scale.toBigDecimalOrNull() ?: throw IllegalArgumentException("font_scale must be numeric")
        require(v.toDouble() in 0.5..2.0) { "font_scale out of range" }
        return "settings put system font_scale $v"
    }

    /** Mock-location appop for OUR package (the only one a task may request; DANGEROUS). */
    fun mockLocationAppop(allow: Boolean): String =
        "appops set ${MOCK_LOCATION_PKG} android:mock_location ${if (allow) "allow" else "deny"}"

    fun mockLocationAppopGet(): String = "appops get ${MOCK_LOCATION_PKG} android:mock_location"

    /** Generic `settings put` — used by the device.settings write path (SecureSettingsHelper). */
    fun settingsPut(table: String, key: String, value: String): String {
        require(table in setOf("system", "secure", "global")) { "table must be system|secure|global" }
        return "settings put $table ${safeToken(key, "key")} ${shellSafeValue(value)}"
    }

    private fun shellSafePermission(permission: String): String {
        require(permission.isNotEmpty() && permission.length <= 220) { "permission name has an unexpected length" }
        require(permission.all { it.isLetterOrDigit() || it in "._" }) {
            "permission name contains unexpected characters"
        }
        return permission
    }

    private fun shellSafeValue(value: String): String {
        require(value.length <= 1000) { "settings value too long" }
        // Values ride single quotes like input text; the allowlist already refused metacharacters
        // for task commands, and this builder is only reached through provider-internal paths.
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    private fun safeToken(token: String, what: String): String {
        require(token.isNotEmpty() && token.length <= 220 && token.all { it.isLetterOrDigit() || it in "._-" }) {
            "$what contains unexpected characters"
        }
        return token
    }

    private fun shellSafePackage(pkg: String): String {
        require(pkg.isNotEmpty() && pkg.length <= 220) { "package name has an unexpected length" }
        require(pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            "package name contains unexpected characters"
        }
        return pkg
    }

    const val MAX_LOG_LINES = 5000
    const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024
    const val MOCK_LOCATION_PKG = "app.autohost"
}
