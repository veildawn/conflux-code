package com.claudemobile.core.domain.di

import com.claudemobile.core.domain.providers.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module exposing the built-in [ProviderRegistry] as a singleton.
 *
 * Binding is intentionally minimal — [ProviderRegistry.Default] holds no
 * mutable state and performs no I/O, so sharing a single instance across
 * the whole app is both safe and cheap.
 *
 * Requirements: 1.1, 1.2, 1.3 (design §"Hilt 模块增量").
 */
@Module
@InstallIn(SingletonComponent::class)
public object ProviderRegistryModule {

    @Provides
    @Singleton
    public fun provideProviderRegistry(): ProviderRegistry = ProviderRegistry.Default
}
