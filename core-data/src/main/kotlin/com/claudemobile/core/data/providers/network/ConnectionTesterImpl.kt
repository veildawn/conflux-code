package com.claudemobile.core.data.providers.network

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ConnectionTestOutcome
import com.claudemobile.core.domain.providers.ConnectionTestResult
import com.claudemobile.core.domain.providers.ConnectionTester
import com.claudemobile.core.domain.providers.ProviderProfile
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Default [ConnectionTester] implementation.
 *
 * Issues a single `POST {baseUrl}/v1/messages` probe with a minimal
 * payload (`max_tokens=1`, `content="ping"`), the Anthropic-compatible
 * `anthropic-version` header, and the appropriate auth header selected
 * by [ProviderProfile.authHeaderStyle]. The response is classified into
 * one of the six outcomes defined in design §7.2.
 *
 * ## Security posture
 *
 * [ProviderProfile.apiKey] is placed only into the outgoing HTTP
 * request headers; it is **never**:
 *
 * - included in [ConnectionTestResult.userReason] (R7.5, R10.4);
 * - included in any exception message thrown or re-thrown by this class
 *   (the implementation only reads `e.javaClass.simpleName` from caught
 *   exceptions to decide the outcome and never stores the throwable
 *   itself on the result);
 * - written to any logging sink — the class does not use `android.util.Log`,
 *   SLF4J, or System.err / System.out.
 *
 * The injected [OkHttpClient] is qualified with [ProviderTestClient] and
 * is intentionally configured **without** an `HttpLoggingInterceptor`
 * (see [ProviderNetworkModule]); if the shared client were replaced with
 * one that did install a logging interceptor, `x-api-key` /
 * `Authorization` headers could leak into diagnostics. The qualifier
 * exists to isolate this risk.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.5, 7.6, 7.7.
 */
@Singleton
public class ConnectionTesterImpl @Inject constructor(
    @ProviderTestClient private val client: OkHttpClient,
    private val dispatchers: CoroutineDispatchers,
) : ConnectionTester {

    override suspend fun test(profile: ProviderProfile): ConnectionTestResult =
        withContext(dispatchers.io) {
            val url = buildProbeUrl(profile.baseUrl)
                ?: return@withContext result(
                    ConnectionTestOutcome.InvalidUrl,
                    REASON_INVALID_URL,
                )

            val request = buildRequest(url, profile)

            try {
                client.newCall(request).execute().use { response ->
                    classifyResponse(response)
                }
            } catch (e: IOException) {
                classifyException(e)
            } catch (e: RuntimeException) {
                // Defensive: OkHttp may wrap some misconfigurations in
                // IllegalStateException / IllegalArgumentException. We
                // still keep the apiKey out of the result.
                classifyException(e)
            }
        }

    // ---------------------------------------------------------------------
    // Request assembly
    // ---------------------------------------------------------------------

    private fun buildProbeUrl(baseUrl: String): HttpUrl? {
        val parsed = "${baseUrl.trimEnd('/')}$PROBE_PATH".toHttpUrlOrNull()
            ?: return null
        if (parsed.scheme != "https") return null
        return parsed
    }

    private fun buildRequest(url: HttpUrl, profile: ProviderProfile): Request {
        val body = PROBE_BODY_TEMPLATE
            .replace("__MODEL__", jsonEscape(profile.model))
            .toRequestBody(JSON_MEDIA_TYPE)

        val builder = Request.Builder()
            .url(url)
            .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .post(body)

        when (profile.authHeaderStyle) {
            AuthHeaderStyle.ApiKey ->
                builder.header(HEADER_X_API_KEY, profile.apiKey)
            AuthHeaderStyle.AuthToken ->
                builder.header(HEADER_AUTHORIZATION, "Bearer ${profile.apiKey}")
        }

        return builder.build()
    }

    // ---------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------

    private fun classifyResponse(response: Response): ConnectionTestResult {
        val code = response.code
        val bodyPeek = peekBody(response)

        return when {
            code in 200..299 -> result(ConnectionTestOutcome.Ok, REASON_OK)

            code == 401 || code == 403 ->
                result(ConnectionTestOutcome.Unauthorized, REASON_UNAUTHORIZED)

            code == 400 -> {
                // R7 AC2 / design §7.2: a 400 triggered purely by our
                // minimal payload shape (not by authentication) should
                // still be reported as success — it proves the endpoint
                // accepted the credential.
                val lower = bodyPeek.lowercase()
                if (lower.contains("invalid_request_error") &&
                    !lower.contains("authentication")
                ) {
                    result(ConnectionTestOutcome.Ok, REASON_OK)
                } else {
                    result(ConnectionTestOutcome.UnknownError, REASON_UNKNOWN)
                }
            }

            code == 404 || isNotFoundModelError(bodyPeek) ->
                result(ConnectionTestOutcome.InvalidModel, REASON_INVALID_MODEL)

            code in 500..599 ->
                result(ConnectionTestOutcome.UnknownError, REASON_UNKNOWN)

            else ->
                result(ConnectionTestOutcome.UnknownError, REASON_UNKNOWN)
        }
    }

    private fun isNotFoundModelError(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("\"not_found_error\"") && lower.contains("model")
    }

    private fun classifyException(e: Throwable): ConnectionTestResult = when (e) {
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException ->
            result(ConnectionTestOutcome.Unreachable, REASON_UNREACHABLE)
        is SSLException ->
            // SSL handshake failures map to Unreachable per design §7.2.
            result(ConnectionTestOutcome.Unreachable, REASON_UNREACHABLE)
        else ->
            result(ConnectionTestOutcome.UnknownError, REASON_UNKNOWN)
    }

    /**
     * Reads up to [BODY_PEEK_BYTES] of the response body for
     * classification purposes. Failures to read the body are swallowed
     * (we still have a usable HTTP status code). The returned string
     * never contains any request-side header values, so it cannot leak
     * [ProviderProfile.apiKey].
     */
    private fun peekBody(response: Response): String = try {
        response.peekBody(BODY_PEEK_BYTES).string()
    } catch (_: IOException) {
        ""
    }

    private fun result(
        outcome: ConnectionTestOutcome,
        reason: String,
    ): ConnectionTestResult = ConnectionTestResult(outcome, reason)

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private companion object {
        const val PROBE_PATH = "/v1/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val CONTENT_TYPE_JSON = "application/json"
        const val HEADER_ANTHROPIC_VERSION = "anthropic-version"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_X_API_KEY = "x-api-key"
        const val HEADER_AUTHORIZATION = "Authorization"

        /**
         * Fixed JSON body template. Only the `__MODEL__` placeholder is
         * substituted at runtime; the payload is otherwise constant so
         * that no user input besides the model identifier is forwarded.
         */
        const val PROBE_BODY_TEMPLATE =
            "{\"model\":__MODEL__,\"max_tokens\":1," +
                "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"

        /** Bytes of the response body to inspect for classification. */
        const val BODY_PEEK_BYTES: Long = 4_096L

        // Short, language-neutral diagnostic strings. UI callers should
        // localize via the ConnectionTestOutcome enum; these exist so
        // that log lines and fallback UI still convey something useful.
        // They never contain the apiKey or any provider-specific value.
        const val REASON_OK = "ok"
        const val REASON_UNAUTHORIZED = "unauthorized"
        const val REASON_UNREACHABLE = "unreachable"
        const val REASON_INVALID_URL = "invalid_url"
        const val REASON_INVALID_MODEL = "invalid_model"
        const val REASON_UNKNOWN = "unknown_error"

        val JSON_MEDIA_TYPE = CONTENT_TYPE_JSON.toMediaType()
    }
}
