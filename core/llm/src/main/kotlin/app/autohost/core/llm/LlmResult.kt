package app.autohost.core.llm

/**
 * P13-01: a two-typed result for the model client (the stdlib `Result` is single-typed and
 * Throwable-bound; [LlmError] is not a Throwable — expected failures are values, per
 * `docs/CONVENTIONS.md`). Mirrors `core/common`'s `AhResult` shape with the llm error type.
 */
sealed interface LlmResult<out T> {
    data class Ok<T>(val value: T) : LlmResult<T>
    data class Err(val error: LlmError) : LlmResult<Nothing>

    fun okOrNull(): T? = (this as? Ok<T>)?.value
    fun errorOrNull(): LlmError? = (this as? Err)?.error
}

fun <T> LlmResult<T>.ok(): T = (this as LlmResult.Ok<T>).value

object LlmResults {
    fun <T> ok(value: T): LlmResult<T> = LlmResult.Ok(value)
    fun <T> err(error: LlmError): LlmResult<T> = LlmResult.Err(error)
}
