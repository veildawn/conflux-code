package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.providers.ProviderProfileStoreError

/**
 * Maps a [ProviderProfileStore][com.claudemobile.core.domain.providers.ProviderProfileStore]
 * mutation result (`kotlin.Result<Unit>`) onto the project-wide [AppResult]
 * type used by use cases.
 *
 * Centralised so that every provider use case translates the same store
 * error vocabulary the same way:
 *
 * | [ProviderProfileStoreError] variant     | Mapped [ErrorCode]               |
 * |----------------------------------------|-----------------------------------|
 * | [ProviderProfileStoreError.NotFound]   | [ErrorCode.NOT_FOUND]             |
 * | [ProviderProfileStoreError.BaseUrlLocked] | [ErrorCode.INVALID_ARGUMENT]   |
 * | [ProviderProfileStoreError.KeystoreUnavailable] | [ErrorCode.KEYSTORE_ERROR] |
 * | any other `Throwable`                  | [ErrorCode.STORAGE_ERROR]         |
 *
 * On success, the [valueOnSuccess] block produces the value to wrap in
 * [AppResult.Success]. The block is invoked only on success; it is not a
 * map over the existing `Unit` payload, it is a thunk so that callers can
 * return the original profile / id they had before the store call rather
 * than a meaningless `Unit`.
 *
 * The function is `internal` because it is an implementation detail
 * shared only across the provider use cases in this package; UI / data /
 * bridge layers translate store errors to their own surfaces directly.
 */
internal inline fun <T> Result<Unit>.toAppResult(valueOnSuccess: () -> T): AppResult<T> {
    return if (isSuccess) {
        valueOnSuccess().asSuccess()
    } else {
        val cause = exceptionOrNull()
        when (cause) {
            is ProviderProfileStoreError.NotFound -> AppError(
                message = "Provider profile not found: ${cause.profileId}",
                code = ErrorCode.NOT_FOUND,
                cause = cause,
            ).asFailure()
            ProviderProfileStoreError.BaseUrlLocked -> AppError(
                message = "Base URL of preset-derived profiles is fixed and cannot be changed.",
                code = ErrorCode.INVALID_ARGUMENT,
                cause = cause,
            ).asFailure()
            is ProviderProfileStoreError.KeystoreUnavailable -> AppError(
                message = "Keystore is unavailable; the API key cannot be encrypted/decrypted.",
                code = ErrorCode.KEYSTORE_ERROR,
                cause = cause,
            ).asFailure()
            null -> AppError(
                message = "Provider profile store reported a failure with no cause.",
                code = ErrorCode.STORAGE_ERROR,
            ).asFailure()
            else -> AppError(
                message = cause.message ?: "Provider profile store error.",
                code = ErrorCode.STORAGE_ERROR,
                cause = cause,
            ).asFailure()
        }
    }
}
