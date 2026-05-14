package com.claudemobile.core.data.providers.network

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.providers.ConnectionTester
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Hilt module providing the [OkHttpClient] used by the
 * `Provider_Profile` connection-test probe.
 *
 * Design references:
 * - Requirements 7.2, 7.5, 10.4 (Connection_Test budget + key non-leakage).
 * - Design §"Hilt 模块增量": dedicated client under the [ProviderTestClient]
 *   qualifier so a shared OkHttp instance cannot accidentally pick up an
 *   [okhttp3.logging.HttpLoggingInterceptor] elsewhere in the graph.
 *
 * The client is deliberately minimal:
 * - `callTimeout(15s)` bounds the total probe duration (R7 AC2).
 * - `connectTimeout(10s)` / `readTimeout(10s)` keep individual network phases
 *   within the 15s envelope.
 * - No `HttpLoggingInterceptor` is installed: the probe's `x-api-key` /
 *   `Authorization: Bearer …` headers carry the user's `apiKey`, and those
 *   headers must never reach any logging sink (R7 AC5, R10 AC4).
 */
@Module
@InstallIn(SingletonComponent::class)
public object ProviderNetworkModule {

    @Provides
    @Singleton
    @ProviderTestClient
    public fun provideConnectionTestOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Intentionally no HttpLoggingInterceptor: R7 AC5, R10 AC4.
            .build()

    /**
     * Exposes the [ConnectionTester] implementation to the rest of the
     * graph. Bound here (rather than via `@Binds`) so that the module
     * remains an `object` consistent with the [provideConnectionTestOkHttp]
     * provider above.
     *
     * Requirements: 7.1, 7.2, 7.3.
     */
    @Provides
    @Singleton
    public fun provideConnectionTester(
        @ProviderTestClient client: OkHttpClient,
        dispatchers: CoroutineDispatchers,
    ): ConnectionTester = ConnectionTesterImpl(client, dispatchers)

    private const val CALL_TIMEOUT_SECONDS: Long = 15L
    private const val CONNECT_TIMEOUT_SECONDS: Long = 10L
    private const val READ_TIMEOUT_SECONDS: Long = 10L
}
