package app.autohost.ui2

/**
 * Lazy-list keys for the Setup tab. Both lists live in ONE LazyColumn, and a checklist step and a feature group are
 * both called NOTIFICATIONS, so a bare enum name crashed the tab on a real phone ("Key NOTIFICATIONS was already
 * used"). Every key carries its kind as a prefix; a test checks that no two keys can ever be equal.
 */
object ListKeys {
    fun step(id: StepId): String = "step:" + id.name
    fun group(group: FeatureGroup): String = "group:" + group.name
}
