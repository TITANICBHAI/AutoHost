package app.autohost.core.common

import app.autohost.core.model.AhError
import app.autohost.core.model.ErrorCode

/**
 * Expected failures are values, never exceptions (CONVENTIONS.md): `Ok(value)` or `Err(AhError)`.
 * Exceptions stay reserved for programmer errors; nothing throws across the broker.
 */
sealed interface AhResult<out T> {
    data class Ok<T>(val value: T) : AhResult<T>
    data class Err(val error: AhError) : AhResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AhError? = (this as? Err)?.error

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw IllegalStateException("AhResult is Err: ${error.code} ${error.message}")
    }

    inline fun <R> map(transform: (T) -> R): AhResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun <R> flatMap(transform: (T) -> AhResult<R>): AhResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    /** Runs [block] when this is [Err] (side effects such as logging), then returns this unchanged. */
    inline fun onErr(block: (AhError) -> Unit): AhResult<T> {
        if (this is Err) block(error)
        return this
    }

    companion object {
        fun <T> ok(value: T): AhResult<T> = Ok(value)

        fun err(
            code: ErrorCode,
            message: String,
            reason: String? = null,
            fixAction: String? = null,
        ): AhResult<Nothing> = Err(AhError.of(code, message, reason, fixAction))

        fun err(error: AhError): AhResult<Nothing> = Err(error)
    }
}
