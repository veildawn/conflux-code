package com.claudemobile.core.common.di

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.common.DefaultCoroutineDispatchers
import com.claudemobile.core.common.DefaultTimeProvider
import com.claudemobile.core.common.DefaultUuidGenerator
import com.claudemobile.core.common.TimeProvider
import com.claudemobile.core.common.UuidGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing core-common singleton bindings.
 *
 * Provides [CoroutineDispatchers], [TimeProvider], and [UuidGenerator]
 * as singletons available throughout the application.
 */
@Module
@InstallIn(SingletonComponent::class)
public object CommonModule {

    @Provides
    @Singleton
    public fun provideCoroutineDispatchers(): CoroutineDispatchers =
        DefaultCoroutineDispatchers()

    @Provides
    @Singleton
    public fun provideTimeProvider(): TimeProvider =
        DefaultTimeProvider()

    @Provides
    @Singleton
    public fun provideUuidGenerator(): UuidGenerator =
        DefaultUuidGenerator()
}
