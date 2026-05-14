package com.claudemobile.core.bridge.network

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing network monitoring and error parsing bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class NetworkModule {

    @Binds
    @Singleton
    public abstract fun bindNetworkMonitor(impl: NetworkMonitorImpl): NetworkMonitor

    public companion object {
        @Provides
        @Singleton
        public fun provideNetworkErrorParser(): NetworkErrorParser {
            return NetworkErrorParser()
        }
    }
}
