package com.claudemobile.core.data.diagnostics

import com.claudemobile.core.domain.repository.DiagnosticsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class DiagnosticsModule {

    @Binds
    @Singleton
    internal abstract fun bindDiagnosticsRepository(
        impl: DiagnosticsRepositoryImpl
    ): DiagnosticsRepository

    public companion object {

        /**
         * Default [ProviderProfileSnapshot] used by
         * [DiagnosticsRepositoryImpl] to enumerate every stored
         * `ProviderProfile` at export time.
         *
         * This default returns an empty list, making the
         * `ai-provider-presets` R10 AC1–AC3 multi-profile redaction a
         * no-op until `ProviderProfileStore` is wired in (spec task 3.6).
         * Once the store lands, this binding should be overridden by a
         * `@Provides` in `ProviderProfileModule` that delegates to
         * `ProviderProfileStore.list()` — see the `ProviderRedaction.kt`
         * KDoc for the expected shape.
         *
         * Requirements: 10.1, 10.3 (pass-through safety).
         */
        @Provides
        @Singleton
        public fun provideDefaultProviderProfileSnapshot(): ProviderProfileSnapshot =
            ProviderProfileSnapshot { emptyList() }
    }
}
