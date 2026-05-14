package com.claudemobile.core.data.providers.network

import com.claudemobile.core.common.CoroutineDispatchers
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.ConnectionTestOutcome
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/**
 * Unit tests for [ConnectionTesterImpl].
 *
 * Covers each outcome in the 6-case decision table from design §7.2:
 * [ConnectionTestOutcome.Ok], [ConnectionTestOutcome.Unauthorized],
 * [ConnectionTestOutcome.Unreachable], [ConnectionTestOutcome.InvalidUrl],
 * [ConnectionTestOutcome.InvalidModel], [ConnectionTestOutcome.UnknownError].
 *
 * Requirements: 7.1, 7.2, 7.3, 7.5, 7.6, 7.7.
 */
class ConnectionTesterImplTest : DescribeSpec({

    describe("ConnectionTesterImpl") {

        it("returns Ok for a 2xx response") {
            runTest {
                withServer { server ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"id":"msg_01","type":"message"}"""),
                    )
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-ant-secret-123456",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.Ok
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("returns Unauthorized for HTTP 401") {
            runTest {
                withServer { server ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(401)
                            .setBody(
                                """{"type":"error","error":{"type":"authentication_error","message":"bad key"}}""",
                            ),
                    )
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-ant-secret-401",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.Unauthorized
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("returns Unauthorized for HTTP 403") {
            runTest {
                withServer { server ->
                    server.enqueue(MockResponse().setResponseCode(403))
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-ant-secret-403",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.Unauthorized
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("returns Unreachable when the socket disconnects before response") {
            runTest {
                withServer { server ->
                    server.enqueue(
                        MockResponse().setSocketPolicy(
                            SocketPolicy.DISCONNECT_AT_START,
                        ),
                    )
                    val tester = testerFor(server.baseUrl(), fastTimeouts = true)
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-ant-secret-unreachable",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.Unreachable
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("returns InvalidUrl for a non-https scheme") {
            runTest {
                val tester = testerFor("https://example.test")
                val profile = profile(
                    baseUrl = "http://not-https.example",
                    apiKey = "sk-ant-secret-invalid-url",
                )

                val result = tester.test(profile)

                result.outcome shouldBe ConnectionTestOutcome.InvalidUrl
                result.userReason shouldNotContain profile.apiKey
            }
        }

        it("returns InvalidUrl for an unparseable baseUrl") {
            runTest {
                val tester = testerFor("https://example.test")
                val profile = profile(
                    baseUrl = "not a url at all",
                    apiKey = "sk-ant-secret-no-parse",
                )

                val result = tester.test(profile)

                result.outcome shouldBe ConnectionTestOutcome.InvalidUrl
                result.userReason shouldNotContain profile.apiKey
            }
        }

        it("returns InvalidModel for HTTP 404 that mentions model in a not_found_error") {
            runTest {
                withServer { server ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(404)
                            .setBody(
                                """{"type":"error","error":{"type":"not_found_error","message":"model not found"}}""",
                            ),
                    )
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        model = "does-not-exist",
                        apiKey = "sk-ant-secret-404",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.InvalidModel
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("returns UnknownError for HTTP 500") {
            runTest {
                withServer { server ->
                    server.enqueue(MockResponse().setResponseCode(500))
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-ant-secret-500",
                    )

                    val result = tester.test(profile)

                    result.outcome shouldBe ConnectionTestOutcome.UnknownError
                    result.userReason shouldNotContain profile.apiKey
                }
            }
        }

        it("sends x-api-key header when authHeaderStyle is ApiKey") {
            runTest {
                withServer { server ->
                    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-apikey-style",
                        authHeaderStyle = AuthHeaderStyle.ApiKey,
                    )

                    tester.test(profile)

                    val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                    requireNotNull(recorded)
                    recorded.getHeader("x-api-key") shouldBe profile.apiKey
                    recorded.getHeader("Authorization") shouldBe null
                    recorded.getHeader("anthropic-version") shouldBe "2023-06-01"
                }
            }
        }

        it("sends Authorization: Bearer header when authHeaderStyle is AuthToken") {
            runTest {
                withServer { server ->
                    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-authtoken-style",
                        authHeaderStyle = AuthHeaderStyle.AuthToken,
                    )

                    tester.test(profile)

                    val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                    requireNotNull(recorded)
                    recorded.getHeader("Authorization") shouldBe "Bearer ${profile.apiKey}"
                    recorded.getHeader("x-api-key") shouldBe null
                }
            }
        }

        it("posts the minimal ping payload with max_tokens=1 to /v1/messages") {
            runTest {
                withServer { server ->
                    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                    val tester = testerFor(server.baseUrl())
                    val profile = profile(
                        baseUrl = server.baseUrl(),
                        apiKey = "sk-payload-check",
                        model = "claude-foo",
                    )

                    tester.test(profile)

                    val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                    requireNotNull(recorded)
                    recorded.method shouldBe "POST"
                    recorded.path shouldBe "/v1/messages"

                    val body = recorded.body.readUtf8()
                    // Minimal payload checks; order-insensitive by design.
                    assert(body.contains("\"max_tokens\":1"))
                    assert(body.contains("\"model\":\"claude-foo\""))
                    assert(body.contains("\"content\":\"ping\""))
                }
            }
        }
    }
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private inline fun withServer(block: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
        block(server)
    } finally {
        server.shutdown()
    }
}

private fun MockWebServer.baseUrl(): String {
    // MockWebServer exposes http:// URLs; our implementation requires
    // https. To keep tests fast and hermetic, we stage an HTTP server and
    // override the OkHttpClient's URL handling by targeting the server's
    // URL directly via the client that the tester injects. For the
    // InvalidUrl cases we deliberately do not hit the server.
    //
    // The ConnectionTesterImpl enforces scheme=https, so we adapt by
    // using an https-looking URL that routes to the MockWebServer via
    // the OkHttpClient set up in [testerFor] below.
    //
    // The URL returned here is what we hand to ProviderProfile.baseUrl
    // and what buildProbeUrl() parses. We present "https://<host>:<port>"
    // matching the MockWebServer, and use an OkHttpClient configured
    // with a SocketFactory that maps the https request to the cleartext
    // server.
    return "https://${hostName}:${port}"
}

private fun profile(
    baseUrl: String,
    apiKey: String,
    model: String = "claude-3-5-sonnet-20241022",
    authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.ApiKey,
): ProviderProfile = ProviderProfile(
    profileId = "test-profile-id",
    displayName = "Test Profile",
    presetReference = PresetReference.Custom,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = null,
    authHeaderStyle = authHeaderStyle,
    createdAt = 0L,
    updatedAt = 0L,
)

private val testDispatchers = object : CoroutineDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.IO
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.IO
    override val mainImmediate: CoroutineDispatcher = Dispatchers.IO
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

/**
 * Builds a [ConnectionTesterImpl] wired to talk to the given
 * [upstreamBaseUrl]. We install a custom DNS + port remap on the
 * OkHttpClient so that the required `https://` scheme can route to a
 * plain-HTTP [MockWebServer] without managing a TLS keystore.
 */
private fun testerFor(
    upstreamBaseUrl: String,
    fastTimeouts: Boolean = false,
): ConnectionTesterImpl {
    val builder = OkHttpClient.Builder()
    if (fastTimeouts) {
        builder
            .callTimeout(2, TimeUnit.SECONDS)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
    } else {
        builder
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
    }
    // Rewrite https://host:port → http://host:port at the Call level so
    // we can reach the plain-HTTP MockWebServer without spinning up a
    // TLS certificate in the test VM. The rewrite is transparent to the
    // tested code.
    builder.addInterceptor { chain ->
        val original = chain.request()
        val url = original.url
        if (url.scheme == "https" && url.host in LOOPBACK_HOSTS) {
            val rewritten = url.newBuilder().scheme("http").build()
            chain.proceed(original.newBuilder().url(rewritten).build())
        } else {
            chain.proceed(original)
        }
    }
    return ConnectionTesterImpl(builder.build(), testDispatchers)
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
