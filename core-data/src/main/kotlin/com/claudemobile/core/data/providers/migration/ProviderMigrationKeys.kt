package com.claudemobile.core.data.providers.migration

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * DataStore preference keys used by the `ai-provider-presets` legacy
 * single-Anthropic-key migrator.
 *
 * The keys live in the shared application [Preferences] DataStore (the
 * same instance used by `SettingsStoreImpl`) so the migration flag
 * persists alongside other app preferences and is wiped only when the
 * user clears app data — preventing the migrator from re-running after
 * the user has explicitly migrated, retried, or rolled back.
 *
 * Requirements: 8.5, 11.1.
 */
internal object ProviderMigrationKeys {

    /**
     * Marks the legacy single-key → `ProviderProfile` migration as
     * complete.
     *
     * `true` ⇒ the migrator has either successfully copied the legacy
     * Anthropic API key into a `ProviderProfile` and cleared the legacy
     * credentials, or has positively determined that no legacy key
     * exists (steps 2 and 8 of design §8.2). In both terminal cases
     * the migrator must NOT run again on subsequent App launches
     * (R8.5).
     *
     * Absent / `false` ⇒ migration has not yet completed; the next
     * call to `LegacyKeyMigrator.runIfNeeded()` will re-evaluate the
     * preconditions.
     */
    val PROVIDER_MIGRATION_V1_DONE: Preferences.Key<Boolean> =
        booleanPreferencesKey("provider_migration_v1_done")
}
