package com.claudemobile.core.data.providers.migration

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the `ai-provider-presets` legacy migrator.
 *
 * [LegacyKeyMigrator] declares an `@Inject`-annotated constructor and is
 * marked `@Singleton`, so Hilt synthesizes the `@Provides` method
 * automatically. The collaborator bindings — `CredentialStore`,
 * `SettingsStore`, `ProviderProfileStore`, and `DataStore<Preferences>`
 * — are already supplied by their respective modules
 * (`CredentialStoreModule`, `SettingsModule`, `ProviderProfileModule`).
 *
 * This module exists primarily as an explicit anchor in the DI graph
 * for the migrator: it makes the singleton scope discoverable in the
 * source tree (`@InstallIn(SingletonComponent::class)`), keeps the
 * migration package self-contained, and provides a stable extension
 * point for any future bindings (e.g., a feature-flagged no-op
 * implementation) without disrupting the consumers.
 *
 * Requirements: 8.1, 11.1.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class LegacyKeyMigratorModule
