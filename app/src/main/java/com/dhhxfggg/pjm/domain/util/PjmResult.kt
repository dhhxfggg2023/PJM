package com.dhhxfggg.pjm.domain.util

/**
 * PJM Standardized Result Wrapper.
 * Replaces nullable returns and raw exceptions with a predictable outcome model.
 */
sealed class PjmResult<out T> {
    data class Success<out T>(
        val data: T,
    ) : PjmResult<T>()

    data class Failure(
        val message: String,
        val errorCode: Int = -1,
        val exception: Throwable? = null,
    ) : PjmResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data

    fun exceptionOrNull(): Throwable? = (this as? Failure)?.exception
}

inline fun <T> PjmResult<T>.onSuccess(action: (T) -> Unit): PjmResult<T> {
    if (this is PjmResult.Success) action(data)
    return this
}

inline fun <T> PjmResult<T>.onFailure(action: (PjmResult.Failure) -> Unit): PjmResult<T> {
    if (this is PjmResult.Failure) action(this)
    return this
}
