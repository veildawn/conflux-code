package com.claudemobile.core.domain.repository

/**
 * Secure storage for API keys and credentials, backed by the Android Keystore.
 */
public interface CredentialStore {

    /**
     * Returns the stored API key, or null if none is stored.
     */
    public suspend fun getApiKey(): String?

    /**
     * Stores the given API key securely.
     */
    public suspend fun setApiKey(key: String)

    /**
     * Deletes the stored API key.
     */
    public suspend fun deleteApiKey()

    /**
     * Returns true if an API key is currently stored.
     */
    public suspend fun hasApiKey(): Boolean

    /**
     * Returns a masked representation of the stored API key (e.g., "...xxxx"),
     * or null if no key is stored. Never reveals more than the last 4 characters.
     */
    public fun getMaskedApiKey(): String?

    /**
     * Legacy accessor: clears the single stored Anthropic API key.
     *
     * This is an alias for [deleteApiKey]. Introduced by the
     * `ai-provider-presets` spec (R8.3) so `LegacyKeyMigrator` can delete the
     * legacy credential after migrating it into a `ProviderProfile`. It is
     * **consumed only by `LegacyKeyMigrator`**; all new call sites should use
     * the provider-profile store instead.
     *
     * The default implementation delegates to [deleteApiKey] so existing
     * implementations do not need to override this method. The method will be
     * removed once the migration window closes.
     */
    public suspend fun clearApiKey(): Unit = deleteApiKey()
}
