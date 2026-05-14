package com.claudemobile.core.data.repository

import com.claudemobile.core.domain.repository.ConversationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class RepositoryModule {

    @Binds
    @Singleton
    internal abstract fun bindConversationRepository(
        impl: ConversationRepositoryImpl
    ): ConversationRepository
}
