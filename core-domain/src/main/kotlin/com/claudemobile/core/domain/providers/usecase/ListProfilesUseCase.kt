package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Reactive stream of all stored [ProviderProfile]s, sorted by
 * [ProviderProfile.updatedAt] descending (R4 AC1).
 *
 * Thin wrapper around [ProviderProfileStore.observeProfiles]; exposed as
 * a use case so that ViewModels collect a use-case dependency rather than
 * the store directly, keeping the dependency direction
 * `feature → domain` clean.
 *
 * Requirements: 4.1.
 */
public class ListProfilesUseCase @Inject constructor(
    private val store: ProviderProfileStore,
) {

    public operator fun invoke(): Flow<List<ProviderProfile>> = store.observeProfiles()
}
