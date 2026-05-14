package com.claudemobile.core.domain.providers

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

/**
 * Example-based tests for [buildClaudeEnv] covering the behaviour
 * specified in design §5:
 *
 * - `ApiKey` auth-header style path → sets `ANTHROPIC_API_KEY`,
 *   clears `ANTHROPIC_AUTH_TOKEN`.
 * - `AuthToken` auth-header style path → sets `ANTHROPIC_AUTH_TOKEN`,
 *   clears `ANTHROPIC_API_KEY`.
 * - Absent (`null` / blank) `smallFastModel` → no
 *   `ANTHROPIC_SMALL_FAST_MODEL` key in the output.
 * - Present `smallFastModel` → `ANTHROPIC_SMALL_FAST_MODEL` set.
 * - Preservation of `HOME` / `PATH` / `TERM` / `LANG` from the base
 *   environment.
 *
 * Requirements validated: 6.2, 6.3, 6.4, 6.5, 6.6, 12.5.
 */
class EnvBuilderTest : DescribeSpec({

    val baseEnv: Map<String, String> = mapOf(
        "HOME" to "/data/data/com.claudemobile.app/files/home",
        "PATH" to "/data/data/com.claudemobile.app/files/usr/bin:/system/bin",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
    )

    fun profile(
        authHeaderStyle: AuthHeaderStyle,
        smallFastModel: String? = null,
        apiKey: String = "test-api-key-abcd1234",
        baseUrl: String = "https://api.moonshot.cn/anthropic",
        model: String = "kimi-k2-turbo-preview",
    ): ProviderProfile = ProviderProfile(
        profileId = "profile-fixture",
        displayName = "Fixture",
        presetReference = PresetReference.Custom,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        smallFastModel = smallFastModel,
        authHeaderStyle = authHeaderStyle,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    describe("auth-header style: ApiKey") {

        val env = buildClaudeEnv(baseEnv, profile(AuthHeaderStyle.ApiKey))

        it("sets ANTHROPIC_API_KEY to profile.apiKey (R6.3)") {
            env shouldContain ("ANTHROPIC_API_KEY" to "test-api-key-abcd1234")
        }

        it("does not set ANTHROPIC_AUTH_TOKEN (mutual exclusion, R6.3)") {
            env shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"
        }

        it("sets ANTHROPIC_BASE_URL to profile.baseUrl (R6.2)") {
            env shouldContain ("ANTHROPIC_BASE_URL" to "https://api.moonshot.cn/anthropic")
        }

        it("sets ANTHROPIC_MODEL to profile.model (R6.5)") {
            env shouldContain ("ANTHROPIC_MODEL" to "kimi-k2-turbo-preview")
        }
    }

    describe("auth-header style: AuthToken") {

        val env = buildClaudeEnv(baseEnv, profile(AuthHeaderStyle.AuthToken))

        it("sets ANTHROPIC_AUTH_TOKEN to profile.apiKey (R6.4)") {
            env shouldContain ("ANTHROPIC_AUTH_TOKEN" to "test-api-key-abcd1234")
        }

        it("does not set ANTHROPIC_API_KEY (mutual exclusion, R6.4)") {
            env shouldNotContainKey "ANTHROPIC_API_KEY"
        }
    }

    describe("auth-header mutual exclusion against stale base values") {

        it("strips stale ANTHROPIC_AUTH_TOKEN from base when style = ApiKey") {
            val polluted = baseEnv + mapOf(
                "ANTHROPIC_AUTH_TOKEN" to "stale-token",
                "ANTHROPIC_API_KEY" to "stale-key",
            )

            val env = buildClaudeEnv(polluted, profile(AuthHeaderStyle.ApiKey))

            env shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"
            env["ANTHROPIC_API_KEY"] shouldBe "test-api-key-abcd1234"
        }

        it("strips stale ANTHROPIC_API_KEY from base when style = AuthToken") {
            val polluted = baseEnv + mapOf(
                "ANTHROPIC_API_KEY" to "stale-key",
                "ANTHROPIC_AUTH_TOKEN" to "stale-token",
            )

            val env = buildClaudeEnv(polluted, profile(AuthHeaderStyle.AuthToken))

            env shouldNotContainKey "ANTHROPIC_API_KEY"
            env["ANTHROPIC_AUTH_TOKEN"] shouldBe "test-api-key-abcd1234"
        }
    }

    describe("ANTHROPIC_SMALL_FAST_MODEL (R6.6)") {

        it("is absent when profile.smallFastModel is null") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = null),
            )

            env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
        }

        it("is absent when profile.smallFastModel is an empty string") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = ""),
            )

            env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
        }

        it("is absent when profile.smallFastModel is blank whitespace") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = "   "),
            )

            env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
        }

        it("is set when profile.smallFastModel is a non-blank string") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = "glm-4-air"),
            )

            env shouldContain ("ANTHROPIC_SMALL_FAST_MODEL" to "glm-4-air")
        }

        it("overrides any stale ANTHROPIC_SMALL_FAST_MODEL from base") {
            val polluted = baseEnv + ("ANTHROPIC_SMALL_FAST_MODEL" to "stale-fast-model")

            val env = buildClaudeEnv(
                polluted,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = "glm-4-air"),
            )

            env["ANTHROPIC_SMALL_FAST_MODEL"] shouldBe "glm-4-air"
        }

        it("strips stale ANTHROPIC_SMALL_FAST_MODEL from base when profile value is null") {
            val polluted = baseEnv + ("ANTHROPIC_SMALL_FAST_MODEL" to "stale-fast-model")

            val env = buildClaudeEnv(
                polluted,
                profile(AuthHeaderStyle.AuthToken, smallFastModel = null),
            )

            env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
        }
    }

    describe("base-env preservation (HOME / PATH / TERM / LANG)") {

        val env = buildClaudeEnv(baseEnv, profile(AuthHeaderStyle.AuthToken))

        it("preserves HOME") {
            env shouldContain ("HOME" to "/data/data/com.claudemobile.app/files/home")
        }

        it("preserves PATH") {
            env shouldContain (
                "PATH" to "/data/data/com.claudemobile.app/files/usr/bin:/system/bin"
            )
        }

        it("preserves TERM") {
            env shouldContain ("TERM" to "xterm-256color")
        }

        it("preserves LANG") {
            env shouldContain ("LANG" to "en_US.UTF-8")
        }

        it("preserves arbitrary non-conflicting base keys") {
            val polluted = baseEnv + mapOf(
                "CUSTOM_USER_VAR" to "keep-me",
                "ANDROID_DATA" to "/data",
            )

            val result = buildClaudeEnv(polluted, profile(AuthHeaderStyle.AuthToken))

            result shouldContain ("CUSTOM_USER_VAR" to "keep-me")
            result shouldContain ("ANDROID_DATA" to "/data")
        }

        it("does not mutate the input base map") {
            val mutable = baseEnv.toMutableMap()
            val snapshot = mutable.toMap()

            buildClaudeEnv(mutable, profile(AuthHeaderStyle.AuthToken))

            mutable shouldBe snapshot
        }
    }

    describe("full env shape sanity check") {

        it("ApiKey path produces the expected five Anthropic keys") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(
                    authHeaderStyle = AuthHeaderStyle.ApiKey,
                    smallFastModel = "fast-1",
                    apiKey = "k-xyz",
                    baseUrl = "https://example.test/api",
                    model = "main-1",
                ),
            )

            env shouldContainKey "ANTHROPIC_BASE_URL"
            env shouldContainKey "ANTHROPIC_API_KEY"
            env shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"
            env shouldContainKey "ANTHROPIC_MODEL"
            env shouldContainKey "ANTHROPIC_SMALL_FAST_MODEL"
            // Base preservation
            env shouldContainKey "HOME"
            env shouldContainKey "PATH"
            env shouldContainKey "TERM"
            env shouldContainKey "LANG"
        }

        it("AuthToken path with null smallFastModel produces four Anthropic keys") {
            val env = buildClaudeEnv(
                baseEnv,
                profile(
                    authHeaderStyle = AuthHeaderStyle.AuthToken,
                    smallFastModel = null,
                ),
            )

            env shouldContainKey "ANTHROPIC_BASE_URL"
            env shouldContainKey "ANTHROPIC_AUTH_TOKEN"
            env shouldNotContainKey "ANTHROPIC_API_KEY"
            env shouldContainKey "ANTHROPIC_MODEL"
            env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
        }
    }
})
