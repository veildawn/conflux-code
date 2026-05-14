package com.claudemobile.core.data.providers.di

import android.content.Context
import android.content.SharedPreferences
import com.claudemobile.core.data.providers.ProviderProfileStoreImpl
import com.claudemobile.core.data.providers.ProviderProfilesPrefsFactory
import com.claudemobile.core.domain.providers.ProviderProfileStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module wiring the `ai-provider-presets` profile-storage layer
 * (design §"Hilt 模块增量"):
 *
 *  - Binds [ProviderProfileStoreImpl] as the singleton implementation of
 *    the domain-level [ProviderProfileStore] interface.
 *  - Provides the qualified [SharedPreferences] instance backing
 *    `provider_profiles.xml`. The instance is built via
 *    [ProviderProfilesPrefsFactory], which uses an `AES256_GCM` Android
 *    Keystore master key (the same key scheme as the base-spec
 *    `CredentialStoreModule` per R9 AC1) but a **separate file** so the
 *    legacy single-Anthropic-key store and the multi-profile store can
 *    evolve independently.
 *
 * Pattern: abstract class + companion-object `@Provides` mirrors the
 * existing [com.claudemobile.core.data.settings.SettingsModule] so the DI
 * graph stays uniform across modules.
 *
 * Requirements: 12.5.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class ProviderProfileModule {

    @Binds
    @Singleton
    internal abstract fun bindProviderProfileStore(
        impl: ProviderProfileStoreImpl,
    ): ProviderProfileStore

    public companion object {

        @Provides
        @Singleton
        @ProviderProfilesPrefs
        public fun provideProviderProfilesPrefs(
            @ApplicationContext context: Context,
        ): SharedPreferences = ProviderProfilesPrefsFactory.create(context)
    }
}
