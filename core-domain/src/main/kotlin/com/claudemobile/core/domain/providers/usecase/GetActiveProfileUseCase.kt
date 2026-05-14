package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Reactive stream of the current Active_Profile (or `null` when none is
 * selected).
 *
 * Thin wrapper around [ProviderProfileStore.observeActiveProfile]; the
 * store guarantees emissions within 200 ms of any write affecting the
 * active reference (R5 AC2, R11 AC6).
 *
 * **Bridge layer note**: this Flow is for UI consumption. The bridge's
 * `SpawnEnvAdapter` must read the active profile fresh on every spawn
 * via [ProviderProfileStore.getActive] rather than caching the latest
 * Flow value (R5 AC4, R6 AC8, R11 AC2).
 *
 * Requirements: 5.1, 5.2, 5.3.
 */
public class GetActiveProfileUseCase @Inject constructor(
    private val store: ProviderProfileStore,
) {

    public operator fun invoke(): Flow<ProviderProfile?> = store.observeActiveProfile()
}
