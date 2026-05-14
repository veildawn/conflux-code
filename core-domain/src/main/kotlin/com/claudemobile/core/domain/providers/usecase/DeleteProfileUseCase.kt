package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.domain.providers.ProviderProfileStore
import javax.inject.Inject

/**
 * Deletes the stored [com.claudemobile.core.domain.providers.ProviderProfile]
 * with the given [profileId].
 *
 * Delegates entirely to [ProviderProfileStore.delete]; the store
 * implementation is responsible for the secure-erase contract
 * (overwrite-then-remove of the encrypted apiKey blob, R4 AC5) and for
 * clearing the active reference when the deleted profile was the
 * Active_Profile (R4 AC6, Property 7).
 *
 * Requirements: 4.5.
 */
public class DeleteProfileUseCase @Inject constructor(
    private val store: ProviderProfileStore,
) {

    public suspend operator fun invoke(profileId: String): AppResult<Unit> {
        if (profileId.isBlank()) {
            return AppError(
                message = "Profile ID must not be blank.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        return store.delete(profileId).toAppResult { Unit }
    }
}
