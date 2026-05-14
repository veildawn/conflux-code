package com.claudemobile.core.domain.providers

import kotlinx.coroutines.flow.Flow

/**
 * Persistence gateway for user-owned [ProviderProfile]s and the currently
 * selected Active_Profile.
 *
 * The store is the single source of truth for provider-profile state; it is
 * implemented by `core-data` on top of EncryptedSharedPreferences + the
 * Android Keystore (see R9.1, design §3) but the contract is purely domain
 * and carries no Android dependencies so it can be used from pure tests and
 * from `core-bridge` without pulling platform code.
 *
 * Concurrency / reactivity contract:
 * - [observeProfiles] and [observeActiveProfile] emit on every committed
 *   mutation; the implementation guarantees downstream visibility within
 *   200 ms of a successful `upsert` / `setActive` / `delete` / `deleteAll`
 *   (R5.2, R11.6).
 * - [getActive] performs a **fresh** read on every invocation and must
 *   **never** return a cached snapshot. This is consumed by the bridge layer
 *   at spawn time and underpins R5.4 / R6.8 / R11.2 ("each Session re-reads
 *   the Active_Profile").
 * - All mutating operations report failure via [Result.failure] carrying a
 *   [ProviderProfileStoreError]; they must not throw for expected domain
 *   conditions (missing profile, locked base URL, keystore unavailable).
 *
 * Error surface: see [ProviderProfileStoreError] for the full classification.
 *
 * Requirements: 4.1, 4.3, 4.4, 4.6, 4.7, 5.1, 5.2, 9.1, 9.4, 9.5, 12.1,
 * 12.5.
 */
public interface ProviderProfileStore {

    /**
     * Cold [Flow] emitting the full profile list on every commit, sorted by
     * [ProviderProfile.updatedAt] descending (most-recently-edited first).
     *
     * Emits the current value on subscription and a new list on every
     * subsequent mutation. The emitted list is a defensive snapshot and is
     * safe to hold across coroutines.
     *
     * Requirements: 4.1, 4.7.
     */
    public fun observeProfiles(): Flow<List<ProviderProfile>>

    /**
     * Cold [Flow] emitting the current Active_Profile on every change.
     *
     * Emits `null` when no profile is active (either none has been selected
     * yet or the previously-active profile was deleted — see R4.6). The
     * implementation guarantees that a change becomes visible to subscribers
     * within 200 ms of a successful [setActive], [upsert] of the active
     * profile, or [delete] of the active profile (R5.2, R11.6).
     *
     * Requirements: 5.1, 5.2, 4.6.
     */
    public fun observeActiveProfile(): Flow<ProviderProfile?>

    /**
     * Snapshot of all currently stored profiles, sorted by
     * [ProviderProfile.updatedAt] descending.
     *
     * Prefer [observeProfiles] for UI surfaces that need to stay in sync
     * with mutations; use [list] only for one-shot reads (e.g., diagnostics
     * redaction iteration, migration guards).
     *
     * Requirements: 4.1.
     */
    public suspend fun list(): List<ProviderProfile>

    /**
     * Looks up a profile by its [ProviderProfile.profileId], or returns
     * `null` when no profile with that id is stored.
     *
     * Requirements: 4.7.
     */
    public suspend fun get(profileId: String): ProviderProfile?

    /**
     * Returns the current Active_Profile, or `null` when none is selected.
     *
     * **Every** invocation performs a fresh read against the underlying
     * store; callers must not memoize the result across spawn boundaries.
     * This is the canonical read path used by the bridge layer's
     * `SpawnEnvAdapter` (R5.4, R6.8, R11.2).
     *
     * Requirements: 5.1, 5.4, 6.8, 11.2.
     */
    public suspend fun getActive(): ProviderProfile?

    /**
     * Inserts or updates the supplied [profile].
     *
     * Semantics:
     * - On insert (new [ProviderProfile.profileId]), the implementation
     *   records the supplied [ProviderProfile.createdAt] / [ProviderProfile.updatedAt]
     *   verbatim and persists an encrypted JSON blob.
     * - On update (existing id), the implementation preserves the original
     *   [ProviderProfile.createdAt] and advances [ProviderProfile.updatedAt]
     *   monotonically (R4.3; Property 5).
     * - When [ProviderProfile.presetReference] is a
     *   [PresetReference.Preset] and [ProviderProfile.baseUrl] differs from
     *   the preset's `baseUrl`, the call fails with
     *   [ProviderProfileStoreError.BaseUrlLocked] (R4.4; Property 6).
     * - When the Android Keystore is unavailable (corrupted master key,
     *   `AEADBadTagException`, etc.), the call fails with
     *   [ProviderProfileStoreError.KeystoreUnavailable] carrying
     *   [ProviderProfile.profileId] (R9.4).
     *
     * Requirements: 4.3, 4.4, 4.7, 9.1, 9.4, 12.1.
     */
    public suspend fun upsert(profile: ProviderProfile): Result<Unit>

    /**
     * Deletes the profile with the given [profileId].
     *
     * Semantics:
     * - The implementation overwrites the stored `apiKey` field with a
     *   blank value before removing the entry so the last-written encrypted
     *   block no longer binds the raw key bytes (R4.5, design §3).
     * - When the deleted profile was the Active_Profile, the active
     *   reference is cleared in the same transaction and
     *   [observeActiveProfile] subsequently emits `null` (R4.6; Property 7).
     * - Missing profile yields [ProviderProfileStoreError.NotFound].
     *
     * Requirements: 4.6, 9.5.
     */
    public suspend fun delete(profileId: String): Result<Unit>

    /**
     * Sets the Active_Profile to the profile identified by [profileId], or
     * clears the active reference when [profileId] is `null`.
     *
     * A non-null [profileId] that does not correspond to a stored profile
     * fails with [ProviderProfileStoreError.NotFound]. Successful
     * invocations are observed by [observeActiveProfile] within 200 ms
     * (R5.2, R11.6).
     *
     * Requirements: 5.1, 5.2.
     */
    public suspend fun setActive(profileId: String?): Result<Unit>

    /**
     * Deletes every stored profile and clears the Active_Profile
     * reference. Used as a recovery affordance when the Keystore becomes
     * permanently unusable (R9.4) and by the "wipe credentials" settings
     * control (R9.5).
     *
     * After a successful call, [list] returns an empty list and
     * [getActive] returns `null` (Property 17).
     *
     * Requirements: 9.5.
     */
    public suspend fun deleteAll(): Result<Unit>
}
