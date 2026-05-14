package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import javax.inject.Inject

/**
 * Updates an existing [ProviderProfile] by reading the stored value
 * and applying a delta of allowed field changes.
 *
 * What can change depends on the profile's [PresetReference] (R4.2):
 *
 * - **Preset-derived profiles** ([PresetReference.Preset]): only
 *   `displayName`, `apiKey`, `model`, and `smallFastModel` may change.
 *   Attempts to alter `baseUrl` or `authHeaderStyle` via this use case
 *   are silently ignored — the use case re-emits the stored values
 *   for those fields. The store performs an additional defensive check
 *   and rejects any mismatched `baseUrl` with
 *   [com.claudemobile.core.domain.providers.ProviderProfileStoreError.BaseUrlLocked]
 *   (R4 AC4), surfaced through [toAppResult] as
 *   [ErrorCode.INVALID_ARGUMENT].
 *
 * - **Custom profiles** ([PresetReference.Custom]): every field except
 *   identity (`profileId`, `presetReference`, `createdAt`) may change.
 *
 * `updatedAt` is always advanced to the current [TimeProvider]
 * timestamp; `createdAt` is preserved (Property 5).
 *
 * Requirements: 4.2, 4.3, 4.4.
 */
public class UpdateProfileUseCase @Inject constructor(
    private val store: ProviderProfileStore,
    private val timeProvider: TimeProvider,
) {

    /**
     * Applies the supplied non-null deltas to the existing profile and
     * persists the result. Null parameters mean "leave unchanged".
     *
     * Returns [AppResult.Failure] with [ErrorCode.NOT_FOUND] when no
     * profile with [profileId] exists, and propagates store errors
     * (e.g. [ErrorCode.INVALID_ARGUMENT] on a base-URL-locked write,
     * [ErrorCode.KEYSTORE_ERROR] when the keystore is unavailable).
     */
    public suspend operator fun invoke(
        profileId: String,
        displayName: String? = null,
        apiKey: String? = null,
        model: String? = null,
        smallFastModel: String? = null,
        baseUrl: String? = null,
        authHeaderStyle: AuthHeaderStyle? = null,
    ): AppResult<ProviderProfile> {
        if (profileId.isBlank()) {
            return AppError(
                message = "Profile ID must not be blank.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val existing = store.get(profileId)
            ?: return AppError(
                message = "Provider profile not found: $profileId",
                code = ErrorCode.NOT_FOUND,
            ).asFailure()

        val isPresetDerived = existing.presetReference is PresetReference.Preset

        val updated = existing.copy(
            displayName = displayName ?: existing.displayName,
            apiKey = apiKey ?: existing.apiKey,
            model = model ?: existing.model,
            // smallFastModel is nullable in the domain model. We
            // distinguish "leave unchanged" (parameter == null) from
            // "set to null" by the caller — callers that wish to clear
            // the field must pass an empty string and rely on the
            // store/EnvBuilder treating empty / blank as absent
            // (`buildClaudeEnv` already filters blank values out at
            // spawn time per R6.6). This keeps the API simple while
            // matching the editor UX, which uses an empty TextField to
            // mean "no override".
            smallFastModel = smallFastModel ?: existing.smallFastModel,
            // Preset-derived profiles cannot change baseUrl / authStyle
            // through this use case (R4.2). Custom profiles can.
            baseUrl = if (isPresetDerived) {
                existing.baseUrl
            } else {
                baseUrl ?: existing.baseUrl
            },
            authHeaderStyle = if (isPresetDerived) {
                existing.authHeaderStyle
            } else {
                authHeaderStyle ?: existing.authHeaderStyle
            },
            updatedAt = timeProvider.now().toEpochMilli(),
        )

        return store.upsert(updated).toAppResult { updated }
    }
}
