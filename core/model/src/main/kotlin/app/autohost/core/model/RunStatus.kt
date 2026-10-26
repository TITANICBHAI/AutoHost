package app.autohost.core.model

enum class RunStatus(val terminal: Boolean) {
    QUEUED(false),
    STARTING(false),
    RUNNING(false),
    PAUSED(false),
    WAITING_USER(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
    INTERRUPTED(true),
    SKIPPED(true);

    /** True while the run still occupies the device (WAITING_USER holds the run open for the person). */
    val active: Boolean get() = !terminal
}
