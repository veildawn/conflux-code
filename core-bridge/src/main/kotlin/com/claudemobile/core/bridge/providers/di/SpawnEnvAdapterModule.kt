package com.claudemobile.core.bridge.providers.di

import com.claudemobile.core.bridge.providers.SpawnEnvAdapter
import com.claudemobile.core.bridge.providers.SpawnEnvAdapterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [SpawnEnvAdapterImpl] as the application-wide
 * [SpawnEnvAdapter] singleton.
 *
 * Installed in [SingletonComponent] so that the same adapter instance is
 * reused across every `CliBridge.spawn` call — but note the adapter
 * itself is intentionally stateless and reads `ProviderProfileStore.getActive`
 * fresh on every invocation (Property 9).
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class SpawnEnvAdapterModule {

    @Binds
    @Singleton
    public abstract fun bindSpawnEnvAdapter(
        impl: SpawnEnvAdapterImpl,
    ): SpawnEnvAdapter
}
