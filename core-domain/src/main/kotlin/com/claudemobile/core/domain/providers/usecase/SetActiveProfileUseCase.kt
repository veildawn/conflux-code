package com.claudemobile.core.domain.providers.usecase

import com.claudemobile.core.common.AppResult
import com.claudemobile.core.domain.providers.ProviderProfileStore
import javax.inject.Inject

/**
 * Sets, or clears, the Active_Profile reference.
 *
 * Pass a non-null `profileId` to mark that profile as active; pass
 * `null` to clear the active reference (used by deletion flows and the
 * "wipe credentials" affordance).
 *
 * The store guarantees observers are notified within 200 ms of a
 * successful write (R5 AC2, R11 AC6). A non-null id that does not
 * correspond to a stored profile fails with [com.claudemobile.core.common.ErrorCode.NOT_FOUND]
 * via [toAppResult].
 *
 * Requirements: 5.1, 5.2.
 */
public class SetActiveProfileUseCase @Inject constructor(
    private val store: ProviderProfileStore,
) {

    public suspend operator fun invoke(profileId: String?): AppResult<Unit> =
        store.setActive(profileId).toAppResult { Unit }
}
