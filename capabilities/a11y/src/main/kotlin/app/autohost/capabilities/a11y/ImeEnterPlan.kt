package app.autohost.capabilities.a11y

/**
 * P12-10 (RM-A1): the IME-Enter decision — `ACTION_IME_ENTER` needs API 30; below that the click
 * fallback applies. Pure over the SDK int (the ApiGate value is injected by the caller), so the
 * table is JVM-tested without touching `Build.VERSION`.
 */
object ImeEnterPlan {

    const val IME_ENTER_MIN_SDK = 30

    sealed class Plan {
        /** Perform `ACTION_IME_ENTER` on the field. */
        data object ImeEnter : Plan()

        /** Fall back to the field's click action (pre-API 30 fields). */
        data object ClickFallback : Plan()
    }

    fun plan(sdkInt: Int): Plan =
        if (sdkInt >= IME_ENTER_MIN_SDK) Plan.ImeEnter else Plan.ClickFallback
}
