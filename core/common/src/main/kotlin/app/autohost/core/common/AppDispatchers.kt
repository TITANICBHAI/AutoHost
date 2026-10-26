package app.autohost.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected dispatchers (CONVENTIONS.md: no implicit `Dispatchers` use). Main is only touched from UI code;
 * the engine and providers take [default]/[io] explicitly so tests can substitute test schedulers.
 */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
) {
    companion object {
        fun real(): AppDispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default)
    }
}
