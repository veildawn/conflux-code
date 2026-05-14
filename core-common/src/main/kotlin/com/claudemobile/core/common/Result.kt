package com.claudemobile.core.common

/**
 * A discriminated union representing either a successful value or a failure with an error.
 * Provides a domain-specific alternative to kotlin.Result that supports sealed hierarchies
 * and custom error types.
 */
public sealed interface AppResult<out T> {
    /** Represents a successful outcome containing [value]. */
    public data class Success<out T>(val value: T) : AppResult<T>

    /** Represents a failed outcome containing an [error]. */
    public data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * Structured error type for propagation across architecture layers.
 */
public data class AppError(
    val message: String,
    val code: ErrorCode = ErrorCode.UNKNOWN,
    val cause: Throwable? = null,
)

/**
 * Categorized error codes for structured error handling.
 */
public enum class ErrorCode {
    UNKNOWN,
    NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_ARGUMENT,
    PERMISSION_DENIED,
    UNAVAILABLE,
    NETWORK_ERROR,
    TIMEOUT,
    PROCESS_ERROR,
    STORAGE_ERROR,
    KEYSTORE_ERROR,
}

// --- Extension functions for ergonomic Result usage ---

/** Creates a successful [AppResult] wrapping [value]. */
public fun <T> T.asSuccess(): AppResult<T> = AppResult.Success(this)

/** Creates a failed [AppResult] from this [AppError]. */
public fun AppError.asFailure(): AppResult<Nothing> = AppResult.Failure(this)

/** Returns `true` if this result is [AppResult.Success]. */
public val <T> AppResult<T>.isSuccess: Boolean
    get() = this is AppResult.Success

/** Returns `true` if this result is [AppResult.Failure]. */
public val <T> AppResult<T>.isFailure: Boolean
    get() = this is AppResult.Failure

/** Returns the success value or `null` if this is a failure. */
public fun <T> AppResult<T>.getOrNull(): T? = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> null
}

/** Returns the success value or the result of [defaultValue] if this is a failure. */
public inline fun <T> AppResult<T>.getOrElse(defaultValue: (AppError) -> T): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> defaultValue(error)
}

/** Returns the success value or throws the error's cause (or an [IllegalStateException]). */
public fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw error.cause ?: IllegalStateException(error.message)
}

/** Transforms the success value using [transform], propagating failures unchanged. */
public inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/** Transforms the success value using [transform] which itself returns an [AppResult]. */
public inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Success -> transform(value)
        is AppResult.Failure -> this
    }

/** Transforms the error using [transform], propagating successes unchanged. */
public inline fun <T> AppResult<T>.mapError(transform: (AppError) -> AppError): AppResult<T> =
    when (this) {
        is AppResult.Success -> this
        is AppResult.Failure -> AppResult.Failure(transform(error))
    }

/** Executes [action] if this is a success, returning the original result. */
public inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(value)
    return this
}

/** Executes [action] if this is a failure, returning the original result. */
public inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * Wraps a suspending [block] in a try-catch, returning [AppResult.Success] on success
 * or [AppResult.Failure] on exception.
 */
public inline fun <T> runCatching(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: Throwable) {
        AppResult.Failure(
            AppError(
                message = e.message ?: "Unknown error",
                cause = e,
            )
        )
    }

/**
 * Suspending variant of [runCatching] for coroutine-based operations.
 */
public suspend inline fun <T> runSuspendCatching(block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (e: Throwable) {
        AppResult.Failure(
            AppError(
                message = e.message ?: "Unknown error",
                cause = e,
            )
        )
    }
