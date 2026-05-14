package com.claudemobile.core.data.providers.di

import javax.inject.Qualifier

/**
 * Hilt qualifier that distinguishes the [android.content.SharedPreferences]
 * instance backing the `ProviderProfileStore` (file: `provider_profiles.xml`)
 * from any other shared-preferences instance in the graph (notably the
 * base-spec `CredentialStore` file).
 *
 * The qualifier is required because both files are encrypted with the same
 * Android Keystore master key — without distinct injection points Hilt
 * cannot select the right instance, and a misroute would persist Provider
 * profiles into the legacy single-key store or vice versa.
 *
 * Requirements: 9.1 (separate file from base-spec credential store).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ProviderProfilesPrefs
