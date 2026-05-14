package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderPreset
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import javax.inject.Inject

/**
 * Creates a new [ProviderProfile] derived from a [ProviderPreset].
 *
 * The use case copies the preset's fixed fields ([ProviderPreset.baseUrl],
 * [ProviderPreset.authHeaderStyle], default model identifiers) verbatim
 * and binds them to a user-supplied API key, optionally overriding the
 * display name, model, and small-fast-model values that the editor
 * pre-populates from the preset (R2 AC1–AC3, R1 AC5).
 *
 * Identity / timestamps:
 * - [profileId] is generated with the injected [UuidGenerator]; tests
 *   substitute a deterministic generator. The resulting id is unique
 *   across creations under any non-colliding [UuidGenerator]
 *   implementation (Property 18).
 * - `createdAt` and `updatedAt` are set to the same instant from
 *   [TimeProvider]; on subsequent edits the store advances `updatedAt`
 *   monotonically while preserving `createdAt`.
 *
 * Validation:
 * - The supplied [apiKey] must be non-empty (R2 AC4 is enforced at the
 *   UI layer; this use case provides a defence-in-depth check that
 *   returns [ErrorCode.INVALID_ARGUMENT] rather than persisting an
 *   empty key).
 * - Other field-level rules from `ProviderProfile.validate` are not
 *   re-run here: the preset itself supplies their values, which are by
 *   construction valid. The store performs its own write-time check
 *   against the preset's `baseUrl` (R4 AC4) so that no malformed
 *   profile can be persisted.
 *
 * Requirements: 2.1, 2.2, 2.3, 1.5.
 *
 * Properties: 18 (profileId uniqueness), 20 (preset field copying).
 */
public class CreateFromPresetUseCase @Inject constructor(
    private val store: ProviderProfileStore,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
) {

    /**
     * Builds and persists a new preset-derived profile.
     *
     * @param preset The built-in preset whose fixed fields seed the new
     *   profile. Must be a registry-known preset; the use case does not
     *   validate this because callers obtain [preset] from
     *   [com.claudemobile.core.domain.providers.ProviderRegistry] in the
     *   first place.
     * @param apiKey Plaintext API key supplied by the user. Must be
     *   non-empty.
     * @param displayNameOverride Optional override for the profile's
     *   [ProviderProfile.displayName]. When `null` or blank, the profile's
     *   `displayName` is the localized resource indicated by
     *   [ProviderPreset.displayNameResId]; because resources cannot be
     *   resolved in the domain layer, the literal `"preset:{presetId}"`
     *   sentinel is stored and the UI replaces it with the resolved
     *   string. Callers (typically the editor ViewModel) almost always
     *   pass an explicit override.
     * @param modelOverride Optional override for the profile's
     *   [ProviderProfile.model]. When `null` or blank, falls back to
     *   [ProviderPreset.defaultModel].
     * @param smallFastModelOverride Optional override for the profile's
     *   [ProviderProfile.smallFastModel]. When `null`, falls back to
     *   [ProviderPreset.defaultSmallFastModel] (which itself may be
     *   `null`).
     * @return [AppResult.Success] carrying the newly persisted profile,
     *   or [AppResult.Failure] when the key is empty or the store
     *   rejects the write.
     */
    public suspend operator fun invoke(
        preset: ProviderPreset,
        apiKey: String,
        displayNameOverride: String? = null,
        modelOverride: String? = null,
        smallFastModelOverride: String? = null,
    ): AppResult<ProviderProfile> {
        if (apiKey.isEmpty()) {
            return AppError(
                message = "API key must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val now = timeProvider.now().toEpochMilli()
        val profile = ProviderProfile(
            profileId = uuidGenerator.generate(),
            displayName = displayNameOverride
                ?.takeUnless { it.isBlank() }
                ?: "preset:${preset.presetId}",
            presetReference = PresetReference.Preset(preset.presetId),
            baseUrl = preset.baseUrl,
            apiKey = apiKey,
            model = modelOverride
                ?.takeUnless { it.isBlank() }
                ?: preset.defaultModel,
            smallFastModel = smallFastModelOverride ?: preset.defaultSmallFastModel,
            authHeaderStyle = preset.authHeaderStyle,
            createdAt = now,
            updatedAt = now,
        )

        return store.upsert(profile).toAppResult { profile }
    }
}
