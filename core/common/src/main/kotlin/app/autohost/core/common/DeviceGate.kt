package app.autohost.core.common

/**
 * Marks an instrumented test as evidence for a gate item (docs/CONVENTIONS.md §Testing,
 * docs/GATES.md holds the procedures). The annotation carries no behaviour — it maps test runs to
 * gate ids so `docs/PROGRESS.md` evidence lines can cite test classes directly.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeviceGate(val id: String)
