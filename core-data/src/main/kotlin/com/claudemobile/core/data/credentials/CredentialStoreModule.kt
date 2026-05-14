package com.claudemobile.core.data.credentials

import com.claudemobile.core.domain.repository.CredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class CredentialStoreModule {

    @Binds
    @Singleton
    public abstract fun bindCredentialStore(
        impl: CredentialStoreImpl,
    ): CredentialStore
}
