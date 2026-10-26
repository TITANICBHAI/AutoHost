package app.autohost.core.common

/**
 * The single place the app's platform API level is readable from pure code (AGENTS.md §3 rule 4: provider
 * `availability()` may also read `Build.VERSION.SDK_INT` directly). The app installs the real value at process
 * start; unit tests install a fake. Until something is installed [sdkInt] is 0 — every version-gated feature is
 * unavailable (fail closed), never silently "modern".
 */
object ApiGate {
    @Volatile
    private var installed: Int = 0

    /** Install the real `Build.VERSION.SDK_INT`. Called once at app start from `AutoHostApp`. */
    fun install(sdkInt: Int) {
        require(sdkInt > 0) { "ApiGate.install needs a real SDK_INT, got $sdkInt" }
        installed = sdkInt
    }

    /** For tests. */
    fun reset() {
        installed = 0
    }

    val sdkInt: Int get() = installed

    val isInstalled: Boolean get() = installed > 0

    fun atLeast(api: Int): Boolean = installed >= api

    fun below(api: Int): Boolean = installed < api
}
