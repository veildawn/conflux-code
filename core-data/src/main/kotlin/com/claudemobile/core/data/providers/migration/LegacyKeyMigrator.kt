/**
 * # LegacyKeyMigrator — cross-reference note
 *
 * **Supersedes** `android-claude-termux-client` base-spec Requirement 9,
 * AC 1 (`selected Claude model identifier` preference handling).
 *
 * The base spec stored a standalone `selected Claude model identifier`
 * preference in `SettingsStore` (key `SettingsKeys.MODEL_ID`). This
 * migrator is the **only** component that still reads that legacy key;
 * it does so exactly once, during the one-time migration from the
 * single-key credential flow to the multi-profile flow, and then clears
 * the key via [com.claudemobile.core.domain.repository.SettingsStore.clearModel].
 *
 * After migration the effective model is derived exclusively from the
 * Active_Profile's `model` field (`ai-provider-presets` R11 AC1–AC2);
 * the standalone preference is no longer written or read by any other
 * component.
 *
 * **Implements** `ai-provider-presets` Requirement 8 (Migration from the
 * Legacy Single API Key), specifically AC 1–5; Property 22
 * (migration idempotence).
 *
 * See also: `ProviderMigrationKeys.kt` for the DataStore flag that
 * prevents re-runs, `ProviderProfileStoreImpl.kt` for the destination
 * store, and `LegacyKeyMigratorModule.kt` for the Hilt binding.
 */
package com.claudemobile.core.data.providers.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.claudemobile.core.data.providers.migration.ProviderMigrationKeys.PROVIDER_MIGRATION_V1_DONE
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.repository.CredentialStore
import com.claudemobile.core.domain.repository.SettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Outcome of a single [LegacyKeyMigrator.runIfNeeded] invocation.
 *
 * Reported via [Result.success] so that callers (typically the bootstrap
 * flow on app start) can decide whether to surface a UI affordance —
 * e.g., navigate to the provider list when [Migrated], stay on the
 * onboarding screen when [SkippedNoLegacyKey], or simply continue when
 * the migration was already done in a prior launch ([SkippedAlreadyDone]
 * / [SkippedProfilesExist]).
 *
 * Failures are reported via [Result.failure] carrying the underlying
 * `ProviderProfileStoreError` (or any other exception thrown by the
 * collaborators); see design §8.2 step 5/6 for the rollback semantics.
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5.
 */
public sealed class MigrationOutcome {

    /**
     * The legacy API key was found, a new `ProviderProfile` was
     * persisted, set as the Active_Profile, and the legacy credentials
     * were cleared. The done-flag has been written.
     *
     * Maps to design §8.2 steps 2–8 (full success path).
     *
     * Requirements: 8.1, 8.2, 8.3.
     */
    public data class Migrated(val profileId: String) : MigrationOutcome()

    /**
     * No legacy API key was present at the time of the run; the
     * done-flag has been written so subsequent launches skip the check
     * outright.
     *
     * Maps to design §8.2 step 2 ("legacyKey == null ⇒ write done flag
     * and end").
     *
     * Requirements: 8.5.
     */
    public data object SkippedNoLegacyKey : MigrationOutcome()

    /**
     * The done-flag was already true; the migrator returned without
     * touching either store. Idempotent re-runs (Property 22) bottom
     * out here.
     *
     * Maps to design §8.2 step 1.
     *
     * Requirements: 8.5.
     */
    public data object SkippedAlreadyDone : MigrationOutcome()

    /**
     * One or more `ProviderProfile`s already exist in the store at the
     * time of the run, so the migration trigger condition (R8.1: "no
     * Provider_Profile and a stored legacy key") fails. The done-flag
     * is written so the migrator never re-runs in this profile-rich
     * state.
     *
     * Maps to the implicit "list non-empty ⇒ skip" guard from
     * design §8.1's trigger condition.
     *
     * Requirements: 8.5.
     */
    public data object SkippedProfilesExist : MigrationOutcome()
}

/**
 * One-shot legacy → multi-profile credential migrator.
 *
 * Implements design §8.2's eight-step algorithm:
 *
 *  1. Read [PROVIDER_MIGRATION_V1_DONE]; if `true`, return
 *     [MigrationOutcome.SkippedAlreadyDone].
 *  2. Read [CredentialStore.getApiKey]. When `null`, write the done-flag
 *     and return [MigrationOutcome.SkippedNoLegacyKey] (we record
 *     completion even on the no-legacy path so subsequent launches do
 *     not re-evaluate the trigger).
 *  3. Read [SettingsStore.getModel]; fall back to the documented
 *     default `"claude-3-5-sonnet-20241022"` when the legacy preference
 *     is absent or blank.
 *  4. Build a new [ProviderProfile] with `displayName = "Anthropic
 *     (default)"`, [PresetReference.Custom], `baseUrl =
 *     "https://api.anthropic.com"`, [AuthHeaderStyle.ApiKey], a freshly
 *     generated UUIDv4 `profileId`, and `createdAt == updatedAt == now`.
 *  5. [ProviderProfileStore.upsert] the profile. On failure, return the
 *     error verbatim and **do not** write the done-flag — the legacy
 *     credentials remain intact for retry on the next launch (R8.4).
 *  6. [ProviderProfileStore.setActive] the new profile id. On failure,
 *     same rollback semantics as step 5.
 *  7. [CredentialStore.clearApiKey] and [SettingsStore.clearModel] the
 *     legacy storage entries (R8.3).
 *  8. Write [PROVIDER_MIGRATION_V1_DONE] = `true` (R8.5).
 *
 * The migrator additionally guards against the "list non-empty"
 * variant of the trigger condition: when the profile store already
 * holds at least one profile (e.g., the user re-installed without
 * clearing app data, or a previous run completed but failed to write
 * the flag), the migrator records the done-flag and returns
 * [MigrationOutcome.SkippedProfilesExist] without reading the legacy
 * credentials. This preserves Property 22 (idempotence) without
 * touching legacy state.
 *
 * Idempotence (Property 22):
 *
 * Calling [runIfNeeded] N times on any starting state (legacy key
 * present/absent × profile present/absent × flag present/absent) yields
 * the same final state as calling it once. The first invocation drives
 * one of the four terminal branches above and leaves the flag set; all
 * subsequent invocations short-circuit at step 1.
 *
 * Rollback semantics:
 *
 * If `upsert` or `setActive` fails the migrator returns
 * `Result.failure(error)` **before** touching the legacy stores or the
 * done-flag. Concretely:
 *
 *  - The legacy `apiKey` is still readable via `CredentialStore.getApiKey()`.
 *  - The legacy model id is still readable via `SettingsStore.getModel()`.
 *  - The done-flag remains absent / `false`.
 *  - The new (failed) profile is **not** present in `ProviderProfileStore`
 *    when `upsert` fails. When `upsert` succeeded but `setActive`
 *    failed, the half-written profile is left in place; the next
 *    launch's run will short-circuit on the "profiles exist" guard
 *    (writing the flag and returning [SkippedProfilesExist]) so the
 *    user can manually finish the migration through the UI without
 *    losing the partially-migrated key.
 *
 * The migrator is intentionally a single-purpose collaborator. It is
 * the **only** legitimate consumer of [CredentialStore.clearApiKey] /
 * [SettingsStore.getModel] / [SettingsStore.clearModel]; both
 * accessors will be removed once the migration window closes.
 *
 * Thread-safety / re-entrancy:
 *
 * `runIfNeeded` is `suspend`-safe but not designed to be called
 * concurrently with itself. The bootstrap layer invokes it once on app
 * start before the UI begins observing the profile store. Concurrent
 * calls would still preserve correctness (each step is idempotent or
 * guarded) but might double-write the done-flag, which is harmless.
 *
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 11.1.
 */
@Singleton
public class LegacyKeyMigrator @Inject constructor(
    private val credentialStore: CredentialStore,
    private val settingsStore: SettingsStore,
    private val profileStore: ProviderProfileStore,
    private val migrationFlags: DataStore<Preferences>,
) {

    public companion object {
        /**
         * Display name assigned to the migrated profile per design §8.2
         * step 4. Kept as a literal here (rather than a localized
         * resource) because the migrator runs before the UI layer is
         * attached and the value is also shown verbatim in any
         * post-migration diagnostics; users can rename it through the
         * provider editor afterwards.
         */
        public const val MIGRATED_DISPLAY_NAME: String = "Anthropic (default)"

        /**
         * Endpoint assigned to the migrated profile (design §8.2 step
         * 4). Matches Anthropic's canonical Anthropic-compatible base
         * URL; the original single-key flow always pointed here.
         */
        public const val MIGRATED_BASE_URL: String = "https://api.anthropic.com"

        /**
         * Documented fallback model identifier (design §8.2 step 3 /
         * R8.1) when no legacy `selected Claude model identifier`
         * preference is found. Chosen to match the base-spec default
         * shipped before the multi-profile flow existed.
         */
        public const val FALLBACK_MODEL: String = "claude-3-5-sonnet-20241022"
    }

    /**
     * Runs the migration algorithm if the trigger conditions are met.
     *
     * See class doc for the full state machine. Never throws for
     * expected domain failures — they are returned as [Result.failure]
     * with the underlying `ProviderProfileStoreError`. Unexpected
     * exceptions (e.g., DataStore I/O) propagate to the caller per the
     * underlying tools' contracts.
     *
     * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5.
     */
    public suspend fun runIfNeeded(): Result<MigrationOutcome> {
        // Step 1: short-circuit if the migration is already done.
        val alreadyDone = readDoneFlag()
        if (alreadyDone) {
            return Result.success(MigrationOutcome.SkippedAlreadyDone)
        }

        // Trigger guard: when profiles already exist we are not in a
        // "fresh upgrade from the legacy single-key flow" state. Record
        // completion and exit without touching legacy storage.
        if (profileStore.list().isNotEmpty()) {
            writeDoneFlag()
            return Result.success(MigrationOutcome.SkippedProfilesExist)
        }

        // Step 2: read the legacy key.
        val legacyKey = credentialStore.getApiKey()
        if (legacyKey.isNullOrEmpty()) {
            writeDoneFlag()
            return Result.success(MigrationOutcome.SkippedNoLegacyKey)
        }

        // Step 3: read the legacy model id, falling back when absent or
        // blank. We treat blank-equals-absent because the legacy
        // SettingsStore may have written `""` historically.
        val legacyModelRaw = settingsStore.getModel()
        val model = legacyModelRaw?.trim().takeUnless { it.isNullOrEmpty() } ?: FALLBACK_MODEL

        // Step 4: build the migrated profile.
        val now = System.currentTimeMillis()
        val profile = ProviderProfile(
            profileId = UUID.randomUUID().toString(),
            displayName = MIGRATED_DISPLAY_NAME,
            presetReference = PresetReference.Custom,
            baseUrl = MIGRATED_BASE_URL,
            apiKey = legacyKey,
            model = model,
            smallFastModel = null,
            authHeaderStyle = AuthHeaderStyle.ApiKey,
            createdAt = now,
            updatedAt = now,
        )

        // Step 5: persist the profile. Rollback on failure.
        val upsertResult = profileStore.upsert(profile)
        if (upsertResult.isFailure) {
            return Result.failure(upsertResult.exceptionOrNull() ?: UnknownMigrationFailure)
        }

        // Step 6: set as active. Rollback on failure (legacy stores are
        // still untouched at this point so the next launch can retry).
        val setActiveResult = profileStore.setActive(profile.profileId)
        if (setActiveResult.isFailure) {
            return Result.failure(setActiveResult.exceptionOrNull() ?: UnknownMigrationFailure)
        }

        // Step 7: clear the legacy credentials.
        credentialStore.clearApiKey()
        settingsStore.clearModel()

        // Step 8: persist the done-flag.
        writeDoneFlag()

        return Result.success(MigrationOutcome.Migrated(profile.profileId))
    }

    /**
     * Reads [PROVIDER_MIGRATION_V1_DONE]; treats absent as `false`. We
     * use [first] rather than `data.first()` chained inline so that the
     * coroutine cleanly surfaces any DataStore read error rather than
     * being swallowed by a Flow operator.
     */
    private suspend fun readDoneFlag(): Boolean {
        val prefs = migrationFlags.data.first()
        return prefs[PROVIDER_MIGRATION_V1_DONE] == true
    }

    /**
     * Sets [PROVIDER_MIGRATION_V1_DONE] = `true`. The DataStore [edit]
     * operation is atomic and durable before the suspending call
     * returns, satisfying the "subsequent launches do not re-run"
     * requirement (R8.5) without an additional fsync.
     */
    private suspend fun writeDoneFlag() {
        migrationFlags.edit { prefs ->
            prefs[PROVIDER_MIGRATION_V1_DONE] = true
        }
    }
}

/**
 * Sentinel exception used when a `Result.failure` is returned without an
 * accompanying exception object — defensive only; the in-tree
 * [ProviderProfileStore] implementation always populates the exception
 * slot.
 */
private object UnknownMigrationFailure : RuntimeException("Migration failed without a cause") {
    private fun readResolve(): Any = UnknownMigrationFailure
}
