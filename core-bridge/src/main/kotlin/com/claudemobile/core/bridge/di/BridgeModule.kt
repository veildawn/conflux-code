package com.claudemobile.core.bridge.di

import com.claudemobile.core.bridge.cli.CliBridgeImpl
import com.claudemobile.core.bridge.cli.ProotEnvironmentProviderImpl
import com.claudemobile.core.bridge.service.DefaultServicePreferencesStore
import com.claudemobile.core.bridge.service.ServicePreferencesStore
import com.claudemobile.core.bridge.workspace.DefaultWorkspacePreferencesStore
import com.claudemobile.core.bridge.workspace.WorkspaceManager
import com.claudemobile.core.bridge.workspace.WorkspaceManagerImpl
import com.claudemobile.core.bridge.workspace.WorkspacePreferencesStore
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.parser.OutputParser
import com.claudemobile.core.domain.parser.OutputParserImpl
import com.claudemobile.core.domain.usecase.ProotEnvironmentProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing bridge layer bindings.
 *
 * Note: [ProcessExecutor] is provided by [com.claudemobile.core.bridge.cli.ProcessModule]
 * using the JNI-based implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class BridgeModule {

    @Binds
    @Singleton
    public abstract fun bindCliBridge(impl: CliBridgeImpl): CliBridge

    @Binds
    @Singleton
    public abstract fun bindServicePreferencesStore(
        impl: DefaultServicePreferencesStore
    ): ServicePreferencesStore

    @Binds
    @Singleton
    public abstract fun bindWorkspaceManager(impl: WorkspaceManagerImpl): WorkspaceManager

    @Binds
    @Singleton
    public abstract fun bindWorkspacePreferencesStore(
        impl: DefaultWorkspacePreferencesStore
    ): WorkspacePreferencesStore

    @Binds
    @Singleton
    public abstract fun bindProotEnvironmentProvider(
        impl: ProotEnvironmentProviderImpl
    ): ProotEnvironmentProvider

    public companion object {
        @Provides
        @Singleton
        public fun provideOutputParser(): OutputParser = OutputParserImpl()
    }
}
