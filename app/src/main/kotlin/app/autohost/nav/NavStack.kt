package app.autohost.nav

/**
 * The screens a person has walked through, so Back goes to the previous screen instead of leaving the app (U-02).
 * Pure and immutable: every operation returns a new stack. Routes are plain names (`Routes.name`) so this class has
 * no Android or Compose dependency and a JVM test can drive it.
 */
data class NavEntry(val route: String, val arg: String? = null)

data class NavStack(
    val entries: List<NavEntry> = listOf(NavEntry(ROOT)),
) {
    init {
        require(entries.isNotEmpty()) { "a navigation stack always has a root screen" }
    }

    val top: NavEntry get() = entries.last()

    /** True when Back should go to an earlier screen; false on the root screen (Back then leaves the app). */
    val canPop: Boolean get() = entries.size > 1

    /**
     * Go to [route]. A tab (root) screen replaces the whole stack. A screen already in the stack is returned to
     * (everything above it is dropped), which is what an on-screen "back" button means. Anything else is pushed.
     */
    fun navigate(route: String, arg: String? = null): NavStack {
        if (route in TABS) return NavStack(listOf(NavEntry(route, arg)))
        val existing = entries.indexOfFirst { it.route == route }
        return if (existing >= 0) {
            NavStack(entries.take(existing) + NavEntry(route, arg))
        } else {
            NavStack(entries + NavEntry(route, arg))
        }
    }

    /** One step back. On the root screen nothing changes. */
    fun pop(): NavStack = if (canPop) NavStack(entries.dropLast(1)) else this

    /** Flat text form for `onSaveInstanceState`: one `route` or `route|arg` string per entry. */
    fun encode(): List<String> = entries.map { if (it.arg == null) it.route else "${it.route}$SEP${it.arg}" }

    companion object {
        const val ROOT = "LIBRARY"

        /** The three tab screens; each is a root of its own. */
        val TABS = setOf("LIBRARY", "SETUP", "SCHEDULES")

        private const val SEP = '|'

        /**
         * Rebuild a saved stack. Unknown route names (an old save from another version) are dropped by [isKnown],
         * and an empty or damaged list gives the default stack — never a crash on restore.
         */
        fun decode(saved: List<String>?, isKnown: (String) -> Boolean): NavStack {
            val restored = saved.orEmpty().mapNotNull { text ->
                val cut = text.indexOf(SEP)
                val route = if (cut < 0) text else text.substring(0, cut)
                val arg = if (cut < 0) null else text.substring(cut + 1)
                if (isKnown(route)) NavEntry(route, arg) else null
            }
            return if (restored.isEmpty()) NavStack() else NavStack(restored)
        }
    }
}
