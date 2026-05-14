package com.claudemobile.feature.chat.di

import com.claudemobile.feature.chat.AccessibilityStateProvider
import com.claudemobile.feature.chat.AndroidAccessibilityStateProvider
import com.claudemobile.feature.chat.AndroidClipboardManager
import com.claudemobile.feature.chat.ClipboardManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatModule {

    @Binds
    @Singleton
    abstract fun bindClipboardManager(
        impl: AndroidClipboardManager,
    ): ClipboardManager

    @Binds
    @Singleton
    abstract fun bindAccessibilityStateProvider(
        impl: AndroidAccessibilityStateProvider,
    ): AccessibilityStateProvider
}
