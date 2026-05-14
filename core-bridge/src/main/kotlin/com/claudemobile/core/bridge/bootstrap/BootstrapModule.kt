package com.claudemobile.core.bridge.bootstrap

import com.claudemobile.core.domain.bridge.BootstrapManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the [BootstrapManager] and [PrefixExtractor] bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class BootstrapModule {

    @Binds
    @Singleton
    internal abstract fun bindBootstrapManager(
        impl: BootstrapManagerImpl
    ): BootstrapManager

    @Binds
    @Singleton
    internal abstract fun bindPrefixExtractor(
        impl: PrefixExtractorImpl
    ): PrefixExtractor
}
