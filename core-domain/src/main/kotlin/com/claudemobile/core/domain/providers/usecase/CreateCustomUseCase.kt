package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileDraft
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ValidationField
import javax.inject.Inject

/**
 * Creates a new Custom (non-preset) [ProviderProfile] from a
 * user-edited [ProviderProfileDraft].
 *
 * Unlike [CreateFromPresetUseCase] the user supplies every field, so
 * this use case runs the full pure-syntactic validator
 * ([ProviderProfile.validate]) before delegating to the store. Failing
 * validation produces an [AppResult.Failure] carrying
 * [ErrorCode.INVALID_ARGUMENT] and the first reported field error in
 * the message, in the order
 * `displayName → baseUrl → apiKey → model → smallFastModel` (mirroring
 * [ValidationField] ordering for stable output).
 *
 * The draft's [ProviderProfileDraft.presetReference] is **ignored** —
 * this use case always emits a [PresetReference.Custom] profile, so
 * mistakes in the draft (e.g. a stale `Preset(...)` left over from a
 * cancelled preset flow) cannot leak into a Custom profile by accident.
 *
 * Requirements: 3.1, 3.2, 3.4, 3.5, 3.6, 3.7.
 *
 * Properties: 18 (profileId uniqueness).
 */
public class CreateCustomUseCase @Inject constructor(
    private val store: ProviderProfileStore,
    private val uuidGenerator: UuidGenerator,
    private val timeProvider: TimeProvider,
) {

    /**
     * @param draft Form values typed by the user. The draft's
     *   `presetReference` is forced to [PresetReference.Custom] before
     *   validation so the preset-locking rule (R4.4) does not fire.
     */
    public suspend operator fun invoke(draft: ProviderProfileDraft): AppResult<ProviderProfile> {
        val customDraft = draft.copy(presetReference = PresetReference.Custom)
        val validation = ProviderProfile.validate(customDraft)
        if (!validation.isValid) {
            // Surface a stable, deterministic message: pick the first
            // failing field in declaration order.
            val firstField = ValidationField.values().firstOrNull { it in validation.errors.keys }
            val firstError = firstField?.let(validation::errorFor)
            return AppError(
                message = "Provider profile draft is invalid: " +
                    "${firstField?.name ?: "unknown"}=${firstError?.name ?: "?"}.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val now = timeProvider.now().toEpochMilli()
        val profile = ProviderProfile(
            profileId = uuidGenerator.generate(),
            displayName = customDraft.displayName,
            presetReference = PresetReference.Custom,
            baseUrl = customDraft.baseUrl,
            apiKey = customDraft.apiKey,
            model = customDraft.model,
            smallFastModel = customDraft.smallFastModel?.takeIf { it.isNotEmpty() },
            authHeaderStyle = customDraft.authHeaderStyle,
            createdAt = now,
            updatedAt = now,
        )

        return store.upsert(profile).toAppResult { profile }
    }
}
