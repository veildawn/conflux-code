package com.claudemobile.core.domain.providers

/**
 * Classified failure conditions reported by [ProviderProfileStore]
 * mutating operations through [Result.failure].
 *
 * These are modeled as a sealed class (rather than thrown exceptions) so
 * that callers are forced by the compiler to handle each case exhaustively
 * and so that UI surfaces can route each classification to a localized,
 * actionable message without string-matching on exception types.
 *
 * Instances never carry sensitive values — no `apiKey`, no `baseUrl`, no
 * raw stack-trace text — so they are safe to include in diagnostics and
 * error telemetry without further redaction (R10.4).
 *
 * Requirements: 4.4, 4.6, 9.4.
 */
public sealed class ProviderProfileStoreError : Throwable() {

    /**
     * The Android Keystore is unavailable for encrypt / decrypt
     * operations. Raised by the store implementation when it catches
     * `KeyStoreException`, `InvalidKeyException`, or
     * `AEADBadTagException` during a profile read or write.
     *
     * When triggered during a bulk read (e.g., `list`, `observeProfiles`),
     * [profileId] is `null` because the failure is not attributable to a
     * specific profile; during [ProviderProfileStore.upsert],
     * [ProviderProfileStore.get], or [ProviderProfileStore.delete] the
     * affected id is supplied so the UI can point the user to
     * re-authenticate just that profile.
     *
     * Recovery: user re-enters the API key for the affected profile, or
     * invokes `deleteAll` to wipe all credentials and start over
     * (design §3, R9.4).
     *
     * Requirements: 9.4.
     */
    public data class KeystoreUnavailable(val profileId: String?) : ProviderProfileStoreError()

    /**
     * Attempted to persist a preset-derived profile whose `baseUrl` has
     * drifted from the preset registry value. The store rejects the write
     * to preserve the invariant that
     * `PresetReference.Preset(id) ⇒ profile.baseUrl == registry.findById(id).baseUrl`.
     *
     * Users editing a preset-derived profile should only be able to
     * modify `displayName` / `apiKey` / `model` / `smallFastModel`; if
     * they need a different base URL they must clone the profile as
     * [PresetReference.Custom].
     *
     * Requirements: 4.4.
     */
    public data object BaseUrlLocked : ProviderProfileStoreError()

    /**
     * The target [profileId] was not found in the store.
     *
     * Raised by [ProviderProfileStore.delete] and
     * [ProviderProfileStore.setActive] when supplied a non-null id that
     * does not correspond to any stored profile. Callers typically
     * translate this to a silent no-op (delete-after-delete races) or
     * to a user-visible "profile no longer exists" message.
     */
    public data class NotFound(val profileId: String) : ProviderProfileStoreError()
}
