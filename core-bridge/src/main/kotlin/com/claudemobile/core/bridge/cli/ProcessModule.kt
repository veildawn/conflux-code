package com.claudemobile.core.bridge.cli

import com.claudemobile.core.common.CoroutineDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the [ProcessExecutor] binding using the
 * JNI-based implementation backed by the Termux terminal-emulator library.
 *
 * This module provides [JniProcessExecutor] as the singleton [ProcessExecutor]
 * for the application, enabling PTY-based process communication through the
 * Terminal_Emulator_Lib JNI interface.
 */
@Module
@InstallIn(SingletonComponent::class)
public object ProcessModule {

    @Provides
    @Singleton
    public fun provideProcessExecutor(
        dispatchers: CoroutineDispatchers,
    ): ProcessExecutor = JniProcessExecutor(dispatchers)
}
