package com.claudemobile.core.data.providers.network

import javax.inject.Qualifier

/**
 * Qualifier distinguishing the [okhttp3.OkHttpClient] dedicated to
 * `Provider_Profile` connection tests from any other OkHttp clients in the
 * dependency graph.
 *
 * The connection-test client is tuned for a single short probe
 * (`callTimeout(15s)` / `connectTimeout(10s)` / `readTimeout(10s)`) and is
 * deliberately configured **without** any `HttpLoggingInterceptor` so that the
 * probe's Authorization / x-api-key headers cannot leak into diagnostics
 * (R7 AC5, R10 AC4; see design §"Hilt 模块增量").
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ProviderTestClient
